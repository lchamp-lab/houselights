package app.houselights.ui.home

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A play asked from home's case that fails is said there, under its action, until the next OK on it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomePlayFailureTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theCaseSaysWhyItDidNotPlayUntilTheNextOk() {
        val did = mutableListOf<String>()
        val actions = HomeActions(onPlay = { item, resume -> did += "play ${item.id} $resume" }, onClearProblem = { did += "clear" })
        compose.setTvContent { HomeGrid(fullHome(), actions, playProblem = UiError.NO_PLAYER) }
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.play_failed), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.no_player), useUnmergedTree = true).assertIsDisplayed()

        compose.press(Key.DirectionCenter)
        assertEquals(listOf("clear", "play heat true"), did)
    }
}
