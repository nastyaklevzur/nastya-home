package ru.nastya.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateAndParserTest {
    private val v1 = """{"title":"Топ-10","updated":"2026-11-02","movies":[
        {"title":"Форрест Гамп","original_title":"Forrest Gump","year":1994,"rating":8.8,"note":"Тёплая","tmdb_id":13,"poster":"posters/forrest_gump.jpg"},
        {"title":"Амели","year":2001,"rating":8.3,"note":"Сказка","poster":"posters/amelie.jpg"}]}"""

    @Test fun v1FileParsesAsPoolInFileOrder() {
        val d = MoviesParser.parse(v1, "https://x/", "test")
        assertEquals(1, d.version)
        assertEquals(listOf("Форрест Гамп", "Амели"), d.movies.map { it.title })
        assertEquals("13", d.movies[0].key)
        assertEquals("амели|2001", d.movies[1].key)
        assertEquals("https://x/posters/forrest_gump.jpg", d.movies[0].posterUrl)
        assertTrue(d.movies.none { it.isNew })
    }

    @Test fun poolIsNoLongerCutToTen() {
        val items = (1..35).joinToString(",") { """{"title":"F$it","year":2020,"rating":8,"note":"n","tmdb_id":$it,"poster":"p/$it.jpg","rank":$it}""" }
        val d = MoviesParser.parse("""{"version":2,"movies":[$items]}""", "b/", "t")
        assertEquals(35, d.movies.size)
        assertEquals(2, d.version)
    }

    @Test fun v2FieldsAndRankOrdering() {
        val json = """{"version":2,"movies":[
          {"title":"B","year":2026,"rating":7.4,"note":"n","tmdb_id":2,"poster":"p/b.jpg","rank":2,"is_new":true,"imdb_rating":7.4,"kp_rating":7.1,
           "genres":["драма","триллер"],"award_badge":"🏆 Оскар","awards":[{"award":"oscar","result":"won","count":6,"label":"6 «Оскаров»"}],"why_you":"потому что"},
          {"title":"A","year":1999,"rating":8.9,"note":"n","tmdb_id":1,"poster":"p/a.jpg","rank":1}]}"""
        val d = MoviesParser.parse(json, "b/", "t")
        assertEquals(listOf("A", "B"), d.movies.map { it.title })
        val b = d.movies[1]
        assertTrue(b.isNew)
        assertEquals(listOf("драма", "триллер"), b.genres)
        assertEquals("🏆 Оскар", b.awardBadge)
        assertEquals(6, b.awards.single().count)
        assertEquals("потому что", b.whyYou)
        assertNull(d.movies[0].awardBadge)
    }

    @Test fun stateRoundTrips() {
        val m = MoviesParser.parse(v1, "https://x/", "t").movies
        val s = AppState(
            seq = 5, updated = "2026-11-03T20:00:00+02:00",
            pinned = listOf(Entry("13", "t1", Snap.of(m[0]), seq = 1)),
            favorites = listOf(Entry(m[1].key, "t2", Snap.of(m[1]), seq = 2)),
            removed = listOf(Entry("13", "t3", Snap.of(m[0]), "watched", true, 3), Entry("77", "t4", null, "skip", null, 4)),
            started = listOf(Entry("13", "t5")),
        )
        val back = StateJson.parse(StateJson.serialize(s))
        assertEquals(s, back)
    }

    @Test fun tmdbIdIsWrittenAsNumberAndLegacyKeyAsString() {
        val json = StateJson.serialize(AppState(favorites = listOf(Entry("13", "t"), Entry("амели|2001", "t"))))
        assertTrue(json, json.contains("\"id\": 13"))
        assertTrue(json, json.contains("\"id\": \"амели|2001\""))
    }

    @Test fun likedNullIsExplicitInRemoved() {
        val json = StateJson.serialize(AppState(removed = listOf(Entry("1", "t", null, "skip", null))))
        assertTrue(json, json.contains("\"liked\": null"))
        assertNotNull(StateJson.parse(json).removed.single().reason)
        assertNull(StateJson.parse(json).removed.single().liked)
    }

    @Test fun brokenEntriesAreSkipped() {
        val s = StateJson.parse("""{"seq":3,"pinned":[{"at":"x"},{"id":5,"at":"y"}],"removed":"oops"}""")
        assertEquals(listOf("5"), s.pinned.map { it.key })
        assertTrue(s.removed.isEmpty())
    }
}
