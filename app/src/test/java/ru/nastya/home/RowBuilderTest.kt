package ru.nastya.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowBuilderTest {
    private fun movie(id: Long, isNew: Boolean = false) = Movie(
        title = "Фильм $id", originalTitle = null, year = 2000 + (id % 20).toInt(), rating = 8.0, note = "n",
        posterUrl = null, kinopoiskId = null, tmdbId = id, search = null, posterPath = "posters/$id.jpg",
        isNew = isNew, rank = id.toInt(),
    )

    private fun data(vararg movies: Movie) = MoviesData("t", null, movies.sortedBy { it.rank }, "test", "base/")
    private fun pool(n: Int, newIds: Set<Long> = emptySet()) = data(*(1L..n).map { movie(it, it in newIds) }.toTypedArray())
    private fun keys(row: List<Movie>) = row.map { it.key }
    private fun entry(m: Movie, reason: String? = null) = Entry(m.key, "t", Snap.of(m), reason)

    @Test fun coldStartTakesTopTenByRank() {
        val row = RowBuilder.build(pool(30), AppState(), emptyList())
        assertEquals((1L..10L).map { it.toString() }, keys(row))
    }

    @Test fun threeNewMoviesAreAlwaysInRow() {
        // Новинки стоят в пуле на местах 12, 15, 20 — по рангу в десятку не попали бы.
        val row = RowBuilder.build(pool(30, setOf(12, 15, 20)), AppState(), emptyList())
        assertEquals(10, row.size)
        assertEquals(3, row.count { it.isNew })
        assertEquals(listOf("12", "15", "20"), keys(row.filter { it.isNew }))
    }

    @Test fun pinnedGoFirstInPinOrder() {
        val d = pool(30)
        val s = AppState(pinned = listOf(entry(d.movies[9]), entry(d.movies[4])))
        val row = RowBuilder.build(d, s, emptyList())
        assertEquals(listOf("10", "5"), keys(row).take(2))
        assertEquals(10, row.size)
        assertEquals(10, row.map { it.key }.toSet().size)
    }

    @Test fun pinnedMovieMissingFromPoolComesFromSnapshot() {
        val gone = movie(99)
        val s = AppState(pinned = listOf(entry(gone)))
        val row = RowBuilder.build(pool(30), s, emptyList())
        assertEquals("99", row.first().key)
        assertEquals("Фильм 99", row.first().title)
        assertEquals("base/posters/99.jpg", row.first().posterUrl)
    }

    @Test fun removedNeverReturnAndPinnedRemovedIsDropped() {
        val d = pool(30)
        val s = AppState(pinned = listOf(entry(d.movies[0])), removed = listOf(entry(d.movies[0], "watched"), entry(d.movies[1], "skip")))
        val row = RowBuilder.build(d, s, emptyList())
        assertFalse(row.any { it.key == "1" || it.key == "2" })
        assertEquals(10, row.size)
    }

    @Test fun previousRowOrderIsKept() {
        val d = pool(30)
        val previous = listOf("7", "3", "5", "1", "2", "4", "6", "8", "9", "10")
        assertEquals(previous, keys(RowBuilder.build(d, AppState(), previous)))
    }

    @Test fun replaceAtPutsNextMovieInTheSamePlace() {
        val d = pool(30)
        val row = RowBuilder.build(d, AppState(), emptyList())
        val removed = row[3]
        val s = AppState(removed = listOf(entry(removed, "skip")))
        val next = RowBuilder.replaceAt(d, s, row, 3)
        assertEquals(10, next.size)
        assertEquals("11", next[3].key)
        assertEquals(keys(row).filterIndexed { i, _ -> i != 3 }, keys(next).filterIndexed { i, _ -> i != 3 })
    }

    @Test fun replacingANewMovieBringsAnotherNewOne() {
        val d = pool(30, setOf(12, 15, 20, 25))
        val row = RowBuilder.build(d, AppState(), emptyList())
        val removed = row.first { it.isNew }
        val next = RowBuilder.replaceAt(d, AppState(removed = listOf(entry(removed, "watched"))), row, row.indexOf(removed))
        assertEquals(3, next.count { it.isNew })
    }

    @Test fun smallPoolGivesShortRowWithoutDuplicates() {
        val row = RowBuilder.build(pool(6), AppState(), emptyList())
        assertEquals(6, row.size)
        val s = AppState(removed = row.take(2).map { entry(it, "skip") })
        val shorter = RowBuilder.replaceAt(pool(6), s, row, 0)
        assertEquals(4, shorter.size)
    }

    @Test fun v1MoviesWithoutTmdbIdUseTitleYearKeyAndRemovedByLegacyKey() {
        val a = movie(1).copy(tmdbId = null)
        val b = movie(2).copy(tmdbId = null)
        val d = data(a, b)
        assertEquals("фильм 1|2001", a.key)
        val s = AppState(removed = listOf(Entry(a.key, "t", null, "skip")))
        assertEquals(listOf(b.key), keys(RowBuilder.build(d, s, emptyList())))
    }
}
