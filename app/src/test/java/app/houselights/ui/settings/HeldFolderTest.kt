package app.houselights.ui.settings

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.ItemEntity
import app.houselights.providers.local.ItemKind
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Réglages › Bibliothèque: a folder whose titles are held counts them on its showcase, by the ring, in place of its last
 * scan, answered or not; OK opens its sheet on a first row saying so, OK again their sheet, « Garder » and « Retirer »
 * there at any time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class HeldFolderTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private val room = Executors.newSingleThreadExecutor()
    private lateinit var db: LibraryDatabase
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel
    private var films = 0L

    /** Whether the player's storage refuses the answers' writes. */
    private var refused = false

    /** The library's rows, the answers' writes refused while [refused] says, as a full storage refuses them. */
    private class Answers(private val dao: LibraryDao, private val refused: () -> Boolean) : LibraryDao by dao {
        override suspend fun setHeldKept(id: Long, kept: Boolean) {
            if (refused()) throw IOException("No space left on device")
            dao.setHeldKept(id, kept)
        }

        override suspend fun removeHeld(ids: Collection<Long>, handedOver: Set<Long>): Int {
            if (refused()) throw IOException("No space left on device")
            return dao.removeHeld(ids, handedOver)
        }
    }

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Room answers on one thread of its own: answering on the calling thread, a flow the pane follows would be read
        // again inside the transaction removing the titles, which Room refuses.
        db = testDatabase {
            allowMainThreadQueries()
            setQueryExecutor(room)
            setTransactionExecutor(room)
        }
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // No scan runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val dao = Answers(db.dao()) { refused }
        val scanner = LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, dao, scanner, artwork) {}
        store = testSettings(folder.root, files)
        val factory = viewModelFactory {
            initializer {
                val sources = SourceSwitcher({ store.state.value }, store::setSource, work, signOut = { _, _ -> })
                SettingsViewModel(store, library, { FakeProvider() }, work, sources, images = null)
            }
        }
        vm = ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
    }

    @After
    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
        room.shutdown()
    }

    /** The library pane on Films, twelve of its thirteen titles held. */
    private fun show() {
        runBlocking {
            // Scanned three hours ago: the pass that held them.
            films = db.dao().insertSource(nfsFolder("Films", lastScanAt = System.currentTimeMillis() - THREE_HOURS_MS))
            (1..13).forEach { n ->
                db.dao().insert(
                    ItemEntity(
                        kind = ItemKind.MOVIE, sourceId = films, title = "Film $n", sortTitle = "film %02d".format(n), year = 2000 + n,
                        filePath = "Film $n.mkv", addedAt = 0, missingSince = 1L.takeIf { n <= 12 },
                    )
                )
            }
            store.setSource(SourceConfig.Local, null)
            store.state.first { it.source == SourceConfig.Local }
        }
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(rememberFocusMemory(), "library")
                LazyColumn { libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}), rows) }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("Films") and hasClickAction()).assertExists()
    }

    /** OK on the showcase, OK on its first row: their sheet, « Garder » focused. */
    private fun openTheirSheet() {
        compose.onNode(hasText("Films") and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.onNode(hasText("12 titres introuvables") and isFocused()).assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Garder") and isFocused()).assertExists()
    }

    private fun held() = runBlocking { db.dao().heldIn(films) }

    @Test
    fun theFolderCountsThemByTheRingInPlaceOfItsLastScan() {
        show()
        compose.onNodeWithText("12 introuvables", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Analysé", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Kept, they stay counted on their folder, its sheet still opening theirs. */
    @Test
    fun keptTheyStayCountedOnTheirFolder() {
        show()
        openTheirSheet()
        compose.onNodeWithText("Et 2 autres").assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { runBlocking { db.dao().source(films)?.heldKept == true } }
        compose.onNodeWithText("12 introuvables", useUnmergedTree = true).assertExists()
        openTheirSheet()
    }

    /** « Retirer » from Réglages, at any time: they leave, and the folder says how it stands again. */
    @Test
    fun removedFromTheirFolderTheyLeave() {
        show()
        openTheirSheet()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("Retirer") and isFocused()).assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(TIMEOUT_MS) { held().isEmpty() }
        assertEquals("the title there stays", listOf("Film 13.mkv"), runBlocking { db.dao().filesOf(films).mapNotNull { it.filePath } })
    }

    private fun saysNothingSaved() = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodesWithText("Rien n’a été enregistré", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** « Garder » not saved: said on the folder's showcase, their sheet still to answer. */
    @Test
    fun aKeepNotSavedIsSaidOnTheFolder() {
        refused = true
        show()
        openTheirSheet()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        saysNothingSaved()
        assertEquals(false, runBlocking { db.dao().source(films)?.heldKept })
        assertEquals(12, held().size)
    }

    /** « Retirer » not saved: said on the folder's showcase, the titles still held there. */
    @Test
    fun aRemovalNotSavedIsSaidOnTheFolder() {
        refused = true
        show()
        openTheirSheet()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        saysNothingSaved()
        assertEquals(12, held().size)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val THREE_HOURS_MS = 3 * 60 * 60_000L
    }
}
