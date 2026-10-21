package app.houselights.ui.search

import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.SearchResults
import app.houselights.ui.testing.film
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Search lies in the light of the title or saga last in focus while the results hold it; a person lends none. */
class SearchLightTest {
    private val saga = MediaItem("saga", MediaKind.COLLECTION, "Spider-Man")
    private val found = SearchResults(
        titles = listOf(film("nwh"), film("bnd")),
        people = listOf(Person("Zendaya", null, null, "p1")),
        sagas = listOf(saga),
    )

    @Test
    fun aTitleOrASagaLendsItsLight() {
        assertEquals(film("bnd"), found.lit("bnd"))
        assertEquals(saga, found.lit("saga"))
    }

    @Test
    fun nothingFoundLendsNone() {
        assertNull(found.lit(null))
        assertNull("a person", found.lit("p1"))
        assertNull("a title the next query no longer finds", SearchResults(titles = listOf(film("bnd"))).lit("nwh"))
    }
}
