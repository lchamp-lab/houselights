package app.houselights.ui.search

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.SearchResults
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A search the server or the library could not answer says so, with Retry, rather than that nothing matches. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SearchFailureTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private var retries = 0

    private fun results(found: Found) {
        val shown = mutableStateOf<Found?>(found)
        val lit = mutableStateOf<MediaItem?>(null)
        compose.setTvContent {
            Results(shown, lit, onItemFocus = {}, rememberFocusMemory(), onOpenItem = {}, onOpenPerson = {}, onRetry = { retries++ })
        }
    }

    @Test
    fun aFailedSearchSaysWhyAndOffersRetry() {
        results(Found("Dune", LoadState.Failed(UiError.OFFLINE)))
        compose.onNodeWithText(context.getString(R.string.search_none, "Dune")).assertDoesNotExist()
        compose.onNodeWithText(context.getString(UiError.OFFLINE.title)).assertExists()
        compose.onNode(hasText(context.getString(R.string.retry)) and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        assertEquals(1, retries)
    }

    @Test
    fun aSearchThatFoundNothingSaysSo() {
        results(Found("Dune", LoadState.Ready(SearchResults())))
        compose.onNodeWithText(context.getString(R.string.search_none, "Dune")).assertExists()
        compose.onNodeWithText(context.getString(R.string.retry)).assertDoesNotExist()
    }
}
