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
