package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A saga film missing from the library: focus reaches it, outlined, it says it is absent, and OK does nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class AbsentCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusReachesItAndItSaysItIsAbsent() {
        compose.setTvContent {
            Row {
                PosterCard(MediaItem("m1", MediaKind.MOVIE, "Alien"), onClick = {})
                AbsentCard("Aliens", 1986)
            }
        }
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Aliens, 1986, Not in your library")
            .assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }
}
