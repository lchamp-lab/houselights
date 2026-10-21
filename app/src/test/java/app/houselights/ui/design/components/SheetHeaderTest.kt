package app.houselights.ui.design.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DESIGN.md, Sheets: a sheet's artwork is as tall as its title and the line of facts under it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SheetHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theArtworkIsAsTallAsTheTitleBlock() {
        compose.setTvContent {
            HlSideSheet(
                "Fiche technique",
                onDismiss = {},
                facts = listOf("Colony", "2026"),
                artwork = { PosterThumb(null, Modifier.testTag("art"), width = null) },
            ) { HlSettingRow("Résolution", value = "Full HD") }
        }
        val art = compose.onNodeWithTag("art").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Fiche technique").getUnclippedBoundsInRoot()
        val facts = compose.onNodeWithText("2026").getUnclippedBoundsInRoot()
        assertEquals("its foot is the facts' foot", facts.bottom.value, art.bottom.value, 1f)
        val reach = title.height / 2
        assertTrue("its top is the title's: $art, $title", art.top <= title.top + reach && art.top >= title.top - reach)
        assertEquals("a poster's shape", art.height.value * 2 / 3, art.width.value, 1f)
        assertTrue("the words after it", title.left >= art.right)
    }
}
