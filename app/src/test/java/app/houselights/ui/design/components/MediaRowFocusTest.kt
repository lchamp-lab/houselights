package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.ui.design.HlSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Components: focus coming back into a [MediaRow] lands on its last card, not the nearest. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class MediaRowFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun comingBackIntoARowFindsTheCardLeft() {
        val start = FocusRequester()
        compose.setTvContent {
            Column {
                listOf("a", "b").forEach { row ->
                    MediaRow(header = row) {
                        items((0 until 8).map { "$row$it" }) { card ->
                            val first = if (card == "a0") Modifier.focusRequester(start) else Modifier
                            ArtworkSurface(
                                onClick = {},
                                modifier = first.testTag(card).width(HlSize.poster).aspectRatio(2f / 3f),
                            ) {}
                        }
                    }
                }
            }
        }
        compose.runOnIdle { start.requestFocus() }
        compose.press(Key.DirectionRight, times = 2)
        compose.onNodeWithTag("a2").assertIsFocused()

        // Down to the row below, along it, then up: the card left, two places back, not the one above.
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("b2").assertIsFocused()
        compose.press(Key.DirectionRight, times = 2)
        compose.onNodeWithTag("b4").assertIsFocused()
        compose.press(Key.DirectionUp)
        compose.onNodeWithTag("a2").assertIsFocused()
    }
}
