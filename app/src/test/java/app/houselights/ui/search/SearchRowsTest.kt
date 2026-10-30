package app.houselights.ui.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.houselights.core.MediaItem
import app.houselights.core.SearchResults
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.LoadState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A row of results at its start stays there as the query changes: the result found first shows first, never off the
 * screen to the left of the one that came first before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SearchRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val found = mutableStateOf<Found?>(null)
    private val lit = mutableStateOf<MediaItem?>(null)

    private fun showResults() = compose.setTvContent {
        Results(found, lit, onItemFocus = {}, rememberFocusMemory(), onOpenItem = {}, onOpenPerson = {}, onRetry = {})
    }

    private fun find(query: String, results: SearchResults) {
        found.value = Found(query, LoadState.Ready(results))
        compose.waitForIdle()
    }

    private fun card(name: String) = compose.onNode(hasText(name) and hasClickAction())

    /** « ALIENS » then « ALIEN »: Alien, found exactly, comes before Aliens, and shows where Aliens did. */
    @Test
    fun theTitleFoundFirstShowsFirstAsTheQueryWidens() {
        showResults()
        find("ALIENS", SearchResults(titles = listOf(film("Aliens"), film("Aliens vs. Predator"))))
        val start = card("Aliens").getUnclippedBoundsInRoot().left

        find("ALIEN", SearchResults(titles = listOf(film("Alien"), film("Aliens"), film("Alien 3"))))
        card("Alien").assertLeftPositionInRootIsEqualTo(start)
    }
}
