package ru.nastya.home

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources

data class AppShortcut(val id: String, val label: String, val icon: Drawable?)

object AppCatalog {
    const val REFRESH = "__refresh__"

    private val known = listOf(
        Launcher.LAMPA to "LAMPA",
        "org.smarttube.stable" to "SmartTube",
        "ru.kinopoisk.tv" to "Кинопоиск",
        "ru.vk.store.tv" to "RuStore",
        "org.videolan.vlc" to "VLC",
        Launcher.TV_SETTINGS to "Настройки",
    )

    /** Только установленные и включённые приложения, плюс кнопка обновления подборки. */
    fun shortcuts(ctx: Context): List<AppShortcut> {
        val pm = ctx.packageManager
        val apps = known.mapNotNull { (pkg, label) ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                if (!info.enabled) null else AppShortcut(pkg, label, pm.getApplicationIcon(info))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        return apps + AppShortcut(REFRESH, ctx.getString(R.string.refresh), AppCompatResources.getDrawable(ctx, R.drawable.ic_refresh))
    }
}
