package ru.nastya.home

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Служебная проверка сети с самого проектора (curl на устройстве нет):
 * adb shell am start -n ru.nastya.home/.ProbeActivity --es urls "https://a|https://b"
 * Результат в logcat по тегу NastyaProbe.
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val urls = intent.getStringExtra("urls")?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val referer = intent.getStringExtra("referer")
        thread {
            for (u in urls) probe(u, referer)
            Log.i(TAG, "done ${urls.size}")
        }
        finish()
    }

    private fun probe(url: String, referer: String?) {
        val t0 = System.currentTimeMillis()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) NastyaHome/1.1")
            if (referer != null) conn.setRequestProperty("Referer", referer)
            val code = conn.responseCode
            val type = conn.contentType
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            var total = 0
            val buf = ByteArray(8192)
            var head = ""
            stream?.use { s ->
                while (total < 96 * 1024) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    if (head.isEmpty()) head = String(buf, 0, minOf(n, 80)).replace('\n', ' ')
                    total += n
                }
            }
            Log.i(TAG, "OK $url -> $code $type ${total}B ${System.currentTimeMillis() - t0}ms head=[$head]")
        } catch (e: Exception) {
            Log.w(TAG, "FAIL $url -> $e ${System.currentTimeMillis() - t0}ms")
        }
    }

    companion object { private const val TAG = "NastyaProbe" }
}
