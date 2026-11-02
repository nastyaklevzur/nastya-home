package ru.nastya.home

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Файлы данных (movies.json, cams.json) живут в корне репозитория и скачиваются с GitHub.
 * Удачная загрузка кэшируется в filesDir; если сети нет — кэш, если и его нет — копия из assets.
 */
object RemoteStore {
    private const val TAG = "NastyaHome"
    const val ASSET_BASE = "file:///android_asset/"

    /** Откуда брать файлы; относительные пути (постеры, превью) берутся с того же адреса. */
    val REMOTE_BASES = listOf(
        "https://raw.githubusercontent.com/nastyaklevzur/nastya-home/main/",
        "https://cdn.jsdelivr.net/gh/nastyaklevzur/nastya-home@main/",
    )

    data class Loaded(val text: String, val base: String, val source: String)

    fun bundled(ctx: Context, name: String): Loaded =
        Loaded(ctx.assets.open(name).bufferedReader().use { it.readText() }, ASSET_BASE, "bundled")

    fun cached(ctx: Context, name: String): Loaded? {
        val file = File(ctx.filesDir, name)
        if (!file.exists()) return null
        val base = prefs(ctx).getString("base:$name", REMOTE_BASES[0]) ?: REMOTE_BASES[0]
        return Loaded(file.readText(), base, "cache")
    }

    fun lastFetchAt(ctx: Context, name: String): Long = prefs(ctx).getLong("fetched:$name", 0L)

    /** Блокирующая загрузка: пробует адреса по очереди, проверяет текст через [validate]. */
    fun fetch(ctx: Context, name: String, validate: (String, String) -> Unit): Loaded? {
        for (base in REMOTE_BASES) {
            try {
                val text = Http.get(base + name)
                validate(text, base)
                File(ctx.filesDir, name).writeText(text)
                prefs(ctx).edit()
                    .putString("base:$name", base)
                    .putLong("fetched:$name", System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "$name loaded from $base")
                return Loaded(text, base, base)
            } catch (e: Exception) {
                Log.w(TAG, "failed to load $name from $base: $e")
            }
        }
        return null
    }

    fun resolve(base: String, path: String?): String? = when {
        path == null -> null
        path.contains("://") -> path
        else -> base + path.trimStart('/')
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)
}
