package app.houselights.ui.home

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.providers.local.Folder
import app.houselights.providers.local.HeldRow
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import app.houselights.ui.settings.HeldTitles
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.FACT_GAP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Titles of a folder held while their files are missing, said at the end of home's head with See, never taking focus:
 * Up from the grid's first row reaches it, OK opens their sheet on Keep, its answers above the list, Back closing it
 * without an answer; answered, or the files back, the note goes and focus comes back to the case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomeHeldTest {
    @get:Rule
    val compose = createComposeRule()

    private val films = Folder(1, "films", SourceKind.MOVIES, Protocol.NFS, "nas", "nas/volume1/films", lastScanAt = 0)

    private fun held(n: Int) = HeldRow(
        id = n.toLong(), sourceId = films.id, kind = ItemKind.MOVIE, title = "Film $n", year = 2000 + n,
        filePath = "Film $n (${2000 + n})/Film $n (${2000 + n}).mkv", missingSince = 0, showTitle = null, seasonNumber = null,
        episodeNumber = null,
    )

    private val titles = HeldTitles(films, (1..12).map(::held))

    /** The answers given, in order. */
    private val answered = mutableListOf<String>()

    private var home by mutableStateOf(fullHome().copy(held = titles))

    private val actions = HomeActions(
        onOpenSearch = {},
        onKeepHeld = {
            answered += "keep ${it.ids.size}"
            home = home.copy(held = null)
        },
        onRemoveHeld = {
            answered += "remove ${it.ids.size}"
            home = home.copy(held = null)
        },
    )

    private fun open() {
        compose.setTvContent { HomeGrid(home, actions) }
        compose.waitForIdle()
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    private val note = "12 titles of “Films” not found on the NAS"

    /** Up to See from the grid's first row, then OK: the sheet. */
    private fun openTheSheet() {
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionUp)
        focusIsOn("Next up")
        compose.press(Key.DirectionUp)
        focusIsOn("See")
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText("12 titles not found").assertExists()
    }

    @Test
    fun homeSaysThemAtTheEndOfItsHeadWithoutTakingFocus() {
        open()
        compose.onNodeWithText(note, useUnmergedTree = true).assertExists()
        focusIsOn("Resume")
    }

    @Test
    fun withoutTitlesHeldHomeSaysNothing() {
        home = fullHome()
        open()
        compose.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("See").assertDoesNotExist()
    }

    /** Keep first and focused, then Remove saying nothing is deleted, above ten titles over their files and how many more. */
    @Test
    fun seeOpensTheirSheetOnKeepTheAnswersAboveTheList() {
        open()
        openTheSheet()
        compose.onNode(hasText("Keep") and isFocused()).assertExists()
        compose.onNodeWithText("Deletes nothing on the NAS, nor what you saw").assertExists()
        compose.onNodeWithText("Film 1${FACT_GAP}2001").assertExists()
        compose.onNodeWithText("Film 1 (2001)/Film 1 (2001).mkv").assertExists()
        compose.onNodeWithText("Film 10${FACT_GAP}2010").assertExists()
        compose.onNodeWithText("Film 11${FACT_GAP}2011").assertDoesNotExist()
        compose.onNodeWithText("And 2 more").assertExists()
        val remove = compose.onNodeWithText("Deletes nothing on the NAS, nor what you saw").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithText("Film 1${FACT_GAP}2001").fetchSemanticsNode().boundsInRoot
        assertTrue("the answers above the list", remove.bottom <= first.top)
    }

    /** Back closes the sheet without an answer: the note stays, focus on See. */
    @Test
    fun backClosesTheSheetWithoutAnAnswer() {
        open()
        openTheSheet()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("12 titles not found").assertDoesNotExist()
        assertEquals(emptyList<String>(), answered)
        compose.onNodeWithText(note, useUnmergedTree = true).assertExists()
        focusIsOn("See")
    }

    /** Keep: the note goes, and focus with it comes back to the case. */
    @Test
    fun keepAnswersAndFocusComesBackToTheCase() {
        open()
        openTheSheet()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("keep 12"), answered)
        compose.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
        focusIsOn("Resume")
    }

    /** Remove, the second answer, asks nothing more. */
    @Test
    fun removeIsOnePressAway() {
        open()
        openTheSheet()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("Remove") and isFocused()).assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("remove 12"), answered)
        compose.onNodeWithText(note, useUnmergedTree = true).assertDoesNotExist()
    }

    /** The files back while the sheet is open: it closes, and does not open again by itself. */
    @Test
    fun theFilesBackCloseTheSheet() {
        open()
        openTheSheet()
        home = home.copy(held = null)
        compose.waitForIdle()
        compose.onNodeWithText("12 titles not found").assertDoesNotExist()
        home = home.copy(held = titles)
        compose.waitForIdle()
        compose.onNodeWithText("12 titles not found").assertDoesNotExist()
        compose.onNodeWithText(note, useUnmergedTree = true).assertExists()
    }
}
