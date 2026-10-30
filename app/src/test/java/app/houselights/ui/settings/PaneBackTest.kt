package app.houselights.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.focusKey
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Réglages' Back from its pane: focus going into the pane and coming out of it composes the screen's Back alone, never
 * the screen around it, its pane and its rows; from the pane, Back takes focus back to the pane's entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PaneBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val settle = SettleMainLooper()

    @Test
    fun focusCrossingIntoThePaneComposesItsBackAlone() {
        val memory = FocusMemory()
        var screen = 0
        compose.setTvContent {
            // As SettingsScreen holds it: the screen keeps whether focus is in the pane, and reads it never.
            val inPane = remember { mutableStateOf(false) }
            screen++
            Column {
                Box(Modifier.size(HlSize.control).testTag(ENTRY).focusKey(FocusKey.pane(Pane.Library.name), memory).focusable())
                Box(Modifier.size(HlSize.control).testTag(ROW).onFocusChanged { inPane.value = it.hasFocus }.focusable())
                PaneBack({ inPane.value }, memory, Pane.Library)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ENTRY).requestFocus()
        compose.onNodeWithTag(ROW).requestFocus()
        compose.onNodeWithTag(ROW).assertIsFocused()
        compose.onNodeWithTag(ENTRY).requestFocus()
        compose.onNodeWithTag(ROW).requestFocus()
        compose.waitForIdle()
        assertEquals("the screen composed once, as it opened", 1, screen)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(ENTRY).assertIsFocused()
        assertEquals(1, screen)
    }

    private companion object {
        const val ENTRY = "entry"
        const val ROW = "row"
    }
}
