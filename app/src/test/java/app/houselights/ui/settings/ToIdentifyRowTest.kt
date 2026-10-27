package app.houselights.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/**
 * Réglages › Bibliothèque: « À identifier » is the last row of the library's actions while there are titles to
 * identify, their count in white beside it; gone, from under focus or while its list was open, focus is on « Analyser
 * maintenant ».
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class ToIdentifyRowTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private val count = MutableStateFlow(0)
    private var opened = 0
    private lateinit var db: LibraryDatabase
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = testDatabase { onCallingThread() }
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        store = testSettings(folder.root, files)
        val factory = viewModelFactory {
            initializer {
                val sources = SourceSwitcher({ store.state.value }, store::setSource, work, signOut = { _, _ -> })
                SettingsViewModel(store, library, { FakeProvider() }, work, sources, images = null, toIdentifyCount = count)
            }
        }
        vm = ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
        db.dao().insertSource(nfsFolder("Films"))
        store.setSource(SourceConfig.Local, null)
        store.state.first { it.source == SourceConfig.Local }
        Unit
    }

    @After
    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
    }

    /**
     * The library pane, focus restored from [memory] as Réglages restores it, a row not in view revealed; [longer], with
     * rows below it, as many folders make it, so a pass revealing a row gone scrolls the actions out of view.
     */
    private fun show(memory: FocusMemory = FocusMemory(), longer: Boolean = false) {
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(memory, PANE)
                val list = rememberLazyListState()
                RestoreFocus(memory, fallback = null) { key -> revealRow(list, key, memory) }
                LazyColumn(state = list) {
                    libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}, toIdentify = { opened++ }), rows)
                    if (longer) items(BELOW) { Spacer(Modifier.height(BELOW_HEIGHT)) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun row() = compose.onNode(hasText("To identify") and hasClickAction())

    private fun focusIsOn(name: String) = compose.onNode(isFocused()).assert(hasText(name, substring = true))

    @Test
    fun theRowComesLastWithItsCountOnlyWhileThereAreTitlesToIdentify() {
        show()
        row().assertDoesNotExist()
        count.value = 12
        compose.waitForIdle()
        row().assert(hasText("12", substring = true)).assert(hasText("Not recognized with certainty", substring = true))
        val last = compose.onNode(hasText("Refresh all details", substring = true) and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("after the library's three actions", row().getUnclippedBoundsInRoot().top > last.top)
    }

    @Test
    fun okOnTheRowOpensTheList() {
        count.value = 3
        show()
        row().requestFocus()
        compose.press(Key.DirectionCenter)
        assertEquals(1, opened)
    }

    @Test
    fun theRowLeavingFromUnderFocusLeavesItOnScanNow() {
        count.value = 1
        show()
        row().requestFocus()
        count.value = 0
        compose.waitForIdle()
        row().assertDoesNotExist()
        focusIsOn("Scan now")
    }

    /**
     * Back from the list, or from the queue's last title, once nothing is left to identify: the pane, scrolled as it
     * looked for the row, comes back where it was, on its actions.
     */
    @Test
    fun backWithTheRowGoneFocusIsOnScanNow() {
        show(FocusMemory(initial = "pane:$PANE/row:actions/row:to_identify"), longer = true)
        compose.onNodeWithText("Scan now").assertExists()
        focusIsOn("Scan now")
    }

    @Test
    fun backWithTitlesLeftFocusIsOnTheRow() {
        count.value = 2
        show(FocusMemory(initial = "pane:$PANE/row:actions/row:to_identify"))
        focusIsOn("To identify")
    }

    private companion object {
        const val PANE = "library"
        const val BELOW = 6
        val BELOW_HEIGHT = 200.dp
    }
}
