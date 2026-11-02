package ru.nastya.home

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Диалоги, общие для главного экрана, «Избранного» и «О фильме». */
object MovieDialogs {
    /** «Понравился «…»?» после «Просмотрен». Назад закрывает диалог без действия. */
    fun askLiked(ctx: Context, m: Movie, onAnswer: (liked: Boolean?) -> Unit) {
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.liked_title, m.title))
            .setItems(
                arrayOf(ctx.getString(R.string.liked_yes), ctx.getString(R.string.liked_no), ctx.getString(R.string.liked_skip))
            ) { _, which -> onAnswer(when (which) { 0 -> true; 1 -> false; else -> null }) }
            .show()
    }

    fun title(m: Movie) = m.title + (m.year?.let { " ($it)" } ?: "")
}
