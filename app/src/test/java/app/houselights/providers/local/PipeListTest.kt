package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Test

/** One rule matches an entry of an "A|B|C" list, in Room's queries as in the ones the provider builds. */
class PipeListTest {
    @Test
    fun roomsQueriesMatchAnEntryAsTheOthersDo() {
        assertEquals(PipeList.matches("genres", ":genre"), PipeList.GENRES_MATCH)
        assertEquals(PipeList.matches("badges", ":token"), PipeList.BADGES_MATCH)
    }

    @Test
    fun aListIsStoredAndReadBack() {
        assertEquals("Action|Drame", PipeList.toText(listOf("Action", "Drame")))
        assertEquals(listOf("Action", "Drame"), PipeList.fromText("|Action||Drame|"))
    }
}
