package ru.nastya.home

import android.content.Context
import android.util.Log
import org.json.JSONObject

object CamsRepository {
    private const val TAG = "NastyaHome"
    private const val FILE = "cams.json"

    fun loadLocal(ctx: Context): CamsData? = loadCached(ctx) ?: loadBundled(ctx)

    private fun loadBundled(ctx: Context): CamsData? = try {
        val l = RemoteStore.bundled(ctx, FILE)
        CamsParser.parse(l.text, l.base, l.source)
    } catch (e: Exception) {
        Log.w(TAG, "no bundled cams.json", e)
        null
    }

    private fun loadCached(ctx: Context): CamsData? {
        val l = RemoteStore.cached(ctx, FILE) ?: return null
        return try {
            CamsParser.parse(l.text, l.base, l.source)
        } catch (e: Exception) {
            Log.w(TAG, "cams cache is broken, ignoring", e)
            null
        }
    }

    fun lastFetchAt(ctx: Context): Long = RemoteStore.lastFetchAt(ctx, FILE)

    fun fetchRemote(ctx: Context): CamsData? {
        val l = RemoteStore.fetch(ctx, FILE) { text, base -> CamsParser.parse(text, base, base) } ?: return null
        return CamsParser.parse(l.text, l.base, l.source)
    }

    data class Stream(val url: String, val headers: Map<String, String>)

    /**
     * Камера выключена по расписанию владельца (так волки Миннесоты работают только днём по местному времени).
     * [startsInSec] — через сколько секунд она включится; -1, если сервис этого не сообщил.
     */
    class OffScheduleException(val startsInSec: Int) : Exception("камера выключена по расписанию, старт через $startsInSec с")

    /**
     * Разбор ответа ipcamlive getcamerastreamstate.php. Путь к потоку зависит от режима камеры
     * (timeshift → streams_timeshift, запись на камере → streams_storage, иначе streams).
     * Если у камеры включено расписание и сейчас не её время, бросает [OffScheduleException]
     * с обратным отсчётом до включения — иначе это выглядело бы как «не удалось узнать адрес».
     */
    fun ipcamliveStream(json: JSONObject): Stream {
        val d = json.optJSONObject("details") ?: error("ipcamlive: нет details")
        if (d.optString("operationtimewindowenabled") == "1" && d.optInt("operationtimewindowactive", 1) != 1) {
            throw OffScheduleException(d.optInt("operationtimewindow_starttimesec", -1))
        }
        if (d.optString("streamavailable") == "0") error("ipcamlive: камера выключена")
        val streamId = d.optString("streamid").takeIf { it.isNotBlank() && it != "null" } ?: error("ipcamlive: нет streamid")
        val host = d.optString("address").removePrefix("http://").removePrefix("https://").trim('/')
            .takeIf { it.isNotBlank() } ?: error("ipcamlive: нет address")
        val dir = when {
            d.optString("storageenabledoncamera") == "1" -> "streams_storage"
            d.optString("timeshiftenabled") == "1" -> "streams_timeshift"
            else -> "streams"
        }
        return Stream("https://$host/$dir/$streamId/stream.m3u8", mapOf("User-Agent" to BROWSER_UA))
    }

    /** Браузерный User-Agent: EarthCam и некоторые другие отдают 403 на «неизвестные» плееры. */
    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    private val SKYLINE_TOKEN = Regex("livee\\.m3u8\\?a=([A-Za-z0-9]+)")
    private val ANY_M3U8 = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")
    private val MOSZOO_WIDGET = Regex("<div class=\"widget-player-hls\"([^>]*)>")
    private val MOSZOO_ATTR = Regex("data-([\\w-]+)=['\"]([^'\"]*)['\"]")

