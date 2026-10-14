package app.houselights.providers.local

import app.houselights.core.NfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRulesTest {
    @Test
    fun tmdbIdGroupsWhateverTheName() {
        assertEquals("tmdb:87359", CollectionRules.key(87359, "Mission: Impossible - Saga"))
        assertEquals(CollectionRules.key(null, "Évil Dead Collection"), CollectionRules.key(null, "evil dead"))
    }

    @Test
    fun namesFromMediaManagersAndTmdbMatch() {
        assertTrue(CollectionRules.sameName("Top Gun Collection", "Top Gun - Saga"))
        assertTrue(CollectionRules.sameName("La saga Evil Dead", "Evil Dead Collection"))
        assertFalse("a set of the user's own", CollectionRules.sameName("Marvel", "Spider-Man (Avengers) - Saga"))
        assertFalse(CollectionRules.sameName("Collection", "Saga"))
    }

    @Test
    fun onlyReleasedFilmsCount() {
        val dates = listOf("1986-05-16", "2022-05-24", "", null, "2031-01-01")
        assertEquals(2, CollectionRules.released(dates, "2026-10-14"))
    }

    @Test
    fun nfoGivesTheCollectionAndItsId() {
        val kodi = NfoParser.parse("<movie><title>Top Gun</title><set tmdbcolid=\"531241\"><name>Top Gun Collection</name></set></movie>")!!
        assertEquals("Top Gun Collection", kodi.collection)
        assertEquals(531241, kodi.collectionTmdbId)
        val tmm = NfoParser.parse("<movie><title>Top Gun</title><set>Top Gun</set><tmdbCollectionId>531241</tmdbCollectionId></movie>")!!
        assertEquals("Top Gun", tmm.collection)
        assertEquals(531241, tmm.collectionTmdbId)
    }
}
