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

    class Response(val code: Int, val body: String)

    /**
     * Запрос с любым методом и телом; в отличие от [get] не бросает исключение на кодах не 200
     * (нужно для GitHub API: 404, 409 и 422 — штатные ответы). Редиректы не следуем.
     */
    fun send(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 20_000,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", UA)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
