package ru.nastya.home

import android.content.Context
import android.widget.ImageView
import coil.load

/** Загрузка постеров с запасным вариантом: вшитая копия или аккуратная заглушка, никаких «битых» картинок. */
object Posters {
    @Volatile
    private var bundled: Set<String>? = null

    private fun bundled(ctx: Context): Set<String> =
        bundled ?: (ctx.assets.list("posters")?.toSet() ?: emptySet()).also { bundled = it }

    fun load(view: ImageView, url: String?, fileName: String?) {
        val fallback = fileName?.takeIf { it in bundled(view.context) }
            ?.let { MoviesRepository.ASSET_BASE + "posters/" + it }
        val primary = url ?: fallback
        view.tag = primary
        if (primary == null) {
            view.setImageResource(R.drawable.poster_placeholder)
            return
        }
        view.load(primary) {
            crossfade(200)
            placeholder(R.drawable.poster_placeholder)
            error(R.drawable.poster_placeholder)
            if (fallback != null && fallback != primary) {
                listener(onError = { _, _ ->
                    if (view.tag == primary) {
                        view.load(fallback) {
                            placeholder(R.drawable.poster_placeholder)
                            error(R.drawable.poster_placeholder)
                        }
                    }
                })
            }
        }
    }
}
