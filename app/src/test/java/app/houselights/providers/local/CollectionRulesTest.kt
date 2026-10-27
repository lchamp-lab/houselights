package app.houselights.providers.local

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

    /**
     * A saga shows without the words that only mark it as one: TMDB's French « - Saga », and its English « Collection »
     * when TMDB knows the saga; a set of the user's own keeps the word as its NFO writes it.
     */
    @Test
    fun aSagaShowsWithoutTheWordsThatMarkItOne() {
        assertEquals("Top Gun", CollectionRules.shownName("Top Gun - Saga", known = true))
        assertEquals("Mission : Impossible", CollectionRules.shownName("Mission : Impossible – Saga", known = true))
        assertEquals("Dune", CollectionRules.shownName("Dune Collection", known = true))
        assertEquals("John Wick", CollectionRules.shownName("John Wick Collection", known = true))
        assertEquals("Alien", CollectionRules.shownName("Alien - Collection", known = true))
        assertEquals("The Criterion Collection", CollectionRules.shownName("The Criterion Collection", known = false))
        assertEquals("Marvel", CollectionRules.shownName("Marvel", known = false))
        // Only a word of its own at the name's end: inside a name, or glued to it, it stays.
        assertEquals("La Collection Pixar", CollectionRules.shownName("La Collection Pixar", known = true))
        assertEquals("MyCollection", CollectionRules.shownName("MyCollection", known = true))
        // A name that is nothing but the word keeps it.
        assertEquals("Collection", CollectionRules.shownName("Collection", known = true))
        assertEquals("Saga", CollectionRules.shownName("Saga", known = true))
    }

    @Test
    fun onlyReleasedFilmsCount() {
        val dates = listOf("1986-05-16", "2022-05-24", "", null, "2031-01-01")
        assertEquals(2, CollectionRules.released(dates, "2026-10-14"))
    }
}
