package ru.nastya.home

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Диалоги, общие для главного экрана, «Избранного» и «О фильме».
 *
 * Системный AlertDialog со списком на этом проекторе не реагирует на стрелки пульта (фокус не попадает в список),
 * поэтому меню собрано из обычных фокусируемых TextView: первый пункт получает фокус сразу, стрелки и OK работают как везде.
 */
object MovieDialogs {
    class Item(val label: String, val action: () -> Unit)

    fun menu(ctx: Context, title: String, items: List<Item>) {
        val d = Dialog(ctx, R.style.Theme_NastyaHome_Dialog)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(14), dp(8), dp(10))
        }
        column.addView(TextView(ctx).apply {
            text = title
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.getColor(R.color.cream))
            setPadding(dp(16), 0, dp(16), dp(10))
        })
        var first: TextView? = null
        for (item in items) {
            val v = TextView(ctx).apply {
                text = item.label
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setBackgroundResource(R.drawable.menu_item_bg)
                setTextColor(ctx.getColorStateList(R.color.chip_text))
                setPadding(dp(16), dp(11), dp(16), dp(11))
                setOnClickListener { d.dismiss(); item.action() }
            }
            column.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            if (first == null) first = v
        }
        d.setContentView(ScrollView(ctx).apply { addView(column) })
        d.window?.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.setOnShowListener { first?.requestFocus() }
        d.show()
    }

    /** «Понравился «…»?» после «Просмотрен». Назад закрывает диалог без действия. */
    fun askLiked(ctx: Context, m: Movie, onAnswer: (liked: Boolean?) -> Unit) {
        menu(
            ctx, ctx.getString(R.string.liked_title, m.title),
            listOf(
                Item(ctx.getString(R.string.liked_yes)) { onAnswer(true) },
                Item(ctx.getString(R.string.liked_no)) { onAnswer(false) },
                Item(ctx.getString(R.string.liked_skip)) { onAnswer(null) },
            ),
        )
    }

    fun title(m: Movie) = m.title + (m.year?.let { " ($it)" } ?: "")
}
