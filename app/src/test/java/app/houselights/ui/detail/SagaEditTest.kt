package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.MediaProvider
import app.houselights.core.MetadataEditor
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Saga page (#48): Modifier, the last of the saga's actions, opens its edit sheet, its poster and its backdrop
 * alone, each opening its choice; back from it, focus is on Modifier. A Jellyfin box set, whose source keeps no
 * pictures, has none, its check ending its actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SagaEditTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Loads at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = work.cancel()

    private fun text(id: Int) = context.getString(id)

    private fun page(source: MediaProvider = duneSource()) = SagaPage(compose, source, work).also { it.open(duneSaga.id) }

    private fun tile(id: Int) = compose.onNode(hasContentDescription(text(id)) and hasClickAction())

    /** Whether [id]'s words show in the open sheet. */
    private fun inSheet(id: Int) =
        compose.onAllNodes(hasText(text(id)) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun modifierOpensTheSagasPosterAndBackdropAloneFocusBackOnIt() {
        val page = page()
        compose.press(Key.DirectionRight, times = 3)
        tile(R.string.edit).assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(
            "Affiche and Fond d'écran, no Logo nor Identifier",
            listOf(true, true, false, false),
            listOf(R.string.artwork_poster, R.string.artwork_backdrop, R.string.artwork_logo, R.string.identify).map(::inSheet),
        )
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(duneSaga.id to ArtworkKind.POSTER), page.edited)
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.runOnIdle { page.shown = true }
        compose.waitForIdle()
        tile(R.string.edit).assertIsFocused()
    }

    /** A Jellyfin box set has no Modifier: its check ends the saga's actions, Right from it opening film 1. */
    @Test
    fun aJellyfinBoxSetHasNoModifier() {
        page(jellyfin(duneSource()))
        tile(R.string.edit).assertDoesNotExist()
        compose.press(Key.DirectionRight, times = 2)
        tile(R.string.mark_saga_watched).assertIsFocused()
        // The last of its actions: Right on to film 1.
        compose.press(Key.DirectionRight)
        compose.onNodeWithContentDescription(context.getString(R.string.saga_card_name, dune.title, 1) + ", 2021").assertIsFocused()
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    /** [source] as a Jellyfin server's: it keeps no pictures of its own, its interface changing them. */
    private fun jellyfin(source: MediaProvider): MediaProvider = object : MediaProvider by source {
        override val editor: MetadataEditor? = null
    }
}
