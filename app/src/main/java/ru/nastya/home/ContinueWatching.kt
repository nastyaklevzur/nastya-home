package ru.nastya.home

import java.text.SimpleDateFormat
import java.util.Locale

/** Ряд «Продолжить просмотр»: фильмы, просмотр которых начат через наш лаунчер (запись `started` в state.json). */
object ContinueWatching {
    data class Item(val movie: Movie, val via: String?, val at: String, val seq: Int)

    /** Новые сверху; убранные (removed) пропускаем; фильм — из пула по ключу или из снимка, иначе записи нет. */
    fun build(state: AppState, pool: List<Movie>, base: String): List<Item> {
        val removed = state.removedKeys
        val byKey = pool.associateBy { it.key }
        return state.started
            .filter { it.key !in removed }
            .sortedWith(compareByDescending<Entry> { it.seq }.thenByDescending { it.at })
            .mapNotNull { e ->
                val m = byKey[e.key] ?: e.snap?.toMovie(e.key, base) ?: return@mapNotNull null
                Item(m, e.via, e.at, e.seq)
            }
    }

    /** «в LAMPA · 3 ноя» / «в Кинопоиске · 3 ноя»; без даты — только «в LAMPA». */
    fun caption(item: Item, locale: Locale): String {
        val where = if (item.via == "kinopoisk") "в Кинопоиске" else "в LAMPA"
        val date = shortDate(item.at, locale)
        return if (date == null) where else "$where · $date"
    }

    private fun shortDate(at: String, locale: Locale): String? {
        if (at.length < 10) return null
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val d = parser.parse(at.substring(0, 10)) ?: return null
            SimpleDateFormat("d MMM", locale).format(d).replace(".", "")
        } catch (e: Exception) {
            null
        }
    }
}
