package ru.nastya.home

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Award(val award: String, val result: String, val count: Int?, val label: String?)

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
    // ----- v2 -----
    /** Относительный (как в JSON) путь постера; нужен, чтобы хранить снимок в state.json. */
    val posterPath: String? = null,
    val imdbId: String? = null,
    val imdbRating: Double? = null,
    val kpRating: Double? = null,
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val country: String? = null,
    val director: String? = null,
    val whyYou: String? = null,
    val critics: String? = null,
    val awards: List<Award> = emptyList(),
    val awardBadge: String? = null,
    val isNew: Boolean = false,
    val rank: Int = Int.MAX_VALUE,
) {
    /** Имя файла постера: по нему ищем вшитую копию, если сеть недоступна. */
    val posterFileName: String?
        get() = (posterUrl ?: posterPath)?.substringAfterLast('/')?.substringBefore('?')?.takeIf { it.isNotEmpty() }

    val searchQuery: String get() = search ?: title

    val ratingText: String? get() = rating?.let { "★ " + String.format(Locale.US, "%.1f", it) }

    val metaLine: String
        get() = listOfNotNull(year?.toString(), ratingText).joinToString("   ·   ")

    /**
     * Ключ фильма в state.json: tmdb_id, а для файлов v1 без него «название|год».
     * Совпадает с ключом старых меток (WatchStatus.key), поэтому миграция не теряет данные.
     */
    val key: String get() = tmdbId?.toString() ?: legacyKey

    val legacyKey: String get() = title.trim().lowercase() + "|" + (year ?: 0)
}

data class MoviesData(
    val title: String,
    val updated: String?,
    /** Весь пул (v1: ровно то, что в файле; v2: 30–40 фильмов, уже по рангу). Ряд из 10 строит [RowBuilder]. */
    val movies: List<Movie>,
    val source: String,
    /** Адрес, с которого разрешаются относительные пути постеров. */
    val base: String = "",
    val version: Int = 1,
)

object MoviesParser {
    private const val DEFAULT_TITLE = "Топ-10 рекомендаций кино"

    fun parse(json: String, baseUrl: String, source: String): MoviesData {
        val trimmed = json.trim()
        var title = DEFAULT_TITLE
        var updated: String? = null
        var version = 1
        val arr: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            title = str(obj, "title") ?: DEFAULT_TITLE
            updated = str(obj, "updated")
            version = obj.optInt("version", 1)
            obj.optJSONArray("movies") ?: JSONArray()
        }
        val list = ArrayList<Movie>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(parseMovie(o, baseUrl, i) ?: continue)
        }
        require(list.isNotEmpty()) { "movies.json: пустой список" }
        // Порядок в файле = ранг; явное поле rank, если есть, сильнее.
        val ordered = list.sortedBy { it.rank }
        return MoviesData(title, updated, ordered, source, baseUrl, version)
    }

    private fun parseMovie(o: JSONObject, baseUrl: String, index: Int): Movie? {
        val t = str(o, "title") ?: return null
        val posterPath = str(o, "poster_url") ?: str(o, "poster")
        val resolved = RemoteStore.resolve(baseUrl, posterPath)
        return Movie(
            title = t,
            originalTitle = str(o, "original_title"),
            year = o.optInt("year", 0).takeIf { it > 0 },
            rating = dbl(o, "rating"),
            note = str(o, "note") ?: "",
            posterUrl = resolved,
            kinopoiskId = o.optLong("kinopoisk_id", 0).takeIf { it > 0 },
            tmdbId = o.optLong("tmdb_id", 0).takeIf { it > 0 },
            search = str(o, "search"),
            posterPath = posterPath,
            imdbId = str(o, "imdb_id"),
            imdbRating = dbl(o, "imdb_rating"),
            kpRating = dbl(o, "kp_rating"),
            genres = strings(o, "genres"),
            runtime = o.optInt("runtime", 0).takeIf { it > 0 },
            country = str(o, "country"),
            director = str(o, "director"),
            whyYou = str(o, "why_you"),
            critics = str(o, "critics"),
            awards = awards(o),
            awardBadge = str(o, "award_badge"),
            isNew = o.optBoolean("is_new", false),
            rank = if (o.has("rank") && !o.isNull("rank")) o.optInt("rank", index) else index,
        )
    }

    private fun awards(o: JSONObject): List<Award> {
        val a = o.optJSONArray("awards") ?: return emptyList()
        val out = ArrayList<Award>()
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            val name = str(x, "award") ?: continue
            out.add(Award(name, str(x, "result") ?: "", x.optInt("count", 0).takeIf { it > 0 }, str(x, "label")))
        }
        return out
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }

    private fun dbl(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.optDouble(key).takeIf { !it.isNaN() } else null

    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
