package ru.nastya.home

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log

object Launcher {
    private const val TAG = "NastyaHome"
    const val LAMPA = "top.rootu.lampa"
    const val KINOPOISK = "ru.kinopoisk.tv"
    const val YOUTUBE_TV = "com.google.android.youtube.tv"
    const val VLC = "org.videolan.vlc"
    const val TV_SETTINGS = "com.android.tv.settings"

    /** Открыть bylampa.online в LAMPA (Uncensored-каталог). */
    fun launchLampaUncensored(ctx: Context): Boolean {
        if (!isInstalled(ctx, LAMPA)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://bylampa.online/"))
            .setClassName(LAMPA, "$LAMPA.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Lampa UC launch failed", e)
            false
        }
    }

    private fun lampaSearchIntent(query: String): Intent = Intent(Intent.ACTION_SEARCH)
        .setClassName(LAMPA, "$LAMPA.MainActivity")
        .putExtra(SearchManager.QUERY, query)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canSearchInLampa(ctx: Context): Boolean =
        ctx.packageManager.resolveActivity(lampaSearchIntent("test"), 0) != null

    /** Открыть поиск фильма в LAMPA. true, если получилось. */
    fun searchInLampa(ctx: Context, query: String): Boolean {
        if (!canSearchInLampa(ctx)) return false
        return try {
            ctx.startActivity(lampaSearchIntent(query))
            true
        } catch (e: Exception) {
            Log.w(TAG, "LAMPA search failed", e)
            false
        }
    }

    /**
     * Открыть карточку фильма в LAMPA. Основной путь — ссылка на TMDB (LAMPA ловит `themoviedb.org/movie/<id>` и сразу
     * открывает карточку с источниками). Запасной — поиск по названию: на холодном старте LAMPA 1.13.5 он теряет запрос
     * и показывает пустое «Введите текст», поэтому используется только когда tmdb_id нет.
     */
    fun openMovieInLampa(ctx: Context, m: Movie): Boolean {
        val id = m.tmdbId
        if (id != null && isInstalled(ctx, LAMPA)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/movie/$id"))
                .setClassName(LAMPA, "$LAMPA.MainActivity")
                .putExtra("source", "tmdb")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "LAMPA open card $id failed", e)
            }
        }
        return searchInLampa(ctx, m.searchQuery)
    }

    /** Открыть фильм в приложении Кинопоиска; без id или без резолва — просто запустить приложение. */
    fun openMovieInKinopoisk(ctx: Context, m: Movie): Boolean {
        val id = m.kinopoiskId
        if (id != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.kinopoisk.ru/film/$id/"))
                .setPackage(KINOPOISK)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                try {
                    ctx.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Kinopoisk open $id failed", e)
                }
            }
        }
        return launchPackage(ctx, KINOPOISK)
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: Exception) {
        false
    }

    /** Запасной плеер для камер: отдать поток в VLC. */
    fun openInVlc(ctx: Context, url: String, title: String): Boolean {
        if (!isInstalled(ctx, VLC)) return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setPackage(VLC)
            .setDataAndType(Uri.parse(url), "video/*")
            .putExtra("title", title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "VLC failed", e)
            false
        }
    }

    fun launchPackage(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: (if (pkg == TV_SETTINGS) Intent(Settings.ACTION_SETTINGS) else null)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "launch $pkg failed", e)
            false
        }
    }
}
