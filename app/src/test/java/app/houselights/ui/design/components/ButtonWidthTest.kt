package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpRect
import app.houselights.ui.design.HlIcons
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Controls: a button with a busy label takes from the start the width of the wider of its words, so neither
 * it nor the one beside it moves as it works.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
// Real fonts, so words measure as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonWidthTest {
    @get:Rule
    val compose = createComposeRule()

    /** Where « Enregistrer » and « Tester » lie, by their click target. */
    private fun bounds(text: String): DpRect = compose.onNode(hasText(text) and hasClickAction()).getUnclippedBoundsInRoot()

    @Test
    fun aBusyButtonKeepsItsWidthAndItsNeighbourItsPlace() {
        var saving by mutableStateOf<String?>(null)
        var testing by mutableStateOf(false)
        compose.setTvContent {
            Row {
                HlButton(
                    "Enregistrer", {}, icon = HlIcons.Check,
                    busy = saving != null, busyLabel = saving ?: "Vérification…", reserveFor = "Enregistrement…",
                )
                HlButton("Tester", {}, icon = HlIcons.Test, busy = testing, busyLabel = "Vérification…")
            }
        }
        val save = bounds("Enregistrer")
        val test = bounds("Tester")
        testing = true
        compose.waitForIdle()
        assertEquals("« Vérification… » in « Tester »'s width", test, bounds("Vérification…"))
        testing = false
        saving = "Vérification…"
        compose.waitForIdle()
        assertEquals(save, bounds("Vérification…"))
        assertEquals("its neighbour unmoved", test, bounds("Tester"))
        saving = "Enregistrement…"
        compose.waitForIdle()
        assertEquals(save, bounds("Enregistrement…"))
        assertEquals(test, bounds("Tester"))
    }
}
