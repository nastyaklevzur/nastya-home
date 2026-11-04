package ru.nastya.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CamsParserTest {
    private fun parse(json: String) = CamsParser.parse(json, "https://example.com/cams.json", "test")

    @Test fun homeFlagIsParsedAndDefaultsToFalse() {
        val d = parse("""{"cams":[{"id":"a","title":"A","home":true},{"id":"b","title":"B"}]}""")
        assertTrue(d.cams[0].home)
        assertFalse(d.cams[1].home)
    }

    @Test fun homeCamsKeepsOnlyVisibleCamsWithHomeFlag() {
        val d = parse(
            """{"cams":[{"id":"a","title":"A","home":true},{"id":"b","title":"B"},
            {"id":"c","title":"C","home":true,"hidden":true},{"id":"d","title":"D","home":true}]}"""
        )
        assertEquals(listOf("a", "d"), d.homeCams.map { it.id })
    }

    @Test fun homeCamsFallsBackToAllVisibleWhenNoCamHasHomeFlag() {
        val d = parse("""{"cams":[{"id":"a","title":"A"},{"id":"b","title":"B","hidden":true},{"id":"c","title":"C"}]}""")
        assertEquals(listOf("a", "c"), d.homeCams.map { it.id })
    }
}
