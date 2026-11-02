package ru.nastya.home

/**
 * Сборка ряда «топ-10» из пула и состояния (PRD §6). Чистая функция без Android — её проверяют юнит-тесты.
 *
 * Правила:
 *  1. Закреплённые (до 3, в порядке закрепления) всегда первые.
 *  2. «Начала смотреть» остаются в ряду. Карточки прежнего ряда сохраняют порядок (стабильность) — но только пока пул
 *     тот же: новый movies.json (другая дата updated) собирается заново по рангу, это решает MainActivity.
 *  3. Свободные места заполняются лучшими по рангу из пула; пока в ряду меньше [NEW_QUOTA] новинок,
 *     сначала берутся новинки.
 *  4. Удалённые (просмотрен / не буду смотреть) в ряд не попадают никогда.
 */
object RowBuilder {
    const val SIZE = 10
    const val NEW_QUOTA = 3
    const val MAX_PINNED = 3

    /** Закреплённые фильмы в порядке закрепления; если фильма уже нет в пуле — из снимка. */
    fun pinnedMovies(data: MoviesData, state: AppState): List<Movie> {
        val byKey = data.movies.associateBy { it.key }
        val removed = state.removedKeys
        return state.pinned.filter { it.key !in removed }.mapNotNull { e ->
            byKey[e.key] ?: byKey[legacyKeyOf(e)] ?: e.snap?.toMovie(e.key, data.base)
        }
    }

    fun build(data: MoviesData, state: AppState, previousKeys: List<String>, size: Int = SIZE, newQuota: Int = NEW_QUOTA): List<Movie> {
        val removed = state.removedKeys
        val available = data.movies.filter { it.key !in removed && it.legacyKey !in removed }
        val byKey = available.associateBy { it.key }
        val row = ArrayList<Movie>()
        val used = HashSet<String>()

        for (m in pinnedMovies(data, state)) if (used.add(m.key)) row.add(m)
        // «Начала смотреть» держится в ряду, пока не отметят просмотренным или не снимут метку.
        for (e in state.started) {
            val m = byKey[e.key] ?: continue
            if (row.size < size && used.add(m.key)) row.add(m)
        }
        val kept = ArrayList<Movie>()
        for (k in previousKeys) {
            val m = byKey[k] ?: continue
            if (row.size + kept.size >= size) break
            if (used.add(m.key)) kept.add(m)
        }
        // Квота новинок важнее стабильности: если прежние карточки не оставляют места трём новинкам,
        // уступают худшие по рангу из прежних (кроме закреплённых и «начала смотреть»).
        val started = state.started.mapTo(HashSet()) { it.key }
        val freshAvailable = available.count { it.isNew && it.key !in used } + kept.count { it.isNew } + row.count { it.isNew }
        var needNew = minOf(newQuota, freshAvailable) - row.count { it.isNew } - kept.count { it.isNew }
        val freeSlots = size - row.size - kept.size
        var mustDrop = needNew - freeSlots
        if (mustDrop > 0) {
            for (m in kept.sortedByDescending { it.rank }) {
                if (mustDrop <= 0) break
                if (m.isNew || m.key in started) continue
                kept.remove(m)
                used.remove(m.key)
                mustDrop--
            }
        }
        row.addAll(kept)
        fill(row, used, available, size, newQuota)
        return row.take(size)
    }

    /**
     * Замена одной карточки (SC-7): [index] — место, которое освободилось. [row] уже без удалённого фильма
     * (удалённый ключ должен быть в [state]), на его место вставляется следующий подходящий.
     */
    fun replaceAt(data: MoviesData, state: AppState, row: List<Movie>, index: Int, size: Int = SIZE, newQuota: Int = NEW_QUOTA): List<Movie> {
        val removed = state.removedKeys
        val kept = row.filter { it.key !in removed }
        val used = kept.mapTo(HashSet()) { it.key }
        val available = data.movies.filter { it.key !in removed && it.legacyKey !in removed }
        val next = pick(available, used, newQuota - kept.count { it.isNew }) ?: return kept
        val out = ArrayList(kept)
        out.add(index.coerceIn(0, out.size), next)
        return out.take(size)
    }

    /**
     * Открепление (SC-2): [state] уже без этого закрепа. Фильм возвращается на место по рангу среди незакреплённых
     * или уходит из ряда, если его ранг хуже, чем у остальных десяти.
     */
    fun unpin(data: MoviesData, state: AppState, row: List<Movie>, key: String, size: Int = SIZE, newQuota: Int = NEW_QUOTA): List<Movie> {
        val unpinned = row.firstOrNull { it.key == key }
        val rebuilt = build(data, state, row.filter { it.key != key }.map { it.key }, size, newQuota).toMutableList()
        if (unpinned != null && rebuilt.none { it.key == key }) return rebuilt
        val pinnedKeys = state.pinned.mapTo(HashSet()) { it.key }
        val fromPool = data.movies.firstOrNull { it.key == key } ?: return rebuilt
        if (rebuilt.none { it.key == key }) return rebuilt
        rebuilt.removeAll { it.key == key }
        var at = rebuilt.indexOfFirst { it.key !in pinnedKeys && it.rank > fromPool.rank }
        if (at < 0) at = rebuilt.size
        rebuilt.add(at, fromPool)
        return rebuilt.take(size)
    }

    private fun fill(row: MutableList<Movie>, used: MutableSet<String>, available: List<Movie>, size: Int, newQuota: Int) {
        while (row.size < size) {
            val next = pick(available, used, newQuota - row.count { it.isNew }) ?: break
            used.add(next.key)
            row.add(next)
        }
    }

    private fun pick(available: List<Movie>, used: Set<String>, needNew: Int): Movie? {
        val candidates = available.filter { it.key !in used }
        if (needNew > 0) candidates.firstOrNull { it.isNew }?.let { return it }
        return candidates.firstOrNull()
    }

    private fun legacyKeyOf(e: Entry): String = e.snap?.let { it.title.trim().lowercase() + "|" + (it.year ?: 0) } ?: e.key
}
