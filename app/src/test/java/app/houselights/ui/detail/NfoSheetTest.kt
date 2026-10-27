package app.houselights.ui.detail

import android.view.KeyEvent
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An NFO in a reading sheet over its title: how it reads after the facts, its views at the end of the title's line,
 * its details as definitions, focused to be read; its XML a view away; a failure in their place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class NfoSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val film = MediaItem("c1", MediaKind.MOVIE, "Colony", year = 2026)
    private val info = NfoInfo(
        kind = NfoKind.MOVIE,
        title = "Colony",
        year = 2026,
        runtimeMin = 122,
        genres = listOf("Horror", "Action"),
        directors = listOf("Yeon Sang-ho"),
        actors = listOf(NfoActor("Gianna Jun", null, null, 0), NfoActor("Koo Kyo-hwan", null, null, 1)),
        tmdbId = 1_234,
    )
    private val lines = listOf("<movie>", "  <title>Colony</title>", "</movie>")

    /** A text's first baseline on the screen. */
    private fun SemanticsNodeInteraction.baseline() = getUnclippedBoundsInRoot().top.value + getAlignmentLinePosition(FirstBaseline).value

    private fun open(document: LoadState<NfoContent?>) = compose.setTvContent {
        NfoSheet(film, NfoState("Colony", document), onRetry = {}, onDismiss = {})
    }

    @Test
    fun itsDetailsAreDefinitionsFocusedToBeRead() {
        open(LoadState.Ready(NfoContent("/films/Colony.nfo", info, lines)))
        compose.onNodeWithText("Full description").assertIsDisplayed()
        compose.onNodeWithText("Yeon Sang-ho").assertIsDisplayed()
        compose.onNodeWithText("Gianna Jun, Koo Kyo-hwan").assertIsDisplayed()
        // The last ones perhaps a press further down.
        compose.onNodeWithText("TMDB 1234").assertExists()
        compose.onNodeWithText("Colony.nfo").assertExists()
        compose.onNode(isFocused() and hasAnyDescendant(hasText("Yeon Sang-ho"))).assertExists()
        // A label beside its value, on one baseline.
        val label = compose.onNodeWithText("Directors")
        val value = compose.onNodeWithText("Yeon Sang-ho")
        assertTrue("beside it", label.getUnclippedBoundsInRoot().right <= value.getUnclippedBoundsInRoot().left)
        assertEquals("on its baseline", label.baseline(), value.baseline(), PX)
    }

    @Test
    fun itsViewsEndTheTitlesLine() {
        open(LoadState.Ready(NfoContent("/films/Colony.nfo", info, lines)))
        val title = compose.onNodeWithText("NFO file").getUnclippedBoundsInRoot()
        val xml = compose.onNodeWithText("XML").getUnclippedBoundsInRoot()
        // Its word ends at the sheet's edge, its plate hanging past it.
        assertEquals("at the sheet's end", SCREEN - HlSpace.safeH.value + HlControl.tabPaddingH.value, xml.right.value, 1f)
        assertTrue("on the title's line: $xml, $title", xml.top < title.bottom && xml.bottom > title.top)
    }

    @Test
    fun itsXmlIsAViewAway() {
        open(LoadState.Ready(NfoContent("/films/Colony.nfo", info, lines)))
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("  <title>Colony</title>").assertIsDisplayed()
        compose.onAllNodesWithText("Yeon Sang-ho").assertCountEquals(0)
    }

    @Test
    fun aFailureTakesTheirPlace() {
        open(LoadState.Failed(UiError.OFFLINE))
        compose.onAllNodesWithText("XML").assertCountEquals(0)
        compose.onNodeWithText("Unreadable").assertIsDisplayed()
    }

    private companion object {
        const val SCREEN = 960f

        /** Bounds are whole test pixels. */
        const val PX = 1f
    }
}
