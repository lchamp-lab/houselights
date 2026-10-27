package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import app.houselights.journal.JournalAccess
import app.houselights.journal.JournalOpener
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * About, Envoyer le journal: OK opens a sheet with the page's QR code and address and what the log keeps, or that
 * there is no home network, or that the page cannot open; the page is served while the sheet is open and the app on
 * screen, and the sheet closes with the page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
// Real fonts, so the sheet's words take their room on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JournalSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val row = FocusRequester()

    /** Serves [access] until cancelled, or until [closes] completes, as the page does at its time. */
    private class FakeJournal(private val access: JournalAccess) : JournalOpener {
        val closes = CompletableDeferred<Unit>()
        var serving = false
        var served = 0
        var cancelled = false

        override suspend fun serve(opened: (JournalAccess) -> Unit) {
            served++
            serving = true
            try {
                opened(access)
                if (access is JournalAccess.Open) closes.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                serving = false
            }
        }
    }

    /** The row where About has it: under Soutenir Houselights, in the pane's list, a row after it. */
    private fun show(journal: JournalOpener) {
        compose.setTvContent {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    HlSettingGroup(null) {
                        SupportRow()
                        JournalRow(Modifier.focusRequester(row).testTag(ROW), journal)
                        HlSettingRow("Design system", Modifier.testTag(NEXT), onClick = {})
                    }
                }
            }
        }
        compose.runOnIdle { row.requestFocus() }
    }

    @Test
    fun theRowSaysWhatItOffersAndServesNothingYet() {
        val journal = FakeJournal(OPEN)
        show(journal)
        compose.onNodeWithText("Envoyer le journal").assertExists()
        compose.onNodeWithText("Pour signaler un problème, depuis votre téléphone").assertExists()
        compose.onNodeWithText(ADDRESS).assertDoesNotExist()
        assertEquals(0, journal.served)
    }

    @Test
    fun okShowsTheCodeItsAddressAndWhatTheLogKeeps() {
        val journal = FakeJournal(OPEN)
        show(journal)
        compose.press(Key.DirectionCenter)
        assertTrue(journal.serving)
        compose.onNodeWithText(SCAN).assertExists()
        compose.onNodeWithContentDescription("Code QR").assertExists()
        compose.onNodeWithText(ADDRESS).assertExists()
        compose.onNodeWithText(MASKED).assertExists()
        compose.onNodeWithText(CLOSES).assertExists()
        compose.onNodeWithText(FEW).assertDoesNotExist()
    }

    /** At the default text size, everything the sheet says lies inside the safe area, with nothing to scroll for. */
    @Test
    fun theSheetHoldsAllItSays() = assertHeld(OPEN)

    @Test
    fun theSheetHoldsAllItSaysAtItsLongest() = assertHeld(JournalAccess.Open(LONGEST, few = true))

    private fun assertHeld(access: JournalAccess) {
        show(FakeJournal(access))
        compose.press(Key.DirectionCenter)
        val foot = foot(compose.onNodeWithText(CLOSES).fetchSemanticsNode())
        assertTrue("$access: its last line ends at $foot", foot <= SCREEN_HEIGHT - HlSpace.safeV.value)
        // Its notes lie on the glass, as nothing needs reading through.
        compose.onNode(isFocusable() and hasText(MASKED, substring = true)).assertDoesNotExist()
    }

    /** At the largest text size the sheet runs past the screen: its notes take focus, and Down reads it through. */
    @Test
    fun atTheLargestTextSizeDownReadsTheSheetThrough() {
        RuntimeEnvironment.setFontScale(LARGEST)
        show(FakeJournal(JournalAccess.Open(LONGEST, few = true)))
        compose.press(Key.DirectionCenter)
        val closes = compose.onNodeWithText(CLOSES)
        assertFalse("its last line starts out of sight", shown(closes.fetchSemanticsNode()))
        down()
        compose.onNode(hasText(MASKED, substring = true) and isFocused()).assertExists()
        repeat(PRESSES) { down() }
        assertTrue("its last line read", shown(closes.fetchSemanticsNode()))
    }

    /**
     * Down in the sheet, as Android sends it: to a window where no view has focus yet, it first gives focus that way
     * (ViewRootImpl's focus navigation, which a key dispatched to the window skips).
     */
    private fun down() {
        var gave = false
        compose.runOnIdle {
            val window = checkNotNull(ShadowDialog.getLatestDialog()?.window) { "No sheet shows" }
            if (window.decorView.findFocus() == null) gave = window.decorView.requestFocus(View.FOCUS_DOWN)
        }
        if (gave) compose.waitForIdle() else compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    /** Where [node] ends, whatever clips it. */
    private fun foot(node: SemanticsNode) = node.positionInRoot.y + node.size.height

    /** [node] whole on the screen, not clipped by the sheet's scroll. */
    private fun shown(node: SemanticsNode) = node.boundsInRoot.bottom >= foot(node) - 1f && foot(node) <= SCREEN_HEIGHT

    /** The code never shows without what it opens: with almost nothing in the log, one sentence says both. */
    @Test
    fun withAlmostNothingInTheLogItSaysWhatTheCodeOpensAndWhenToOpenIt() {
        show(FakeJournal(JournalAccess.Open(ADDRESS, few = true)))
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(FEW).assertExists()
        compose.onNodeWithText(SCAN).assertDoesNotExist()
        compose.onNodeWithContentDescription("Code QR").assertExists()
        compose.onNodeWithText(ADDRESS).assertExists()
        compose.onNodeWithText(MASKED).assertExists()
    }

    @Test
    fun withoutAHomeNetworkItSaysSoInPlaceOfTheCode() {
        show(FakeJournal(JournalAccess.Offline))
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(OFFLINE).assertExists()
        compose.onNodeWithText(SCAN).assertDoesNotExist()
        compose.onNodeWithContentDescription("Code QR").assertDoesNotExist()
        compose.onNodeWithText(MASKED).assertDoesNotExist()
        compose.onNodeWithText(CLOSES).assertDoesNotExist()
    }

    @Test
    fun whenThePageCannotOpenItSaysSoInPlaceOfTheCode() {
        show(FakeJournal(JournalAccess.Failed))
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(FAILED).assertExists()
        compose.onNodeWithText(OFFLINE).assertDoesNotExist()
        compose.onNodeWithContentDescription("Code QR").assertDoesNotExist()
        compose.onNodeWithText(MASKED).assertDoesNotExist()
        compose.onNodeWithText(CLOSES).assertDoesNotExist()
    }

    /** Home leaves the sheet composed while the app is out of sight: the page closes as the app stops, and the sheet with it. */
    @Test
    fun leavingTheAppClosesThePageAndTheSheet() {
        val journal = FakeJournal(OPEN)
        show(journal)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(ADDRESS).assertExists()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("the page closes as the app stops", journal.cancelled)
            assertFalse(journal.serving)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        compose.onNodeWithText(ADDRESS).assertDoesNotExist()
        assertEquals("back on screen, it opens no page again", 1, journal.served)
    }

    @Test
    fun backClosesTheSheetAndThePage() {
        val journal = FakeJournal(OPEN)
        show(journal)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(ADDRESS).assertExists()

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(ADDRESS).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("the page closes with the sheet", journal.cancelled)
            assertTrue(!journal.serving)
        }
        compose.onNodeWithTag(ROW).assertIsFocused()
        compose.press(Key.DirectionDown)
        compose.onNodeWithTag(NEXT).assertIsFocused()
    }

    @Test
    fun theSheetClosesWithThePageOnceItsTimeIsUp() {
        val journal = FakeJournal(OPEN)
        show(journal)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(ADDRESS).assertExists()

        compose.runOnIdle { journal.closes.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithText(ADDRESS).assertDoesNotExist()
        compose.onNodeWithTag(ROW).assertIsFocused()
    }

    private companion object {
        const val ROW = "journal"
        const val NEXT = "next"
        const val NB = Typography.nbsp
        const val SCREEN_HEIGHT = 540f
        const val LARGEST = 1.3f
        const val PRESSES = 12
        const val SCAN = "Scannez ce code avec votre téléphone$NB: il ouvre le journal de Houselights, à joindre à votre message."
        const val ADDRESS = "http://192.168.1.53:8642/journal/k7f2m4q9x3w8r5t6y2zb"
        const val LONGEST = "http://192.168.100.200:65535/journal/k7f2m4q9x3w8r5t6y2zb"
        val OPEN = JournalAccess.Open(ADDRESS, few = false)
        const val MASKED = "Mots de passe et jetons y sont masqués$NB; les noms de vos fichiers, partages et du NAS y restent$NB: " +
            "relisez-le avant de le publier."
        const val CLOSES = "La page se ferme avec ce panneau, au plus tard dans 10${NB}min."
        const val FEW = "Scannez ce code pour ouvrir le journal, presque vide$NB: revenez ici juste après le problème."
        const val OFFLINE = "Le lecteur n’est relié à aucun réseau local$NB: reliez-le à votre box, puis ouvrez à nouveau ce panneau."
        const val FAILED = "La page n’a pas pu s’ouvrir sur ce lecteur$NB: fermez ce panneau, puis ouvrez-le à nouveau."
    }
}
