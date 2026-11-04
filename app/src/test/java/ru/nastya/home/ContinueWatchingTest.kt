package ru.nastya.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ContinueWatchingTest {
    private fun movie(id: Long, title: String = "Фильм $id") = Movie(
        title = title, originalTitle = null, year = 2000, rating = null, note = "",
        posterUrl = null, kinopoiskId = null, tmdbId = id, search = null,
    )

    private fun snap(title: String) = Snap(title, null, 2001, null, "", null, null)

    @Test
    fun ordersNewestFirstAndSkipsRemoved() {
        val state = AppState(
            started = listOf(
                Entry("1", "2026-11-01T10:00:00+03:00", seq = 1, via = "lampa"),
                Entry("2", "2026-11-03T10:00:00+03:00", seq = 3, via = "kinopoisk"),
                Entry("3", "2026-11-02T10:00:00+03:00", seq = 2),
            ),
            removed = listOf(Entry("3", "2026-11-04T10:00:00+03:00", reason = "watched")),
        )
        val items = ContinueWatching.build(state, listOf(movie(1), movie(2), movie(3)), "")
        assertEquals(listOf("2", "1"), items.map { it.movie.key })
        assertEquals("kinopoisk", items[0].via)
    }

    @Test
    fun usesSnapWhenNotInPoolAndSkipsWhenNoSnap() {
        val state = AppState(
            started = listOf(
                Entry("10", "2026-11-01T10:00:00+03:00", snap = snap("Из снимка"), seq = 1),
                Entry("11", "2026-11-01T11:00:00+03:00", seq = 2),
            ),
        )
        val items = ContinueWatching.build(state, emptyList(), "https://x/")
        assertEquals(1, items.size)
        assertEquals("Из снимка", items[0].movie.title)
        assertEquals(10L, items[0].movie.tmdbId)
    }

    @Test
    fun tiesBrokenByDate() {
        val state = AppState(
            started = listOf(
                Entry("1", "2026-11-01T10:00:00+03:00", seq = 0),
                Entry("2", "2026-11-02T10:00:00+03:00", seq = 0),
            ),
        )
        val items = ContinueWatching.build(state, listOf(movie(1), movie(2)), "")
        assertEquals(listOf("2", "1"), items.map { it.movie.key })
    }

    @Test
    fun captions() {
        val ru = Locale("ru")
        val lampa = ContinueWatching.Item(movie(1), "lampa", "2026-11-03T10:00:00+03:00", 1)
        val kp = ContinueWatching.Item(movie(1), "kinopoisk", "2026-11-03T10:00:00+03:00", 1)
        val noDate = ContinueWatching.Item(movie(1), null, "", 1)
        assertTrue(ContinueWatching.caption(lampa, ru).startsWith("в LAMPA · 3 "))
        assertTrue(ContinueWatching.caption(kp, ru).startsWith("в Кинопоиске · 3 "))
        assertEquals("в LAMPA", ContinueWatching.caption(noDate, ru))
    }
}