    /** Превращает описание камеры в адрес HLS-потока. Блокирующий вызов (IPEYE спрашивает актуальный сервер). */
    fun resolve(cam: Cam): Stream {
        return when (cam.type) {
            "ipeye" -> {
                val dev = requireNotNull(cam.dev) { "ipeye: нет dev" }
                val server = try {
                    val info = Http.get("https://ipeye.ru/webs/stream_info.php?devid=$dev", connectTimeoutMs = 6_000, readTimeoutMs = 8_000)
                    JSONObject(info).optString("server").takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    Log.w(TAG, "ipeye stream_info failed for $dev: $e")
                    null
                } ?: requireNotNull(cam.server) { "ipeye: нет server" }
                Stream("https://$server/api/v1/stream/$dev/hls/index.m3u8", emptyMap())
            }
            "skyline" -> {
                val page = requireNotNull(cam.page) { "skyline: нет page" }
                val html = Http.get(page, headers = mapOf("User-Agent" to BROWSER_UA), readTimeoutMs = 12_000)
                val token = SKYLINE_TOKEN.find(html)?.groupValues?.get(1) ?: error("skyline: токен не найден на странице")
                // Важно: именно live.m3u8 на hd-auth; «livee.m3u8» со страницы — ловушка, сжигающая токен.
                Stream("https://hd-auth.skylinewebcams.com/live.m3u8?a=$token", emptyMap())
            }
            "rtspme" -> {
                // Двухшаговый токен rtsp.me / rtsp.ru: embed-токен → адрес HLS (живёт ~час, привязан к IP).
                val id = requireNotNull(cam.cam) { "rtspme: нет cam" }
                val host = cam.server ?: "rtsp.me"
                val step1 = JSONObject(Http.get("https://$host/api/embed/$id/token", headers = mapOf("Referer" to "https://$host/embed/$id/", "User-Agent" to BROWSER_UA)))
                val base = step1.getString("url").trimEnd('/')
                val token = step1.getString("token")
                val step2 = JSONObject(Http.get("$base/token/$token", headers = mapOf("User-Agent" to BROWSER_UA)))
                Stream(step2.getString("hls"), emptyMap())
            }
            "rostelecom" -> {
                // Публичные камеры Ростелекома: по токену ссылки lk-b2b.camera.rt.ru/sl/<token> отдаётся hls_url.
                val token = requireNotNull(cam.cam) { "rostelecom: нет cam" }
                val json = JSONObject(Http.get("https://lk-b2b.camera.rt.ru/api/v1/vc/cameras/$token.json"))
                val url = json.optJSONObject("camera")?.optString("hls_url")?.takeIf { it.isNotBlank() }
                    ?: json.optString("hls_url").takeIf { it.isNotBlank() }
                    ?: error("rostelecom: нет hls_url")
                Stream(url, emptyMap())
            }
            "page" -> {
                // Универсально: первая ссылка на .m3u8 в HTML страницы (например, камеры Кировска на 123streaming.ru).
                val page = requireNotNull(cam.page) { "page: нет page" }
                val html = Http.get(page, headers = mapOf("User-Agent" to BROWSER_UA), readTimeoutMs = 12_000)
                val url = ANY_M3U8.find(html)?.value?.replace("\\/", "/")?.replace("&amp;", "&") ?: error("page: m3u8 не найден")
                val headers = HashMap<String, String>()
                cam.referer?.let { headers["Referer"] = it }
                Stream(url, headers)
            }
            "ipcamlive" -> {
                // ipcamlive.com: по alias плеера сервис отдаёт сервер и streamid (см. ipcamliveStream).
                val alias = requireNotNull(cam.cam) { "ipcamlive: нет cam (alias)" }
                ipcamliveStream(JSONObject(Http.get("https://g1.ipcamlive.com/player/getcamerastreamstate.php?alias=$alias", headers = mapOf("User-Agent" to BROWSER_UA))))
            }
            "moszoo" -> {
                // Трансляции Московского зоопарка (zoo.mos.ru): на странице вольера виджеты
                // <div class="widget-player-hls" data-id="panda4" data-link="…live…" data-record-video="…повтор…">.
                // Живой эфир идёт в часы работы зоопарка, в остальное время сервер отдаёт 404 — тогда берём повтор дневного эфира.
                val page = requireNotNull(cam.page) { "moszoo: нет page" }
                val html = Http.get(page, headers = mapOf("User-Agent" to BROWSER_UA), readTimeoutMs = 12_000)
                val widgets = MOSZOO_WIDGET.findAll(html).map { m ->
                    MOSZOO_ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
                }.toList()
                val w = (if (cam.cam != null) widgets.firstOrNull { it["id"] == cam.cam } else widgets.firstOrNull())
                    ?: error("moszoo: виджет ${cam.cam ?: ""} не найден")
                val live = w["link"]?.takeIf { it.isNotBlank() }
                val replay = w["record-video"]?.takeIf { it.isNotBlank() }
                val headers = mapOf("User-Agent" to BROWSER_UA, "Referer" to "https://zoo.mos.ru/")
                val url = live?.takeIf { l ->
                    try { Http.get(l, headers, connectTimeoutMs = 6_000, readTimeoutMs = 8_000).contains("#EXTM3U") } catch (e: Exception) { false }
                } ?: replay ?: live ?: error("moszoo: у виджета нет ссылок")
                Stream(url, headers)
            }
            "earthcam" -> {
                val url = requireNotNull(cam.url) { "earthcam: нет url" }
                Stream(url, mapOf("Referer" to (cam.referer ?: "https://www.earthcam.com/"), "User-Agent" to BROWSER_UA))
            }
            "ivideon" -> {
                val server = requireNotNull(cam.server) { "ivideon: нет server" }
                val id = requireNotNull(cam.cam) { "ivideon: нет cam" }
                Stream(
                    "https://openapi-alpha.ivideon.com/cameras/$server:$id/live_stream" +
                        "?op=GET&access_token=public&q=2&video_codecs=h264&format=hls",
                    emptyMap(),
                )
            }
            else -> {
                val url = requireNotNull(cam.url) { "hls: нет url" }
                val headers = HashMap<String, String>()
                cam.referer?.let { headers["Referer"] = it }
                Stream(url, headers)
            }
        }
    }
}
