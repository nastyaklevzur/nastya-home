package ru.nastya.home

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

object Launcher {
    private const val TAG = "NastyaHome"
    const val LAMPA = "top.rootu.lampa"
    const val TV_SETTINGS = "com.android.tv.settings"

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
