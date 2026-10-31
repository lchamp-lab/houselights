package app.houselights.ui.design.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.houselights.R
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TEXT_SCALE_MAX
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A code to type elsewhere keeps its own width in a pane, whatever its letters and the text size: beside its QR code
 * where it fits, under it where it does not, never cut nor under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
// Real fonts, so the code measures as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceCodeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(code: String, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        compose.setTvContent {
            HlTwoPane(side = {}) {
                DeviceCodePlate(
                    code,
                    "Scannez le code, puis saisissez-le.",
                    R.drawable.trakt_qr,
                    "trakt.tv/activate",
                    left = "Ce code expire dans 9 min",
                )
            }
        }
    }

    /** The code's own layout: false when its glyphs run past its box. */
    private fun cut(text: String): Boolean {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().hasVisualOverflow
    }

    private fun DpRect.overlaps(other: DpRect) = rect().overlaps(other.rect())

    private fun DpRect.rect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun holds(code: String, scale: Float) {
        show(code.replace(" ", ""), scale)
        assertFalse("$code cut at $scale", cut(code))
        val box = compose.onNodeWithText(code).getUnclippedBoundsInRoot()
        val qr = compose.onNodeWithContentDescription("Code QR").getUnclippedBoundsInRoot()
        assertFalse("$code at $scale over its QR code: $box, $qr", box.overlaps(qr))
        assertTrue("$code at $scale inside the pane: $box", box.right <= PANE_END)
    }

    @Test
    fun aWideCodeHoldsWhole() = holds("WMWM 0000", 1f)

    @Test
    fun aWideCodeHoldsWholeAtTheLargestTextSize() = holds("WMWM 0000", TEXT_SCALE_MAX)

    @Test
    fun aCodeSitsBesideItsQrCodeWhereItFits() {
        show("B4D7E29A", 1f)
        val box = compose.onNodeWithText("B4D7 E29A").getUnclippedBoundsInRoot()
        val qr = compose.onNodeWithContentDescription("Code QR").getUnclippedBoundsInRoot()
        assertTrue("beside it: $box, $qr", box.right <= qr.left && box.top < qr.bottom)
    }

    private companion object {

        /** The pane's end: the safe area's. */
        val PANE_END = 960.dp - HlSpace.safeH
    }
}
