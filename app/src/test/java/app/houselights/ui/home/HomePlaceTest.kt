package app.houselights.ui.home

import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.LibrarySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** While focus is on the tabs, home names the place a tab leads to. */
class HomePlaceTest {
    private val films = Library("movies", LibraryKind.MOVIES, null)
    private val shows = Library("shows", LibraryKind.SHOWS, "Séries")
    private val summary = LibrarySummary(total = 9, posters = emptyList(), uhd = 7)

    @Test
    fun offTheTrackNothingIsNamed() {
        assertNull(placeOnTabs(null, listOf(films), emptyMap()))
    }

    @Test
    fun theFirstTabIsHome() {
        assertEquals(Place.Home, placeOnTabs(0, listOf(films), emptyMap()))
    }

    @Test
    fun theOtherTabsAreTheLibrariesInOrderWithWhatTheyHold() {
        val summaries = mapOf(films.id to summary)
        assertEquals(Place.Shelf(films, summary), placeOnTabs(1, listOf(films, shows), summaries))
        // A summary not there yet: the library is named all the same.
        assertEquals(Place.Shelf(shows, null), placeOnTabs(2, listOf(films, shows), summaries))
        assertNull(placeOnTabs(3, listOf(films, shows), summaries))
    }
}
