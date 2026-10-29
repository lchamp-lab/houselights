package app.houselights.ui.edit

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MatchDoubt
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Modifier names Identifier by what it does for the title: one without a match, to identify or left as it is, is to be
 * found on TMDB; « Associer à un autre titre TMDB » stays for a title matched, where it corrects. A saga's has its
 * poster and its backdrop alone, its years as its facts (#48).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class EditMenuTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val compose = createComposeRule()

    private fun show(item: MediaItem, onIdentify: (() -> Unit)? = {}) {
        compose.setTvContent { EditMenu(item, onDismiss = {}, onArtwork = {}, onIdentify = onIdentify) }
        compose.waitForIdle()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun says(find: Boolean) {
        compose.onNodeWithText(FIND).apply { if (find) assertExists() else assertDoesNotExist() }
        compose.onNodeWithText(MATCH_ANOTHER).apply { if (find) assertDoesNotExist() else assertExists() }
    }

    @Test
    fun aTitleToIdentifyIsToBeFound() {
        show(film("m1", "Vacances").copy(matchDoubt = MatchDoubt.NOT_FOUND))
        says(find = true)
    }

    @Test
    fun aTitleLeftAsItIsIsToBeFound() {
        show(film("m1", "Vacances").copy(keptUnidentified = true))
        says(find = true)
    }

    @Test
    fun aMatchedTitleIsMatchedWithAnother() {
        show(film("m1", "Heat"))
        says(find = false)
    }

    /** A saga's Modifier: its poster and its backdrop, no logo, TMDB giving a collection none, no Identifier; its years. */
    @Test
    fun aSagasModifierHasItsPosterAndItsBackdropAloneItsYearsSaid() {
        show(MediaItem("saga:dune", MediaKind.COLLECTION, "Dune", year = 2021, endYear = 2024), onIdentify = null)
        assertEquals(listOf(true, true, false), listOf("Poster", "Backdrop", "Logo").map(::shown))
        assertEquals(false to false, shown(FIND) to shown(MATCH_ANOTHER))
        compose.onNodeWithText("2021 – 2024", useUnmergedTree = true).assertExists()
    }

    private companion object {
        const val FIND = "Find this title on TMDB"
        const val MATCH_ANOTHER = "Match with another TMDB title"
    }
}
