package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Focus: focus entering an [HlSegmented] track lands on the current option, from any side. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class SegmentedFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusEntersATrackOnTheCurrentOption() {
        val above = FocusRequester()
        compose.setTvContent {
            Column {
                HlButton("Trier", onClick = {}, modifier = Modifier.focusRequester(above).testTag("above"))
                HlSegmented(listOf("Accueil", "Films", "Séries", "Collections"), selected = 2, onSelect = {})
                HlButton("Filtrer", onClick = {}, modifier = Modifier.testTag("below"))
            }
        }
        compose.runOnIdle { above.requestFocus() }
        // From above the first option: the current one, not the nearest.
        compose.press(Key.DirectionDown)
        compose.onNodeWithText("Séries").assertIsFocused()
        compose.onNodeWithText("Accueil").assertIsNotFocused()

        // Along the track, out of it, and back in from below: the current one again.
        compose.press(Key.DirectionLeft, times = 2)
        compose.onNodeWithText("Accueil").assertIsFocused()
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("below").assertIsFocused()
        compose.press(Key.DirectionUp)
        compose.onNodeWithText("Séries").assertIsFocused()
    }
}
