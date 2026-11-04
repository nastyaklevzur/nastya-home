package ru.nastya.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.request.CachePolicy

/**
 * Заставка «Тёплый рассвет»: пятна света разгораются, появляется приветствие, пульсирует сердечко,
 * затем всё плавно растворяется и проявляется главный экран. Только View-анимации — никакого видео.
 */
class Splash(private val root: View, private val content: View, private val onDone: () -> Unit) {

    private val set = AnimatorSet()
    private var pulse: ObjectAnimator? = null
    private var finished = false
    private val dismissRunnable = Runnable { dismiss() }

    fun start() {
        root.visibility = View.VISIBLE
        root.alpha = 1f
        content.alpha = 0f

        val glow1 = root.findViewById<View>(R.id.glow1)
        val glow2 = root.findViewById<View>(R.id.glow2)
        val glow3 = root.findViewById<View>(R.id.glow3)
        val line1 = root.findViewById<View>(R.id.splashLine1)
        val line2 = root.findViewById<View>(R.id.splashLine2)
        val heart = root.findViewById<View>(R.id.splashHeart)

        for (v in listOf(glow1, glow2, glow3, line1, line2, heart)) v.alpha = 0f
        val d = root.resources.displayMetrics.density

        val anims = ArrayList<Animator>()
        anims += glow(glow1, 0L, 0.75f)
        anims += glow(glow2, 350L, 0.7f)
        anims += glow(glow3, 700L, 0.55f)
        anims += appear(line1, 600L, 28f * d)
        anims += appear(line2, 1250L, 24f * d)
        anims += appear(heart, 1750L, 0f)
        set.playTogether(anims)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (finished) return
                pulse = ObjectAnimator.ofPropertyValuesHolder(
                    heart,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f),
                ).apply {
                    duration = 520
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
        })
        set.start()
        root.postDelayed(dismissRunnable, TOTAL_MS)
    }

    private fun glow(v: View, delay: Long, alpha: Float): Animator {
        v.scaleX = 0.45f
        v.scaleY = 0.45f
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, alpha),
                ObjectAnimator.ofFloat(v, View.SCALE_X, 0.45f, 1f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.45f, 1f),
            )
            startDelay = delay
            duration = 1500
            interpolator = DecelerateInterpolator(1.6f)
        }
    }

    private fun appear(v: View, delay: Long, fromY: Float): Animator {
        v.translationY = fromY
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, fromY, 0f),
            )
            startDelay = delay
            duration = 750
            interpolator = DecelerateInterpolator()
        }
    }

    /** Первая строка: «<Имя>,» или «Привет,» без имени. */
    fun setName(name: String?) {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        root.findViewById<TextView>(R.id.splashLine1).text =
            if (n != null) "$n," else root.context.getString(R.string.splash_hello)
    }

    /** Фон: свежий снимок Земли, Солнца или неба. Если сети нет — остаётся тёплый градиент. */
    fun setBackdrop(b: SplashBackdrops.Backdrop) {
        val image = root.findViewById<ImageView>(R.id.splashImage)
        val caption = root.findViewById<TextView>(R.id.splashCaption)
        caption.text = b.caption
        image.load(b.url()) {
            diskCacheKey(SplashBackdrops.cacheKey(b))
            memoryCachePolicy(CachePolicy.DISABLED)
            crossfade(900)
            listener(onSuccess = { _, _ ->
                caption.animate().alpha(0.8f).setDuration(700).start()
            })
        }
    }

    /** Любая кнопка или таймер: растворяем заставку и проявляем главный экран. */
    fun dismiss() {
        if (finished) return
        finished = true
        root.removeCallbacks(dismissRunnable)
        set.cancel()
        pulse?.cancel()
        content.animate().alpha(1f).setDuration(650).setStartDelay(150).start()
        root.animate().alpha(0f).setDuration(650).setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                root.visibility = View.GONE
                (root.parent as? ViewGroup)?.removeView(root)
                onDone()
            }.start()
    }

    val isShowing: Boolean get() = !finished

    companion object {
        const val TOTAL_MS = 5_000L
    }
}
