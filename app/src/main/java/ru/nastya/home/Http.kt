package ru.nastya.home

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Простой блокирующий HTTP GET с ручными редиректами. Вызывать не на главном потоке. */
object Http {
    const val UA = "NastyaHome/1.1 (Android TV; github.com/nastyaklevzur/nastya-home)"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 15_000,
    ): String {
        var current = URL(url)
        for (hop in 0 until 5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Cache-Control", "no-cache")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    current = URL(current, location)
                    continue
                }
                if (code != 200) throw IOException("HTTP $code for $current")
                val charset = conn.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "UTF-8"
                return conn.inputStream.bufferedReader(charset(charset)).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects for $url")
    }
}
