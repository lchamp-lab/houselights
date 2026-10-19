package app.houselights.ui.design.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import app.houselights.core.Person
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A person whose page opens is read as a button; one with nothing to open announces no action. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PersonCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aPersonWhosePageOpensIsReadAsAButton() {
        var opened = 0
        val villeneuve = Person("Denis Villeneuve", "Réalisation", null)
        compose.setTvContent { PersonCard(villeneuve, onOpen = { opened++ }) }
        compose.onNodeWithContentDescription("Denis Villeneuve, Réalisation")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, opened)
    }

    @Test
    fun aPersonWithNothingToOpenAnnouncesNoAction() {
        compose.setTvContent { PersonCard(Person("Benicio del Toro", "Alejandro", null)) }
        compose.onNodeWithContentDescription("Benicio del Toro, Alejandro").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }
}
