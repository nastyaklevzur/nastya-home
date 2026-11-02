package ru.nastya.home

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object MoviesRepository {
    private const val TAG = "NastyaHome"
    const val ASSET_BASE = "file:///android_asset/"

    /** Откуда брать movies.json; постеры с относительными путями берутся с того же адреса. */
    val REMOTE_BASES = listOf(
        "https://raw.githubusercontent.com/nastyaklevzur/nastya-home/main/",
        "https://cdn.jsdelivr.net/gh/nastyaklevzur/nastya-home@main/",
    )

    private const val PREFS = "movies"
    private const val KEY_BASE = "base"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val CACHE_FILE = "movies.json"

    fun loadLocal(ctx: Context): MoviesData = loadCached(ctx) ?: loadBundled(ctx)

    fun loadBundled(ctx: Context): MoviesData {
        val text = ctx.assets.open("movies.json").bufferedReader().use { it.readText() }
        return MoviesParser.parse(text, ASSET_BASE, "bundled")
    }

    fun loadCached(ctx: Context): MoviesData? {
        val file = File(ctx.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        val base = prefs(ctx).getString(KEY_BASE, REMOTE_BASES[0]) ?: REMOTE_BASES[0]
        return try {
            MoviesParser.parse(file.readText(), base, "cache")
        } catch (e: Exception) {
            Log.w(TAG, "cache is broken, ignoring", e)
            null
        }
    }

    fun lastFetchAt(ctx: Context): Long = prefs(ctx).getLong(KEY_FETCHED_AT, 0L)

    /** Блокирующая загрузка из сети: пробует адреса по очереди. Вызывать не на главном потоке. */
    fun fetchRemote(ctx: Context): MoviesData? {
        for (base in REMOTE_BASES) {
            try {
                val text = httpGet(base + "movies.json")
                val data = MoviesParser.parse(text, base, base)
                File(ctx.filesDir, CACHE_FILE).writeText(text)
                prefs(ctx).edit()
                    .putString(KEY_BASE, base)
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "movies.json loaded from $base (${data.movies.size} movies, updated=${data.updated})")
                return data
            } catch (e: Exception) {
                Log.w(TAG, "failed to load from $base: $e")
            }
        }
        return null
    }

    private fun httpGet(url: String): String {
        var current = URL(url)
        for (hop in 0 until 5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "NastyaHome/1.0 (Android TV)")
            conn.setRequestProperty("Cache-Control", "no-cache")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    current = URL(current, location)
                    continue
                }
                if (code != 200) throw IOException("HTTP $code for $current")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects for $url")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
