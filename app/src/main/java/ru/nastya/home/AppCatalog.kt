package ru.nastya.home

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources

data class AppShortcut(val id: String, val label: String, val icon: Drawable?)

object AppCatalog {
    const val REFRESH = "__refresh__"

    private val movieKnown = listOf(
        "org.smarttube.stable" to "SmartTube",
        Launcher.YOUTUBE_TV to "YouTube",
        Launcher.KINOPOISK to "Кинопоиск",
        Launcher.LAMPA to "LAMPA",
    )

    private val systemKnown = listOf(
        "ru.vk.store.tv" to "RuStore",
        Launcher.TV_SETTINGS to "Настройки",
        "org.videolan.vlc" to "VLC",
    )

    /** Приложения для кино на вкладке «Кино»: только установленные и включённые. */
    fun movieApps(ctx: Context): List<AppShortcut> = installed(ctx, movieKnown)

    /** Приложения вкладки «Приложения»: установленные и включённые, плюс кнопка обновления подборки. */
    fun systemApps(ctx: Context): List<AppShortcut> =
        installed(ctx, systemKnown) + AppShortcut(REFRESH, ctx.getString(R.string.refresh), AppCompatResources.getDrawable(ctx, R.drawable.ic_refresh))

    private fun installed(ctx: Context, known: List<Pair<String, String>>): List<AppShortcut> {
        val pm = ctx.packageManager
        return known.mapNotNull { (pkg, label) ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (!info.enabled) null else AppShortcut(pkg, label, pm.getApplicationIcon(info))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}
