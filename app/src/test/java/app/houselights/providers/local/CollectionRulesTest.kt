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

    /** A Japanese or Russian NFO set takes TMDB's collection when TMDB gives it the same name, as the key does. */
    @Test
    fun namesThatLeaveNoWordMatchWhole() {
        assertTrue(CollectionRules.sameName("攻殻機動隊", "攻殻機動隊"))
        assertTrue(CollectionRules.sameName("Звёздные  войны ", "звёздные войны"))
        assertFalse(CollectionRules.sameName("攻殻機動隊", "アキラ"))
        assertFalse("one with words, one without", CollectionRules.sameName("Akira", "アキラ"))
    }

    /** Names that leave no word once split on Latin letters and stripped of generic ones: each saga keeps its own. */
    @Test
    fun sagasWhoseNamesLeaveNoWordStayApart() {
        val names = listOf("攻殻機動隊", "新世紀エヴァンゲリオン", "Звёздные войны", "Брат", "Collection", "Les Films")
        assertEquals(names.size, names.map { CollectionRules.key(null, it) }.toSet().size)
        assertEquals("name:攻殻機動隊", CollectionRules.key(null, "攻殻機動隊"))
        assertEquals(CollectionRules.key(null, "Звёздные  войны "), CollectionRules.key(null, "звёздные войны"))
        // Names with words keep their key: sagas already in the library are found again.
        assertEquals("name:evil dead", CollectionRules.key(null, "Evil Dead Collection"))
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
