package app.houselights.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import app.houselights.settings.AppSettings
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pressInSheet
import org.junit.After
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
 * A new path mapping's sheet in the pane Lecture, Android stopping the app as a path is looked up in another app: it
 * comes back open, with its server path and its network path as typed, but a password typed, which it never keeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class MappingSheetRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var settings: SettingsFixture

    @Before
    fun open() {
        settings = SettingsFixture(folder.root)
    }

    @After
    fun close() = settings.close()

    @Test
    fun aPathTypedComesBackWithTheSheet() {
        val restoration = typeInANewSheet("nfs://nas/films")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("the sheet open again", sheetShows())
        compose.onNode(hasSetTextAction() and hasText(SERVER_PATH)).assertExists()
        compose.onNode(hasSetTextAction() and hasText("nfs://nas/films")).assertExists()
    }

    @Test
    fun aPasswordTypedNeverComesBack() {
        val restoration = typeInANewSheet("smb://u:motdepasse@nas/films")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("the sheet open again", sheetShows())
        compose.onNode(hasSetTextAction() and hasText(SERVER_PATH)).assertExists()
        compose.onNode(hasSetTextAction() and hasText("")).assertExists()
        val password = compose.onAllNodes(hasText("motdepasse", substring = true), useUnmergedTree = true)
        assertTrue("no password", password.fetchSemanticsNodes().isEmpty())
    }

    /** The pane Lecture, « Add a mapping » opened, [SERVER_PATH] then [network] typed on the remote's keyboard. */
    private fun typeInANewSheet(network: String): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HouselightsTheme {
                CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) {
                    LazyColumn { playbackPane(AppSettings(), server = true, tracks = false) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Add a mapping") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertTrue("the sheet opened", sheetShows())
        // Focus opens on the server path.
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput(SERVER_PATH)
        compose.onNode(hasContentDescription("Network path (SMB/NFS)")).requestFocus()
        compose.waitForIdle()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasSetTextAction() and isFocused()).performTextInput(network)
        compose.waitForIdle()
        return restoration
    }

    private fun sheetShows() = ShadowDialog.getLatestDialog()?.isShowing == true

    private companion object {
        const val SERVER_PATH = "/media/films"
    }
}
