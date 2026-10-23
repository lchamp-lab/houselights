package app.houselights.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.HomeSection
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Focus: Back at home's start says once that a second Back quits, in the bar, then quits. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class QuitAgainTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun film(n: Int) = MediaItem(id = "film$n", kind = MediaKind.MOVIE, title = "Film $n", year = 2000 + n)

    private val home = HomeState(
        libraries = listOf(Library("movies", LibraryKind.MOVIES, null), Library("shows", LibraryKind.SHOWS, null)),
        content = HomeContent.Rows(
            listOf(
                HomeSection(SectionType.LATEST, (1..12).map(::film), landscape = false, libraryId = "movies"),
                HomeSection(SectionType.LATEST, (20..32).map(::film), landscape = false, libraryId = "shows"),
            ),
            firstScan = false,
        ),
    )

    private val line = "Press Back again to quit"

    private fun open() {
        compose.setTvContent { HomeBands(home) }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val quits get() = compose.activity.isFinishing

    @Test
    fun aFirstBackFromTheFirstRowSaysASecondQuits() {
        open()
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse("one Back never quits", quits)
        back()
        assertTrue(quits)
    }

    @Test
    fun theBarAsksTheSameWay() {
        open()
        compose.press(Key.DirectionUp)
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse(quits)
        back()
        assertTrue(quits)
    }

    @Test
    fun theLineGoesWhenItsTimeIsOutAndBackAsksAgain() {
        open()
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(QUIT_WINDOW_MS + FRAME_MS)
        compose.onNodeWithText(line).assertDoesNotExist()
        back()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(line).assertIsDisplayed()
        assertFalse("the first Back was too long ago", quits)
    }

    @Test
    fun theLineGoesAsSoonAsFocusMovesDown() {
        open()
        compose.press(Key.DirectionUp)
        back()
        compose.onNodeWithText(line).assertIsDisplayed()
        // Along the bar it stays; down into the rows it goes, and Back asks again.
        compose.press(Key.DirectionRight)
        compose.onNodeWithText(line).assertIsDisplayed()
        compose.press(Key.DirectionDown)
        compose.onNodeWithText(line).assertDoesNotExist()
        back()
        assertFalse(quits)
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun backFromALowerRowStillGoesUpToTheBarFirst() {
        open()
        compose.press(Key.DirectionDown)
        back()
        compose.onNode(isFocused()).assert(hasText("Home"))
        compose.onNodeWithText(line).assertDoesNotExist()
        assertFalse(quits)
    }

    private companion object {
        /** A frame and more, past the line's time. */
        const val FRAME_MS = 32L
    }
}
