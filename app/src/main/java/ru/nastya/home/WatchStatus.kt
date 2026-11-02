package ru.nastya.home

import android.content.Context

/** Метки «Начала смотреть» / «Просмотрено», хранятся локально по ключу название+год. */
object WatchStatus {
    enum class Status(val label: String?) {
        NONE(null),
        STARTED("Начала смотреть"),
        WATCHED("Просмотрено"),
    }

    private const val PREFS = "watch"

    fun key(m: Movie): String = m.title.trim().lowercase() + "|" + (m.year ?: 0)

    fun get(ctx: Context, m: Movie): Status = when (prefs(ctx).getString(key(m), null)) {
        "started" -> Status.STARTED
        "watched" -> Status.WATCHED
        else -> Status.NONE
    }

    fun set(ctx: Context, m: Movie, status: Status) {
        val e = prefs(ctx).edit()
        when (status) {
            Status.NONE -> e.remove(key(m))
            Status.STARTED -> e.putString(key(m), "started")
            Status.WATCHED -> e.putString(key(m), "watched")
        }
        e.apply()
    }

    /** Фильм открыли в LAMPA: если метки нет — ставим «Начала смотреть». */
    fun markOpened(ctx: Context, m: Movie): Boolean {
        if (get(ctx, m) != Status.NONE) return false
        set(ctx, m, Status.STARTED)
        return true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
