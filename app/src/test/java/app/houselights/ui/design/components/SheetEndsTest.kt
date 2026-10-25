package app.houselights.ui.design.components

import android.view.KeyEvent
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.Text
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A sheet that scrolls shows its ends whole once focus nears them: the group's title above, the note below. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SheetEndsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nearItsEndsASheetScrollsToThem() {
        val scroll = ScrollState(0)
        compose.setTvContent {
            val first = remember { FocusRequester() }
            HlSideSheet("Audio et sous-titres", onDismiss = {}, initialFocus = first, scroll = scroll) {
                HlSettingGroup("Sous-titres") {
                    repeat(ROWS) { i -> HlSettingRow("Piste $i", if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = {}) }
                }
                Text("Votre choix vaut pour ce film et toutes ses copies. Un changement fait pendant la lecture est retenu aussi.")
            }
        }
        compose.waitForIdle()
        assertTrue("the rows outgrow the sheet", scroll.maxValue > 0)

        repeat(ROWS - 1) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("the note under the last row", scroll.maxValue, scroll.value)

        repeat(ROWS - 1) { compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        assertEquals("the group's title above the first row", 0, scroll.value)
    }

    private companion object {
        const val ROWS = 12
        const val SETTLE_MS = 2_000L
    }
}
