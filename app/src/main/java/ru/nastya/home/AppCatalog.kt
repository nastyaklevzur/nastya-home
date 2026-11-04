package ru.nastya.home

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources

data class AppShortcut(val id: String, val label: String, val icon: Drawable?)

object AppCatalog {
    const val REFRESH = "__refresh__"
    const val LAMPA_UC = "__lampa_uc__"

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
    fun movieApps(ctx: Context): List<AppShortcut> {
        val base = installed(ctx, movieKnown)
        // Добавить «Lampa UC» рядом с обычной LAMPA, если та установлена
        val lampaIdx = base.indexOfFirst { it.id == Launcher.LAMPA }
        if (lampaIdx < 0) return base
        val lampaIcon = base[lampaIdx].icon
        val ucEntry = AppShortcut(LAMPA_UC, "Lampa UC", lampaIcon)
        return base.toMutableList().also { it.add(lampaIdx + 1, ucEntry) }
    }

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
