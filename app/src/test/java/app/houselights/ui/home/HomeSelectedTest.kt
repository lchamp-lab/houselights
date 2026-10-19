package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.SectionType
import app.houselights.ui.testing.film
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home always shows a title: the one last selected, else the first, where focus lands. */
class HomeSelectedTest {
    private val resume = HomeSection(SectionType.CONTINUE, listOf(film("heat"), film("alien")), landscape = true)
    private val latest = HomeSection(SectionType.LATEST, listOf(film("playtime")), landscape = false, libraryId = "movies")

    @Test
    fun theTitleLastSelectedStays() {
        assertEquals("playtime", selectedTitle(listOf(resume, latest), "playtime")?.id)
        assertEquals("alien", selectedTitle(listOf(resume, latest), "alien")?.id)
    }

    @Test
    fun beforeAnyOrOnceItHasLeftTheRowsTheFirstTitle() {
        assertEquals("heat", selectedTitle(listOf(resume, latest), null)?.id)
        // Finished and gone from "Reprendre": the first title takes its place.
        assertEquals("heat", selectedTitle(listOf(resume, latest), "gone")?.id)
    }

    @Test
    fun noRowsNoTitle() {
        assertNull(selectedTitle(emptyList(), "heat"))
    }
}
