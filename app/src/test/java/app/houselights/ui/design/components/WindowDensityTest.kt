package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.canvasDensityOf
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, One canvas: a sheet and a menu, each in a window of its own, are laid out on the canvas and at its text
 * size, not at the density the system declares (the Zidoo's 240 dpi, 1.5 px a dp where the canvas has 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = ZIDOO)
class WindowDensityTest {
    @get:Rule
    val compose = createComposeRule()

    private val canvas = canvasDensityOf(SCREEN_PX)

    private fun px(dp: Dp) = with(canvas) { dp.roundToPx() }

    @Test
    fun aSheetIsLaidOutOnTheCanvas() {
        var inside: Density? = null
        compose.setTvContent {
            HlSideSheet("Modifier", onDismiss = {}) {
                inside = LocalDensity.current
                Box(Modifier.size(HlSize.control).testTag(INSIDE))
            }
        }
        compose.waitForIdle()
        assertEquals(canvas, inside)
        assertEquals(px(HlSize.control), compose.onNodeWithTag(INSIDE).fetchSemanticsNode().size.height)
    }

    @Test
    fun aSheetTakesTheTextSizeItOpensAt() {
        var inside: Density? = null
        compose.setTvContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, LARGER)) {
                HlSideSheet("Modifier", onDismiss = {}) { inside = LocalDensity.current }
            }
        }
        compose.waitForIdle()
        assertEquals(Density(canvas.density, LARGER), inside)
    }

    @Test
    fun aMenuIsLaidOutOnTheCanvas() {
        compose.setTvContent {
            val options = listOf(MenuOption("Titre", selected = true) {}, MenuOption("Année", selected = false) {})
            HlMenuButton("Trier", HlIcons.Sort, listOf(MenuSection(null, options)))
        }
        compose.onNodeWithText("Trier").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(px(HlSize.controlCompact), compose.onNodeWithText("Année").fetchSemanticsNode().size.height)
    }

    private companion object {
        const val SCREEN_PX = 1920
        const val INSIDE = "inside"
        const val LARGER = 1.3f
    }
}

/** The Zidoo's screen: 1920 × 1080 px at the 240 dpi it declares. */
private const val ZIDOO = "w1280dp-h720dp-land-hdpi"
