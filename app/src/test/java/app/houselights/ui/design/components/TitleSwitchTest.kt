package app.houselights.ui.design.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tile after a films' wall's title turns it to its sagas, and back. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class TitleSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTileTurnsTheTitleToTheOtherViewAndBack() {
        compose.setTvContent {
            var sagas by remember { mutableStateOf(false) }
            HlTitleSwitch(if (sagas) "Sagas" else "Films", label = if (sagas) "Films" else "Sagas", onSwitch = { sagas = !sagas })
        }
        compose.onNodeWithContentDescription("Sagas").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Sagas").assertIsDisplayed()
        compose.onNodeWithContentDescription("Films").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Films").assertIsDisplayed()
    }
}
