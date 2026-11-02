package ru.nastya.home

import android.content.Context
import android.widget.ImageView
import coil.load

/** Загрузка картинок с запасным вариантом: вшитая копия или аккуратная заглушка, никаких «битых» картинок. */
object Posters {
    private val bundled = HashMap<String, Set<String>>()

    @Synchronized
    private fun bundled(ctx: Context, dir: String): Set<String> =
        bundled.getOrPut(dir) { ctx.assets.list(dir)?.toSet() ?: emptySet() }

    fun load(view: ImageView, url: String?, fileName: String?, assetDir: String = "posters", placeholder: Int = R.drawable.poster_placeholder) {
        val fallback = fileName?.takeIf { it in bundled(view.context, assetDir) }
            ?.let { RemoteStore.ASSET_BASE + assetDir + "/" + it }
        val primary = url ?: fallback
        view.tag = primary
        if (primary == null) {
            view.setImageResource(placeholder)
            return
        }
        view.load(primary) {
            crossfade(200)
            placeholder(placeholder)
            error(placeholder)
            if (fallback != null && fallback != primary) {
                listener(onError = { _, _ ->
                    if (view.tag == primary) {
                        view.load(fallback) {
                            placeholder(placeholder)
                            error(placeholder)
                        }
                    }
                })
            }
        }
    }

    /** Для камер: сначала живой кадр (если есть), при ошибке — статичная картинка из репозитория/assets. */
    fun loadCam(view: ImageView, cam: Cam) {
        val live = cam.previewUrl
        val still = cam.previewFallbackUrl
        if (live != null && still != null && live != still) {
            view.tag = live
            view.load(live) {
                crossfade(200)
                placeholder(R.drawable.cam_placeholder)
                error(R.drawable.cam_placeholder)
                listener(onError = { _, _ ->
                    if (view.tag == live) load(view, still, cam.previewFileName, "cams", R.drawable.cam_placeholder)
                })
            }
        } else {
            load(view, live ?: still, cam.previewFileName, "cams", R.drawable.cam_placeholder)
        }
    }
}
