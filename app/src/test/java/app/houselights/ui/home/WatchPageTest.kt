package app.houselights.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.MediaItem
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** « À regarder »: home's rows under their title, opened on the row its door shows. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class WatchPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(row: WatchRow, onOpen: (MediaItem) -> Unit = {}) {
        compose.setTvContent { WatchBands(fullHome(), row, onOpen) }
        compose.waitForIdle()
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    @Test
    fun itIsTitledAsAPlace() {
        open(WatchRow.CONTINUE)
        compose.onNodeWithText("To watch").assertIsDisplayed()
    }

    @Test
    fun theNextEpisodesDoorOpensItOnItsRow() {
        open(WatchRow.NEXT_UP)
        focusIsOn("MobLand")
    }

    @Test
    fun theNewsDoorOpensItOnTheLatestTitles() {
        open(WatchRow.LATEST)
        focusIsOn("Film1")
    }

    @Test
    fun theSecondTitleInProgressOpensItOnReprendre() {
        open(WatchRow.CONTINUE)
        focusIsOn("Heat")
    }

    @Test
    fun itsRowsAreHomesWithTheirCards() {
        val opened = mutableListOf<String>()
        open(WatchRow.LATEST) { opened += it.id }
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("film2"), opened)
        compose.press(Key.DirectionDown)
        focusIsOn("Show")
    }

    @Test
    fun aRowItLacksOpensItOnItsFirst() {
        assertEquals(0, sectionOf(emptyList(), WatchRow.NEXT_UP))
        val rows = (fullHome().content as HomeContent.Rows).sections
        assertEquals(2, sectionOf(rows, WatchRow.LATEST))
        assertEquals(0, sectionOf(rows.drop(1).filterNot { it.type == app.houselights.core.SectionType.NEXT_UP }, WatchRow.NEXT_UP))
    }
}
