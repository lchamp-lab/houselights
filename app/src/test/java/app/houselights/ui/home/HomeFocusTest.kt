package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.core.SectionType
import app.houselights.ui.design.FocusKey
import app.houselights.ui.testing.film
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home finds the row of the card focus left by what the row is, not where it was. */
class HomeFocusTest {
    @Test
    fun aCardIsFoundInItsRowWhereverTheRowIsNow() {
        val continuing = HomeSection(SectionType.CONTINUE, listOf(film("1")), landscape = true)
        val latest = HomeSection(SectionType.LATEST, listOf(film("1"), film("2")), landscape = false, libraryId = "movies")
        val key = cardKey(latest, film("2"))
        assertEquals(1, sectionIndex(listOf(continuing, latest), key))
        // A row gone in front of it: the same row is found where it is now.
        assertEquals(0, sectionIndex(listOf(latest), key))
        assertNull(sectionIndex(listOf(continuing), key))
    }

    @Test
    fun theSameTitleInTwoRowsIsTwoCards() {
        val continuing = HomeSection(SectionType.CONTINUE, listOf(film("1")), landscape = true)
        val latest = HomeSection(SectionType.LATEST, listOf(film("1")), landscape = false, libraryId = "movies")
        val rows = listOf(continuing, latest)
        assertEquals(0, sectionIndex(rows, cardKey(continuing, film("1"))))
        assertEquals(1, sectionIndex(rows, cardKey(latest, film("1"))))
    }

    @Test
    fun homesDoorsAreInNoRow() {
        val rows = listOf(HomeSection(SectionType.CONTINUE, listOf(film("1")), landscape = true))
        assertNull(sectionIndex(rows, CASE))
        assertNull(sectionIndex(rows, FocusKey.action("door:settings")))
    }
}
