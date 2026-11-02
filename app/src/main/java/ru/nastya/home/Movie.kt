package ru.nastya.home

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Movie(
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val rating: Double?,
    val note: String,
    val posterUrl: String?,
    val kinopoiskId: Long?,
    val tmdbId: Long?,
    val search: String?,
) {
    /** Имя файла постера: по нему ищем вшитую копию, если сеть недоступна. */
    val posterFileName: String?
        get() = posterUrl?.substringAfterLast('/')?.substringBefore('?')?.takeIf { it.isNotEmpty() }

    val searchQuery: String get() = search ?: title

    val ratingText: String? get() = rating?.let { "★ " + String.format(Locale.US, "%.1f", it) }

    val metaLine: String
        get() = listOfNotNull(year?.toString(), ratingText).joinToString("   ·   ")
}

data class MoviesData(
    val title: String,
    val updated: String?,
    val movies: List<Movie>,
    val source: String,
)

object MoviesParser {
    private const val DEFAULT_TITLE = "Топ-10 рекомендаций кино"

    fun parse(json: String, baseUrl: String, source: String): MoviesData {
        val trimmed = json.trim()
        var title = DEFAULT_TITLE
        var updated: String? = null
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            title = str(obj, "title") ?: DEFAULT_TITLE
            updated = str(obj, "updated")
            obj.optJSONArray("movies") ?: JSONArray()
        }
        val list = ArrayList<Movie>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = str(o, "title") ?: continue
            val poster = str(o, "poster_url") ?: str(o, "poster")
            val resolved = when {
                poster == null -> null
                poster.contains("://") -> poster
                else -> baseUrl + poster.trimStart('/')
            }
            val rating = if (o.has("rating") && !o.isNull("rating")) o.optDouble("rating").takeIf { !it.isNaN() } else null
            list.add(
                Movie(
                    title = t,
                    originalTitle = str(o, "original_title"),
                    year = o.optInt("year", 0).takeIf { it > 0 },
                    rating = rating,
                    note = str(o, "note") ?: "",
                    posterUrl = resolved,
                    kinopoiskId = o.optLong("kinopoisk_id", 0).takeIf { it > 0 },
                    tmdbId = o.optLong("tmdb_id", 0).takeIf { it > 0 },
                    search = str(o, "search"),
                )
            )
        }
        require(list.isNotEmpty()) { "movies.json: пустой список" }
        return MoviesData(title, updated, list.take(10), source)
    }

    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
