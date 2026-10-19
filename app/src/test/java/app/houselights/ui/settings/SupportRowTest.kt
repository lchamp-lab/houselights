package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** About, Soutenir Houselights: OK shows the Ko-fi code in a side sheet; Back closes it, focus back on the row. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class SupportRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val row = FocusRequester()

    /** The row where About has it: in its group of actions, in the pane's list, a row after it. */
    private fun show() {
        compose.setTvContent {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    HlSettingGroup(null) {
                        SupportRow(Modifier.focusRequester(row).testTag(ROW))
                        HlSettingRow("Design system", Modifier.testTag(NEXT), onClick = {})
                    }
                }
            }
        }
        compose.runOnIdle { row.requestFocus() }
    }

    @Test
    fun theRowSaysWhatItOffers() {
        show()
        compose.onNodeWithText("Soutenir Houselights").assertExists()
        compose.onNodeWithText("Offrez un café au projet, depuis votre téléphone.").assertExists()
        compose.onNodeWithText(ADDRESS).assertDoesNotExist()
    }

    @Test
    fun okShowsTheCodeItsAddressAndWhatToDoWithIt() {
        show()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Code QR").assertExists()
        compose.onNodeWithText(ADDRESS).assertExists()
        compose.onNodeWithText("Scannez ce code avec votre téléphone pour ouvrir la page Ko-fi du projet.").assertExists()
    }

    @Test
    fun backClosesTheSheetAndFocusIsBackOnTheRow() {
        show()
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(ADDRESS).assertExists()

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(ADDRESS).assertDoesNotExist()
        compose.onNodeWithTag(ROW).assertIsFocused()
        // The remote goes on from there, down to the next action.
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag(NEXT).assertIsFocused()
    }

    private companion object {
        const val ROW = "support"
        const val NEXT = "next"
        const val ADDRESS = "ko-fi.com/lchamp_lab"
    }
}
