package app.houselights.ui.design.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The end credits: each craft one line under its heading, three names at most; nothing to say, nothing drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class EndCreditsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eachCraftIsReadAsOneLineThreeNamesAtMost() {
        compose.setTvContent {
            EndCredits(listOf(Craft("Image", listOf("Boris Kaufman", "Louis Berger", "Jean-Paul Alphen", "Albert Viguier"))))
        }
        compose.onNodeWithText("Credits").assertIsDisplayed()
        compose.onNodeWithContentDescription("Image, Boris Kaufman, Louis Berger, Jean-Paul Alphen").assertIsDisplayed()
    }

    @Test
    fun nothingToSayDrawsNothing() {
        compose.setTvContent {
            EndCredits(listOf(Craft("Musique", emptyList())))
        }
        compose.onAllNodesWithText("Credits", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Musique", substring = true).assertCountEquals(0)
    }
}
