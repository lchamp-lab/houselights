package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Components: an intertitle's action is focused as it appears, unless it is a specimen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class EmptyStateFocusTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theActionOfAnIntertitleIsFocusedAsItAppears() {
        compose.setTvContent { HlEmptyState("Rien ici", action = "Réessayer") }
        compose.onNodeWithText("Réessayer").assertIsFocused()
    }

    @Test
    fun aSpecimenLeavesFocusWhereItIs() {
        compose.setTvContent { HlEmptyState("Rien ici", action = "Réessayer", focusOnAppear = false) }
        compose.onNodeWithText("Réessayer").assertIsNotFocused()
    }
}
