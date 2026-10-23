package app.houselights.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.PathMapping
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SecretSetting
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.testing.http
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.fullSettings
import app.houselights.ui.testing.testSettings
import app.houselights.ui.testing.waitFor
import app.houselights.ui.util.UiError
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException
import java.util.Collections

/** The settings' changes: never a crash, and what must not be cut off goes on once the settings are left. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val screens = ViewModelStore()
    private val home = SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "token")
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var library: LocalLibrary

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // No scan runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        library = LocalLibrary(context, dao, scanner, artwork) {}
    }

    @After
    fun close() {
        screens.clear()
        db.close()
    }

    /** The settings' ViewModel as the screen holds it: [screens] clears it when the settings are left. */
    private fun settings(
        store: SettingsStore,
        work: CoroutineScope,
        checkKey: suspend (String) -> TmdbKeyCheck = { TmdbKeyCheck.ACCEPTED },
        images: ImageLoader? = null,
        check: suspend (SourceConfig.Jellyfin, String) -> Unit = { _, _ -> },
    ): SettingsViewModel {
        val factory = viewModelFactory {
            initializer {
                val sources = SourceSwitcher({ store.state.value }, store::setSource, work, signOut = { _, _ -> }, check = check)
                SettingsViewModel(store, library, { FakeProvider() }, work, sources, images = images, checkTmdbKey = checkKey)
            }
        }
        return ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
    }

    /** Reported on the row it was made from, where focus is, never on another. */
    @Test
    fun aSettingThatCannotBeWrittenSaysSoAndNeverCrashes() = runTest {
        val store = fullSettings(backgroundScope)
        val vm = settings(store, backgroundScope)
        vm.setUi4k(false)
        advanceUntilIdle()
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.UI_RESOLUTION))
        assertNull(vm.errorOf(SettingAction.FILE_ACCESS))
        // The next change clears it.
        vm.setForceHttp(true)
        assertNull(vm.actionError)
        advanceUntilIdle()
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.FILE_ACCESS))
        assertNull(vm.errorOf(SettingAction.UI_RESOLUTION))
    }

    /** "My files" refused by the device's storage: reported on the source's row until the next change. */
    @Test
    fun foldersThatCannotBecomeTheSourceSaySoOnTheSourceRow() = runTest {
        val vm = settings(testSettings(folder.root, backgroundScope), backgroundScope)
        vm.actionFailed(SettingAction.SOURCE, UiError.NOT_SAVED)
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.SOURCE))
        assertNull(vm.errorOf(SettingAction.COLLECTIONS))
        vm.setCollectionMinFilms(0)
        assertNull(vm.errorOf(SettingAction.SOURCE))
    }

    /** A sleeping kept server: nothing changes, the source's row says why, and the server stays kept. */
    @Test
    fun aKeptServerThatDoesNotAnswerSaysSoOnTheSourceRow() = runTest {
        val store = keeping(home)
        val vm = settings(store, backgroundScope, check = { _, _ -> throw IOException("Connection timed out") })
        var resumed = false
        vm.resumeServer(home, onResumed = { resumed = true }, onRefused = { error("Refused") })
        waitFor("the server asked") { !vm.resuming }
        assertFalse(resumed)
        assertEquals(UiError.OFFLINE, vm.errorOf(SettingAction.KEPT_SERVER))
        assertEquals(home, store.state.value.keptServer)
    }

    /** A token the server refuses: the sign-in opens for that server, which is no longer kept. */
    @Test
    fun aKeptServerThatRefusesItsTokenOpensItsSignIn() = runTest {
        val store = keeping(home)
        val vm = settings(store, backgroundScope, check = { _, _ -> throw http(401) })
        var refused: SourceConfig.Jellyfin? = null
        vm.resumeServer(home, onResumed = { error("Resumed") }, onRefused = { refused = it })
        waitFor("the sign-in asked") { refused != null }
        assertEquals(home, refused)
        assertNull(store.state.first { it.keptServer == null }.keptServer)
        assertEquals(SourceConfig.Local, store.state.value.source)
        assertNull(vm.actionError)
    }

    @Test
    fun aKeptServerThatTakesItsTokenIsTheSourceAgain() = runTest {
        val store = keeping(home)
        val vm = settings(store, backgroundScope)
        var resumed = false
        vm.resumeServer(home, onResumed = { resumed = true }, onRefused = { error("Refused") })
        waitFor("the server resumed") { resumed }
        assertEquals(home, store.state.value.source)
        assertNull(store.state.value.keptServer)
    }

    /** Settings restored before the source could be read show what applies to no source, never a crash. */
    @Test
    fun withoutASourceTheSettingsShowWhatApplies() = runTest {
        val factory = viewModelFactory {
            initializer {
                val store = fullSettings(backgroundScope)
                val sources = SourceSwitcher({ store.state.value }, store::setSource, backgroundScope)
                SettingsViewModel(store, library, { null }, backgroundScope, sources, images = null)
            }
        }
        val vm = ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
        assertFalse(vm.capabilities.pathMapping)
        assertFalse(vm.counts)
    }

    /** Settings on the folders, [server] kept beside them. */
    private suspend fun TestScope.keeping(server: SourceConfig.Jellyfin): SettingsStore {
        val store = testSettings(folder.root, backgroundScope)
        store.setSource(SourceConfig.Local, kept = server)
        store.state.first { it.keptServer != null }
        return store
    }

    @Test
    fun aFolderThatCannotBeRemovedSaysSoOnItsCard() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val id = dao.insertSource(nfsFolder("films"))
        val vm = settings(store, work = backgroundScope)
        val folder = dao.source(id)!!
        // The library's database refuses every write.
        db.openHelper.writableDatabase.execSQL("PRAGMA query_only = ON")
        vm.removeFolder(folder)
        waitFor("the folder's removal over") { id !in vm.removing }
        assertEquals(UiError.FAILED, vm.errorOf(SettingAction.REMOVE_FOLDER, id))
        assertNull(vm.errorOf(SettingAction.REMOVE_FOLDER, id + 1))
        assertNull(vm.errorOf(SettingAction.CLEANUP))
    }

    /** A folder removed waits for the file a scan reads in it: leaving the settings meanwhile does not stop it. */
    @Test
    fun aFolderRemovedIsRemovedEvenWhenTheSettingsAreLeft() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val id = dao.insertSource(nfsFolder("films"))
        val vm = settings(store, work = backgroundScope)
        vm.removeFolder(dao.source(id)!!)
        assertTrue(id in vm.removing)
        screens.clear()
        waitFor("the folder removed") { dao.sources().isEmpty() }
    }

    /** TMDB checks a key for a few seconds; leaving the sheet and the settings meanwhile still saves it. */
    @Test
    fun aTmdbKeyIsSavedEvenWhenTheSettingsAreLeft() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val tmdb = CompletableDeferred<TmdbKeyCheck>()
        val vm = settings(store, work = backgroundScope, checkKey = { tmdb.await() })
        vm.saveTmdbApiKey(" k3y ")
        advanceUntilIdle()
        assertTrue(vm.savingTmdbKey)
        screens.clear()
        tmdb.complete(TmdbKeyCheck.ACCEPTED)
        assertEquals("k3y", store.state.first { it.tmdbApiKey != null }.tmdbApiKey)
        waitFor("the key's outcome") { vm.tmdbKey?.outcome != null }
        assertEquals(TmdbKeySave.Checked(TmdbKeyCheck.ACCEPTED), vm.tmdbKey?.outcome)
        assertFalse(vm.savingTmdbKey)
    }

    /** The key check's outcome lives in the ViewModel, so a reopened sheet shows it with the typed key. */
    @Test
    fun howATmdbKeyWentOutlivesItsSheet() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val tmdb = CompletableDeferred<TmdbKeyCheck>()
        val vm = settings(store, work = backgroundScope, checkKey = { tmdb.await() })
        val number = vm.saveTmdbApiKey(" k3y ")
        advanceUntilIdle()
        assertEquals(TmdbKeyAttempt(number!!, "k3y"), vm.tmdbKey)
        // A second Save meanwhile, from a sheet opened again, changes nothing.
        assertNull(vm.saveTmdbApiKey("other"))

        tmdb.complete(TmdbKeyCheck.REFUSED)
        waitFor("the key saved") { !vm.savingTmdbKey }
        assertEquals(TmdbKeyAttempt(number, "k3y", TmdbKeySave.Checked(TmdbKeyCheck.REFUSED)), vm.tmdbKey)
        assertNull(store.state.value.tmdbApiKey)

        vm.editTmdbKey()
        assertNull(vm.tmdbKey)
    }

    /** A key typed while the last is checked: the last one's outcome never shows over the new one. */
    @Test
    fun aKeyTypedMeanwhileIsNotToldHowTheLastOneWent() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val tmdb = CompletableDeferred<TmdbKeyCheck>()
        val vm = settings(store, work = backgroundScope, checkKey = { tmdb.await() })
        val first = vm.saveTmdbApiKey("k3y")
        advanceUntilIdle()
        vm.editTmdbKey()
        tmdb.complete(TmdbKeyCheck.REFUSED)
        waitFor("the first key saved") { !vm.savingTmdbKey }
        assertNull(vm.tmdbKey)

        // The next one has a number of its own: a sheet waiting on the first never takes it for its own.
        val second = vm.saveTmdbApiKey("")
        waitFor("the second key's outcome") { vm.tmdbKey?.outcome != null }
        assertTrue(second != first)
        assertEquals(TmdbKeyAttempt(second!!, "", TmdbKeySave.Checked(TmdbKeyCheck.ACCEPTED)), vm.tmdbKey)
    }

    /** The Keystore refuses to seal the key: the sheet says nothing was saved, the app goes on. */
    @Test
    fun aTmdbKeyTheKeystoreCannotSealSaysSo() = runTest {
        val broken = Secrets { throw ProviderException("Keystore operation failed") }
        val store = testSettings(folder.root, backgroundScope, broken)
        val vm = settings(store, work = backgroundScope)
        vm.saveTmdbApiKey("k3y")
        waitFor("the key's outcome") { vm.tmdbKey?.outcome != null }
        assertEquals(TmdbKeySave.Failed(UiError.KEYSTORE), vm.tmdbKey?.outcome)
        assertFalse(vm.savingTmdbKey)
    }

    /** Coil's cache reads its journal at the first size query, under its cleanup lock: off the main thread. */
    @OptIn(ExperimentalCoilApi::class)
    @Test
    fun theImageCacheIsMeasuredAwayFromTheMainThread() = runTest {
        val askedOn = Collections.synchronizedList(mutableListOf<Thread>())
        val real = DiskCache.Builder().directory(folder.newFolder("images")).maxSizeBytes(1_000_000L).build()
        val cache = object : DiskCache by real {
            override val size: Long
                get() = real.size.also { askedOn += Thread.currentThread() }
        }
        val images = ImageLoader.Builder(ApplicationProvider.getApplicationContext<Context>()).diskCache(cache).build()
        val vm = settings(testSettings(folder.root, backgroundScope), work = backgroundScope, images = images)
        waitFor("the storage measured") { vm.storage.value != null }
        assertEquals(1_000_000L, vm.storage.value?.imageCacheMax)
        assertTrue("The size was never asked", askedOn.isNotEmpty())
        assertFalse("Asked on the main thread", Thread.currentThread() in askedOn)
    }

    @Test
    fun eachPaneSaysWhatOfItsOwnWasLost() {
        val lost = setOf(SecretSetting.TMDB_KEY, SecretSetting.PATH_MAPPINGS, SecretSetting.TRAKT)
        assertEquals(R.string.settings_lost_tmdb_key, lostSecretNote(Pane.Library, lost))
        assertEquals(R.string.settings_lost_mappings, lostSecretNote(Pane.Playback, lost))
        assertEquals(R.string.settings_lost_trakt, lostSecretNote(Pane.Trakt, lost))
        assertNull(lostSecretNote(Pane.Display, lost))
        assertNull(lostSecretNote(Pane.Library, setOf(SecretSetting.SOURCE)))
    }

    /** A mapping whose path is emptied goes at once, rather than stay as an empty row until the next start. */
    @Test
    fun aMappingEmptiedGoes() = runTest {
        val vm = settings(testSettings(folder.root, backgroundScope), backgroundScope)
        vm.putMapping(null, PathMapping("/media/films", "nfs://192.168.1.20/volume1/films"))
        assertEquals(1, vm.mappings.size)
        vm.putMapping(0, PathMapping("", "nfs://192.168.1.20/volume1/films"))
        assertTrue(vm.mappings.isEmpty())
    }
}
