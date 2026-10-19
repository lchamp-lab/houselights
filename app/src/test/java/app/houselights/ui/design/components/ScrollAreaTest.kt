package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.ui.design.HlSize
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Components: an [HlScrollArea] takes focus only when it can scroll, then scrolls a step per press. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ScrollAreaTest {
    @get:Rule
    val compose = createComposeRule()

    private val before = FocusRequester()
    private val area = FocusRequester()

    private fun show(lines: Int) = compose.setTvContent {
        Column {
            HlButton("Avant", onClick = {}, modifier = Modifier.focusRequester(before).testTag("before"))
            HlScrollArea(Modifier.height(200.dp).focusRequester(area).testTag("area")) {
                Column { repeat(lines) { Text("Ligne $it") } }
            }
            HlButton("Après", onClick = {}, modifier = Modifier.testTag("after"))
        }
    }

    private fun scroll(): ScrollAxisRange =
        compose.onNodeWithTag("area").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    fun downScrollsAStepAtATimeThenMovesOnAtTheEnd() {
        show(lines = 40)
        compose.runOnIdle { before.requestFocus() }
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("area").assertIsFocused()
        assertEquals(0f, scroll().value())

        compose.press(Key.DirectionDown)
        // A dp of the canvas is a pixel here.
        assertEquals(HlSize.scrollStep.value, scroll().value())

        // Down to the end: the area keeps focus while there is more.
        var presses = 0
        while (scroll().value() < scroll().maxValue() && presses++ < 40) {
            compose.onNodeWithTag("area").assertIsFocused()
            compose.press(Key.DirectionDown)
        }
        assertEquals(scroll().maxValue(), scroll().value())
        compose.onNodeWithTag("area").assertIsFocused()
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("after").assertIsFocused()

        compose.press(Key.DirectionUp)
        compose.onNodeWithTag("area").assertIsFocused()
        compose.press(Key.DirectionUp)
        assertEquals(scroll().maxValue() - HlSize.scrollStep.value, scroll().value())
    }

    @Test
    fun anAreaWithNothingToScrollIsPassedBy() {
        show(lines = 2)
        compose.runOnIdle { before.requestFocus() }
        assertEquals(0f, scroll().maxValue())
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag("after").assertIsFocused()
    }
}
