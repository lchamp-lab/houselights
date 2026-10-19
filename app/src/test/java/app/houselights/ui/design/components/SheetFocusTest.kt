package app.houselights.ui.design.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Sheets: Back closes a sheet, and when it has gone, focus is back where it was. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SheetFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun backFromASheetFindsFocusOnWhatOpenedIt() {
        var open by mutableStateOf(false)
        var dismissed = 0
        val opener = FocusRequester()
        compose.setTvContent {
            Column {
                HlButton("Modifier", onClick = { open = true }, modifier = Modifier.focusRequester(opener).testTag("opener"))
                HlButton("Lire", onClick = {}, modifier = Modifier.testTag("other"))
            }
            if (open) {
                val first = remember { FocusRequester() }
                HlSideSheet(
                    title = "Modifier",
                    onDismiss = {
                        dismissed++
                        open = false
                    },
                    initialFocus = first,
                ) {
                    HlSettingRow("Identifier", Modifier.focusRequester(first).testTag("row"), onClick = {})
                }
            }
        }
        compose.runOnIdle { opener.requestFocus() }
        compose.press(Key.DirectionCenter)
        compose.onNodeWithTag("row").assertIsFocused()

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("row").assertDoesNotExist()
        assertEquals(1, dismissed)
        compose.onNodeWithTag("opener").assertIsFocused()
    }
}
