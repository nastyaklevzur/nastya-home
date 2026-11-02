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

    /** Браузерный User-Agent: EarthCam и некоторые другие отдают 403 на «неизвестные» плееры. */
    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    private val SKYLINE_TOKEN = Regex("livee\\.m3u8\\?a=([A-Za-z0-9]+)")
    private val ANY_M3U8 = Regex("https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*")

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
