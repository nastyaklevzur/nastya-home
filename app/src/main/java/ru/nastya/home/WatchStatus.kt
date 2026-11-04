package ru.nastya.home

import android.content.Context

/**
 * Метки «Начат просмотр» / «Просмотрено». С v2 живут в [StateStore] (и уезжают в репозиторий),
 * этот объект оставлен как тонкая обёртка, чтобы карточки и меню не менялись.
 * «Просмотрено» теперь значит «удалено из подборки» (см. [StateStore.remove]).
 */
object WatchStatus {
    enum class Status(val label: String?) {
        NONE(null),
        STARTED("Начат просмотр"),
        WATCHED("Просмотрено"),
    }

    fun get(@Suppress("UNUSED_PARAMETER") ctx: Context, m: Movie): Status = when {
        StateStore.removedEntry(m)?.reason == "watched" -> Status.WATCHED
        StateStore.isStarted(m) -> Status.STARTED
        else -> Status.NONE
    }

    fun set(@Suppress("UNUSED_PARAMETER") ctx: Context, m: Movie, status: Status) {
        when (status) {
            Status.NONE -> StateStore.setStarted(m, false)
            Status.STARTED -> StateStore.setStarted(m, true)
            Status.WATCHED -> StateStore.remove(m, "watched")
        }
    }

    /** Фильм открыли в LAMPA / Кинопоиске ([via]): если метки нет — ставим «Начат просмотр». */
    fun markOpened(ctx: Context, m: Movie, via: String = "lampa"): Boolean {
        if (get(ctx, m) != Status.NONE) return false
        StateStore.setStarted(m, true, via)
        return true
    }
}
