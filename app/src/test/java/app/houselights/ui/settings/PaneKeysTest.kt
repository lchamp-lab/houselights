package app.houselights.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A pane's rows each keep their own place in its list, whatever stands above them: a note at its head leaving (a secret
 * entered again) takes neither focus from the row it was on nor the sheet that row opened; one coming while the pane is
 * at its top shows above them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class PaneKeysTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var settings: SettingsFixture

    /** The note at the pane's head, as Réglages shows one for a secret lost. */
    private var note by mutableStateOf(true)

    @Before
    fun open() {
        settings = SettingsFixture(folder.root)
    }

    @After
    fun close() = settings.close()

    private fun add() = compose.onNode(hasText("Add a mapping") and hasClickAction())

    private fun networkPath() = compose.onNode(hasText("Network path (SMB/NFS)") and hasClickAction())

    private fun sheetShows() = ShadowDialog.getLatestDialog()?.isShowing == true

    @Test
    fun aRowKeepsFocusAndItsSheetAsTheNoteAboveItLeaves() {
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) {
                LazyColumn {
                    if (note) item(key = "lost-secret") { BasicText("Mappings lost") }
                    playbackPane(AppSettings(), server = true, tracks = false)
                }
            }
        }
        compose.waitForIdle()
        add().requestFocus()
        add().assertIsFocused()

        note = false
        compose.waitForIdle()
        add().assertIsFocused()

        note = true
        compose.waitForIdle()
        add().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue("the sheet opened", sheetShows())
        note = false
        compose.waitForIdle()
        assertTrue("the sheet still open", sheetShows())
    }

    /**
     * The pane at its top, focus on a row: a note coming at its head, a secret no longer read, shows above the rows, which
     * keep their keys and the list its place by them, focus staying on the row.
     */
    @Test
    fun aNoteComingAtTheHeadOfAPaneAtItsTopShows() {
        note = false
        lateinit var list: LazyListState
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) {
                list = rememberLazyListState()
                NoteInSight(list, note)
                LazyColumn(state = list) {
                    if (note) item(key = "lost-secret") { BasicText("Mappings lost") }
                    playbackPane(AppSettings(), server = true, tracks = false)
                }
            }
        }
        compose.waitForIdle()
        networkPath().requestFocus()
        compose.waitForIdle()
        networkPath().assertIsFocused()
        assertEquals("the pane at its top", 0 to 0, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)

        note = true
        compose.waitForIdle()
        compose.onNodeWithText("Mappings lost").assertIsDisplayed()
        networkPath().assertIsFocused()
    }
}
