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
        assertEquals(listOf("Drame", "Épouvante", "épouvante", "Action"), PipeList.distinctGenres(lists))
    }

    @Test
    fun aListIsStoredAndReadBack() {
        assertEquals("Action|Drame", PipeList.toText(listOf("Action", "Drame")))
        assertEquals(listOf("Action", "Drame"), PipeList.fromText("|Action||Drame|"))
    }
}
