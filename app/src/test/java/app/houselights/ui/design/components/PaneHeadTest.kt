package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** DESIGN.md, Secondary screens: a pane's title, and About's lockup, sit on the screen title's baseline at every text size. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaneHeadTest {
    @get:Rule
    val compose = createComposeRule()

    private var fontScale by mutableFloatStateOf(1f)

    private fun show(head: @Composable () -> Unit) {
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HlTwoPane(side = { ScreenTitle(SCREEN) }) {
                    Column {
                        head()
                        Box(Modifier.testTag(NEXT))
                    }
                }
            }
        }
    }

    /** Where [text]'s first baseline lies in the root. */
    private fun baseline(text: String): Dp {
        val node = compose.onNodeWithText(text)
        return node.getUnclippedBoundsInRoot().top + node.getAlignmentLinePosition(FirstBaseline)
    }

    @Test
    fun aPaneTitleSitsOnTheScreenTitleBaseline() {
        show { PaneHead(PANE, aside = "12 titres dans 2 dossiers") }
        for (scale in SCALES) {
            fontScale = scale
            compose.waitForIdle()
            val screen = baseline(SCREEN)
            assertEquals("at $scale", screen.value, baseline(PANE).value, HALF_PX)
            assertEquals("the aside, at $scale", screen.value, baseline("12 titres dans 2 dossiers").value, HALF_PX)
            // The head ends on the title's baseline: what follows is measured from it.
            assertEquals("at $scale", screen.value, compose.onNodeWithTag(NEXT).getUnclippedBoundsInRoot().top.value, HALF_PX)
        }
    }

    @Test
    fun aSentenceFollowsTheTitleByItsBaseline() {
        show { PaneHead(PANE, sentence = SENTENCE) }
        assertEquals((baseline(PANE) + HlSpace.xl).value, baseline(SENTENCE).value, ONE_PX)
    }

    @Test
    fun theLockupNamesAboutOnTheScreenTitleBaseline() {
        show { PaneLockup() }
        for (scale in SCALES) {
            fontScale = scale
            compose.waitForIdle()
            val name = compose.onNodeWithContentDescription("houselights").getUnclippedBoundsInRoot()
            assertEquals("its capitals, at $scale", HlSize.wordmark.value, name.height.value, OVERSHOOT)
            // Round letters overshoot the capitals a little below their foot.
            assertEquals("at $scale", baseline(SCREEN).value, name.bottom.value, OVERSHOOT)
        }
    }

    private companion object {
        const val SCREEN = "Réglages"
        const val PANE = "Affichage"
        const val SENTENCE = "Le lecteur règle sa sortie."
        const val NEXT = "next"
        val SCALES = listOf(1f, 1.3f)
        const val HALF_PX = 0.5f
        const val ONE_PX = 1f

        /** wordmark.xml's round letters past its capitals, at the lockup's size (1.9 %), and a pixel's rounding. */
        val OVERSHOOT = 1.5.dp.value
    }
}
