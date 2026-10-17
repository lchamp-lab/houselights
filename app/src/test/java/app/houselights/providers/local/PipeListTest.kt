package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** One rule matches an entry of an "A|B|C" list, in Room's queries as in the ones the provider builds. */
class PipeListTest {
    @Test
    fun roomsQueriesMatchAnEntryAsTheOthersDo() {
        assertEquals(PipeList.matchesAnyCase("genres", ":genre"), PipeList.GENRES_MATCH)
        assertEquals(PipeList.matches("badges", ":token"), PipeList.BADGES_MATCH)
    }

    /** Spellings join as SQLite folded them, whatever its lower() makes of a letter: here, "É" kept apart. */
    @Test
    fun genreSpellingsJoinAsTheFilterFoldsThem() {
        val lists = listOf(
            GenreList("drame|Épouvante", "drame|Épouvante"),
            GenreList("Drame|épouvante", "drame|épouvante"),
            GenreList("Drame||Action", "drame||action"),
            GenreList("action", "action"),
        )
        assertEquals(listOf("Action", "Drame", "Épouvante", "épouvante"), PipeList.distinctGenres(lists))
    }

    /** A genre most titles write in small letters still sorts in its place, not after "Western". */
    @Test
    fun genresSortAsAReaderLooksForThem() {
        val lists = listOf(GenreList("Western|drame", "western|drame"), GenreList("drame|Action", "drame|action"))
        assertEquals(listOf("Action", "drame", "Western"), PipeList.distinctGenres(lists))
    }

    /** On a tie, a word's own spelling before capitals throughout and small letters. */
    @Test
    fun aTieGoesToTheWordsOwnSpelling() {
        assertEquals("Drame", PipeList.spellingOf(listOf("DRAME", "drame", "Drame")))
        assertEquals("Drame", PipeList.spellingOf(listOf("drame", "Drame", "drame", "Drame", "DRAME")))
        assertEquals("drame", PipeList.spellingOf(listOf("drame", "drame", "Drame")))
    }

    @Test
    fun aListIsStoredAndReadBack() {
        assertEquals("Action|Drame", PipeList.toText(listOf("Action", "Drame")))
        assertEquals(listOf("Action", "Drame"), PipeList.fromText("|Action||Drame|"))
    }
}
