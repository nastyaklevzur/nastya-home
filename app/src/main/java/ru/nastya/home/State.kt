package ru.nastya.home

import org.json.JSONArray
import org.json.JSONObject

/** Снимок карточки: экран избранного и закреплённые работают, даже когда фильм уже выбыл из пула. */
data class Snap(
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val rating: Double?,
    val note: String,
    val poster: String?,
    val search: String?,
) {
    fun toMovie(key: String, base: String): Movie = Movie(
        title = title,
        originalTitle = originalTitle,
        year = year,
        rating = rating,
        note = note,
        posterUrl = RemoteStore.resolve(base, poster),
        kinopoiskId = null,
        tmdbId = key.toLongOrNull(),
        search = search,
        posterPath = poster,
    )

    companion object {
        fun of(m: Movie) = Snap(m.title, m.originalTitle, m.year, m.rating, m.note.take(48), m.posterPath, m.search)
    }
}

/** Запись о фильме в одном из списков состояния. */
data class Entry(
    val key: String,
    val at: String,
    val snap: Snap? = null,
    /** Только для removed: "watched" | "skip". */
    val reason: String? = null,
    /** Только для removed: оценка после просмотра. */
    val liked: Boolean? = null,
    /** Номер действия, которым запись создана; Mac сравнивает по нему, а не по часам. */
    val seq: Int = 0,
)

data class AppState(
    val seq: Int = 0,
    val updated: String = "",
    val pinned: List<Entry> = emptyList(),
    val favorites: List<Entry> = emptyList(),
    val removed: List<Entry> = emptyList(),
    val started: List<Entry> = emptyList(),
    /** «Нравится», поставленное из меню (до или после просмотра) — сигнал вкуса для рек.системы. */
    val likes: List<Entry> = emptyList(),
) {
    val removedKeys: Set<String> get() = removed.mapTo(HashSet()) { it.key }
    val isEmpty: Boolean get() = seq == 0 && pinned.isEmpty() && favorites.isEmpty() && removed.isEmpty() && started.isEmpty() && likes.isEmpty()
}

object StateJson {
    const val VERSION = 1
    /** Записи removed старше года сворачиваются до {id, reason, liked}, чтобы файл не рос (PRD §9). */
    private const val COMPACT_AFTER_REMOVED = 500

    fun parse(text: String): AppState {
        val o = JSONObject(text)
        return AppState(
            seq = o.optInt("seq", 0),
            updated = o.optString("updated", ""),
            pinned = entries(o.optJSONArray("pinned")),
            favorites = entries(o.optJSONArray("favorites")),
            removed = entries(o.optJSONArray("removed")),
            started = entries(o.optJSONArray("started")),
            likes = entries(o.optJSONArray("likes")),
        )
    }

    fun serialize(s: AppState): String {
        val o = JSONObject()
        o.put("version", VERSION)
        o.put("updated", s.updated)
        o.put("seq", s.seq)
        o.put("pinned", array(s.pinned))
        o.put("favorites", array(s.favorites))
        o.put("removed", array(s.removed, compact = s.removed.size > COMPACT_AFTER_REMOVED))
        o.put("started", array(s.started))
        o.put("likes", array(s.likes))
        return o.toString(2) + "\n"
    }

    private fun array(list: List<Entry>, compact: Boolean = false): JSONArray {
        val a = JSONArray()
        list.forEachIndexed { i, e ->
            val o = JSONObject()
            // Числовые id (tmdb) пишем числом, как в PRD; ключи v1 «название|год» остаются строкой.
            o.put("id", e.key.toLongOrNull() ?: e.key)
            if (e.reason != null) o.put("reason", e.reason)
            if (e.reason != null) o.put("liked", e.liked ?: JSONObject.NULL)
            o.put("at", e.at)
            if (e.seq > 0) o.put("seq", e.seq)
            // В свёрнутом виде оставляем снимки только у свежих записей (последние 500).
            val keepSnap = !compact || i >= list.size - COMPACT_AFTER_REMOVED
            if (e.snap != null && keepSnap) o.put("snap", snap(e.snap))
            a.put(o)
        }
        return a
    }

    private fun snap(s: Snap): JSONObject = JSONObject().apply {
        put("title", s.title)
        s.originalTitle?.let { put("original_title", it) }
        s.year?.let { put("year", it) }
        s.rating?.let { put("rating", it) }
        put("note", s.note)
        s.poster?.let { put("poster", it) }
        s.search?.let { put("search", it) }
    }

    private fun entries(a: JSONArray?): List<Entry> {
        if (a == null) return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("id") ?: continue
            if (id == JSONObject.NULL) continue
            val key = if (id is Number) id.toLong().toString() else id.toString()
            out.add(
                Entry(
                    key = key,
                    at = o.optString("at", ""),
                    snap = o.optJSONObject("snap")?.let(::parseSnap),
                    reason = if (o.has("reason") && !o.isNull("reason")) o.optString("reason") else null,
                    liked = if (o.has("liked") && !o.isNull("liked")) o.optBoolean("liked") else null,
                    seq = o.optInt("seq", 0),
                )
            )
        }
        return out
    }

    private fun parseSnap(o: JSONObject): Snap? {
        val title = o.optString("title", "").takeIf { it.isNotBlank() } ?: return null
        fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).takeIf { it.isNotBlank() } else null
        return Snap(
            title = title,
            originalTitle = s("original_title"),
            year = o.optInt("year", 0).takeIf { it > 0 },
            rating = if (o.has("rating") && !o.isNull("rating")) o.optDouble("rating").takeIf { !it.isNaN() } else null,
            note = o.optString("note", ""),
            poster = s("poster"),
            search = s("search"),
        )
    }
}
