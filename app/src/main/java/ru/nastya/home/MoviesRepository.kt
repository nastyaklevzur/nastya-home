package ru.nastya.home

import android.content.Context
import android.util.Log

object MoviesRepository {
    private const val TAG = "NastyaHome"
    private const val FILE = "movies.json"
    const val ASSET_BASE = RemoteStore.ASSET_BASE

    fun loadLocal(ctx: Context): MoviesData = loadCached(ctx) ?: loadBundled(ctx)

    fun loadBundled(ctx: Context): MoviesData {
        val l = RemoteStore.bundled(ctx, FILE)
        return MoviesParser.parse(l.text, l.base, l.source)
    }

    fun loadCached(ctx: Context): MoviesData? {
        val l = RemoteStore.cached(ctx, FILE) ?: return null
        return try {
            MoviesParser.parse(l.text, l.base, l.source)
        } catch (e: Exception) {
            Log.w(TAG, "movies cache is broken, ignoring", e)
            null
        }
    }

    fun lastFetchAt(ctx: Context): Long = RemoteStore.lastFetchAt(ctx, FILE)

    /** Блокирующая загрузка из сети. Вызывать не на главном потоке. */
    fun fetchRemote(ctx: Context): MoviesData? {
        val l = RemoteStore.fetch(ctx, FILE) { text, base -> MoviesParser.parse(text, base, base) } ?: return null
        return MoviesParser.parse(l.text, l.base, l.source)
    }
}
