package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The overview a title's head cuts opens whole beside the page; one it shows whole is words alone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Text measured as drawn: only then are lines cut.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SynopsisTest {
    @get:Rule
    val compose = createComposeRule()

    private val story = "Un instituteur part en vacances avec ses élèves. Sur la plage, une barque les attend, et la mer. " +
        "Les jours passent, les enfants apprennent à nager, à pêcher, à se taire quand l'orage vient."

    @Test
    fun aCutOverviewOpensWhole() {
        var opened = false
        compose.setTvContent { Box(Modifier.width(40.dp)) { HeadOverview(story, onOpen = { opened = true }) } }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(opened)
    }

    @Test
    fun aWholeOverviewIsWordsAlone() {
        compose.setTvContent { HeadOverview("Un été à la mer.", onOpen = {}) }
        compose.onNodeWithText("Un été à la mer.").assertIsDisplayed()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun theSheetGivesTheOverviewWholeUnderItsTitle() {
        val film = MediaItem("f1", MediaKind.MOVIE, "Zéro de conduite", year = 1933, overview = story)
        compose.setTvContent { SynopsisSheet(film, onDismiss = {}) }
        compose.onNodeWithText("Synopsis").assertIsDisplayed()
        compose.onNodeWithText("Zéro de conduite").assertIsDisplayed()
        compose.onAllNodesWithText(story).assertCountEquals(1)
    }
}
