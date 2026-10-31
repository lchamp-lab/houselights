package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import app.houselights.settings.TextSize
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.waitWithLooper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Réglages › Affichage, « Taille du texte »: a row under the interface's language opening the standard choice sheet;
 * the step chosen is kept in the settings, and the screen behind is laid out again at it once the sheet has gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class TextSizeRowTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var settings: SettingsFixture
    private val store get() = settings.store
    private val vm get() = settings.vm

    @Before
    fun open() {
        settings = SettingsFixture(folder.root)
    }

    @After
    fun close() = settings.close()

    /** The display pane at the stored text size, as the app lays its screens out. */
    private fun show() {
        compose.setContent {
            val s by vm.settings.collectAsState()
            HouselightsTheme(s.textSize.step) {
                val memory = rememberFocusMemory()
                val rows = remember { PaneRows(memory, Pane.Display.name) }
                CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                    LazyColumn { displayPane(s, rows) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row() = compose.onNode(hasText("Taille du texte") and hasClickAction())

    private fun sheet() = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    /** The text size [text] is laid out at. */
    private fun scaleOf(text: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.density.fontScale
    }

    @Test
    fun theRowSaysTheSizeUnderTheLanguage() {
        show()
        row().assertExists()
        compose.onNodeWithText("Standard").assertExists()
        compose.onNodeWithText("Tous les textes de l’app").assertExists()
        val language = compose.onNodeWithText("Langue de l’interface").fetchSemanticsNode().boundsInRoot
        val size = compose.onNodeWithText("Taille du texte").fetchSemanticsNode().boundsInRoot
        assertTrue("under the language: $size, $language", size.top >= language.bottom)
    }

    /** The standard choice sheet: what it changes, the three steps and what each is for, focus on the current one. */
    @Test
    fun theSheetOffersThreeSteps() {
        show()
        row().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("Agrandit ou réduit tous les textes de Houselights. La grille et les marges ne changent pas.").assertExists()
        listOf(
            "Compacte" to "Pour lire de plus près",
            "Standard" to "Recommandée",
            "Grande" to "Pour lire de plus loin",
        ).forEach { (name, what) ->
            compose.onNode(hasText(name) and hasClickAction() and hasText(what)).assertExists()
        }
        compose.onNode(hasText("Recommandée") and hasClickAction()).assertIsSelected().assertIsFocused()
    }

    /** Choosing applies and closes: the step is saved, the screen behind laid out again at it, focus back on the row. */
    @Test
    fun choosingCompacteSavesItAndLaysTheScreenOutAgain() {
        show()
        assertEquals(1f, scaleOf("Taille du texte"))
        row().requestFocus()
        compose.press(Key.DirectionCenter)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_UP)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitWithLooper("Compacte saved") { store.state.value.textSize == TextSize.COMPACT }
        assertNull("the sheet has gone", sheet())
        compose.onNodeWithText("Compacte").assertExists()
        assertEquals(TextSize.COMPACT.step, scaleOf("Taille du texte"))
        row().assertIsFocused()
    }

    /** With Android at its largest, Grande can add nothing: its description says so, and it stays a choice. */
    @Test
    @Config(fontScale = 1.3f)
    fun withAndroidAtItsLargestGrandeSaysItIsReached() {
        show()
        row().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasText("Grande") and hasClickAction() and hasText("Déjà atteinte par le réglage d’Android")).assertExists()
        assertEquals(1.3f, scaleOf("Grande"))
    }
}
