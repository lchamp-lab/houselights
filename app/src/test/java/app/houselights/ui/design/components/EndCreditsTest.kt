package app.houselights.ui.design.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The end credits: the overview whole, then each craft read as one line, three names at most; nothing to say, nothing drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class EndCreditsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun eachCraftIsReadAsOneLineThreeNamesAtMost() {
        compose.setTvContent {
            val image = Craft("Image", listOf("Boris Kaufman", "Louis Berger", "Jean-Paul Alphen", "Albert Viguier"))
            EndCredits("Un été à la mer.", listOf(image))
        }
        compose.onNodeWithContentDescription("Un été à la mer.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Image, Boris Kaufman, Louis Berger, Jean-Paul Alphen").assertIsDisplayed()
    }

    @Test
    fun nothingToSayDrawsNothing() {
        compose.setTvContent { EndCredits(null, listOf(Craft("Musique", emptyList()))) }
        compose.onAllNodesWithText("Générique", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Musique", substring = true).assertCountEquals(0)
    }
}
