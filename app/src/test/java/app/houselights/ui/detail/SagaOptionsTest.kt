package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.MediaProvider
import app.houselights.core.MetadataEditor
import app.houselights.ui.design.components.MENU_KEY
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.pressOnRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Saga page (#48): Menu on the predella, pressed as with the remote, opens the saga's sheet, the one of its
 * poster on a wall, its mark first and focused, then Modifier, its poster and its backdrop alone; the Menu key says so at
 * the predella's end while focus is in it. The panels have no options: Menu does nothing there, the key gone. A Jellyfin
 * box set's predella has no key, its sheet its mark alone, which Menu still opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SagaOptionsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Loads at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = work.cancel()

    private fun text(id: Int) = context.getString(id)

    private fun page(source: MediaProvider = duneSource()) = SagaPage(compose, source, work).also { it.open(duneSaga.id) }

    private val resume get() = compose.onNode(hasText(text(R.string.resume)) and hasClickAction())
    private val fromStart get() = compose.onNodeWithContentDescription(text(R.string.from_start))

    /** A row of the open sheet, by its words. */
    private fun option(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction() and hasAnyAncestor(isDialog()))

    /** Whether [id]'s words show in the open sheet. */
    private fun inSheet(id: Int) =
        compose.onAllNodes(hasText(text(id)) and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The Menu keys drawn on the page. */
    private fun keys() = compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).fetchSemanticsNodes().size

    /** The sheets open. */
    private fun sheets() = compose.onAllNodes(isDialog()).fetchSemanticsNodes().size

    /**
     * Menu on Reprendre opens the saga's sheet, its mark focused, then Modifier; the key, at the predella's end, goes
     * under it. Modifier has the saga's poster and its backdrop alone, no logo, no Identifier, each opening its choice.
     */
    @Test
    fun menuOnThePredellaOpensTheSagasSheetModifierItsPosterAndBackdropAlone() {
        val page = page()
        resume.assertIsFocused()
        assertEquals("the key at the predella's end", 1, keys())
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_saga_watched).assertIsFocused()
        option(R.string.edit).assertExists()
        assertEquals("no key under a sheet", 0, keys())

        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(
            "Affiche and Fond d'écran, no Logo nor Identifier",
            listOf(true, true, false, false),
            listOf(R.string.artwork_poster, R.string.artwork_backdrop, R.string.artwork_logo, R.string.identify).map(::inSheet),
        )
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(duneSaga.id to ArtworkKind.POSTER), page.edited)
    }

    /** Menu on each of the predella's actions, Reprendre, Depuis le début and the check, opens the same sheet. */
    @Test
    fun menuOnEachOfThePredellasActionsOpensTheSagasSheet() {
        page()
        repeat(3) { step ->
            if (step > 0) {
                compose.press(Key.DirectionRight)
                resume.assertIsNotFocused()
            }
            if (step == 1) fromStart.assertIsFocused()
            compose.pressOnRemote(Key.Menu)
            option(R.string.mark_saga_watched).assertIsFocused()
            compose.pressInSheet(KeyEvent.KEYCODE_BACK)
            assertEquals(0, sheets())
        }
    }

    /** Back from a picture's choice, focus is on the action Menu was pressed on: the page's memory, as back from a film's. */
    @Test
    fun backFromTheChoiceFocusIsOnTheActionMenuWasPressedOn() {
        val page = page()
        compose.press(Key.DirectionRight)
        fromStart.assertIsFocused()
        compose.pressOnRemote(Key.Menu)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf(duneSaga.id to ArtworkKind.POSTER), page.edited)
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.runOnIdle { page.shown = true }
        compose.waitForIdle()
        fromStart.assertIsFocused()
    }

    /** The panels have no options: focus in them, the key goes and Menu does nothing; back on the predella, it comes back. */
    @Test
    fun aPanelHasNoOptionsMenuDoingNothingThere() {
        page()
        compose.press(Key.DirectionUp)
        assertEquals("no key while a panel has focus", 0, keys())
        compose.pressOnRemote(Key.Menu)
        assertEquals("no sheet", 0, sheets())
        compose.press(Key.DirectionDown)
        resume.assertIsFocused()
        assertEquals(1, keys())
    }

    /**
     * A Jellyfin box set's sheet has its mark alone, the check beside it does the same: no key at the predella's end, but
     * Menu still opens it, and OK marks the saga.
     */
    @Test
    fun aJellyfinBoxSetsPredellaHasNoKeyItsSheetItsMarkAlone() {
        val page = page(jellyfin(duneSource()))
        resume.assertIsFocused()
        assertEquals("no key", 0, keys())
        compose.pressOnRemote(Key.Menu)
        option(R.string.mark_saga_watched).assertIsFocused()
        option(R.string.edit).assertDoesNotExist()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, sheets())
        assertEquals("marked with its films", true, page.vm.state.value.item?.user?.played)
    }

    /** [source] as a Jellyfin server's: it keeps no pictures of its own, its interface changing them. */
    private fun jellyfin(source: MediaProvider): MediaProvider = object : MediaProvider by source {
        override val editor: MetadataEditor? = null
    }
}
