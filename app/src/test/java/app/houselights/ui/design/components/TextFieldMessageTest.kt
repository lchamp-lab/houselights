package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Components: one line under an [HlTextField], the hint or the error, also given to accessibility. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class TextFieldMessageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFieldSaysWhatToTypeUntilSomethingIsWrong() {
        var error by mutableStateOf<String?>(null)
        compose.setTvContent {
            HlTextField(
                label = "Adresse",
                value = "nas.local",
                onValueChange = {},
                supporting = "L’adresse IP ou le nom du NAS",
                error = error,
            )
        }
        compose.onNodeWithText("L’adresse IP ou le nom du NAS").assertExists()
        compose.onNodeWithContentDescription("Adresse").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))

        error = "Le NAS ne répond pas"
        compose.onNodeWithText("Le NAS ne répond pas").assertExists()
        compose.onNodeWithText("L’adresse IP ou le nom du NAS").assertDoesNotExist()
        compose.onNodeWithContentDescription("Adresse")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Le NAS ne répond pas"))

        // Put right, the field says what to type again.
        error = null
        compose.onNodeWithText("Le NAS ne répond pas").assertDoesNotExist()
        compose.onNodeWithText("L’adresse IP ou le nom du NAS").assertExists()
    }
}
