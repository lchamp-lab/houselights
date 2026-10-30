package app.houselights.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.PathMapping
import app.houselights.core.SourceConfig
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.IgnoredFileRow
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.testDatabase
import app.houselights.providers.local.toFolder
import app.houselights.settings.SecretSetting
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.testing.http
import app.houselights.testing.secretsOf
import app.houselights.testing.testKey
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import kotlin.coroutines.EmptyCoroutineContext

/** The settings' changes: never a crash, and what must not be cut off goes on once the settings are left. */
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
        save: suspend (SourceConfig, SourceConfig.Jellyfin?) -> Unit = store::setSource,
    ): SettingsViewModel {
        val factory = viewModelFactory {
            initializer {
                val sources = SourceSwitcher({ store.state.value }, save, work, signOut = { _, _ -> }, check = check)
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

    /** « Recherche automatique » and « Versions proposées » refused by the device's storage: each said on its own row. */
    @Test
    fun anUpdateSettingThatFailsSaysSoOnItsRow() = runTest {
        val vm = settings(fullSettings(backgroundScope), backgroundScope)
        vm.setUpdateCheck(false)
        advanceUntilIdle()
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.UPDATE_CHECK))
        assertNull(vm.errorOf(SettingAction.UPDATE_CHANNEL))
        vm.setUpdatePrereleases(true)
        advanceUntilIdle()
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.UPDATE_CHANNEL))
        assertNull(vm.errorOf(SettingAction.UPDATE_CHECK))
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

    /**
     * The library closed, or failing, under the settings, as when a screen outlives it: the titles to identify and the
     * files left out stay as they last were, never a crash.
     */
    @Test
    fun whatTheLibraryCannotReadAnyMoreIsNoCrash() = runTest {
        val closed = flow<Nothing> { throw IllegalStateException("the connection pool has been closed") }
        val factory = viewModelFactory {
            initializer {
                val store = fullSettings(backgroundScope)
                val sources = SourceSwitcher({ store.state.value }, store::setSource, backgroundScope)
                SettingsViewModel(
                    store,
                    library,
                    { null },
                    backgroundScope,
                    sources,
                    images = null,
                    toIdentifyCount = closed,
                    ignoredFiles = closed,
                )
            }
        }
        val vm = ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
        advanceUntilIdle()
        assertNull(vm.toIdentify.value)
        assertEquals(emptyList<IgnoredFileRow>(), vm.ignored.value)
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
        val folder = dao.source(id)!!.toFolder()
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
        vm.removeFolder(dao.source(id)!!.toFolder())
        assertTrue(id in vm.removing)
        screens.clear()
        waitFor("the folder removed") { dao.sources().isEmpty() }
    }

    /** Removing A, then B while A waits: B is the last folder, A no longer counting. */
    @Test
    fun aFolderBeingRemovedLeavesTheNextOneLast() = runTest {
        val vm = settings(testSettings(folder.root, backgroundScope), work = backgroundScope)
        val (a, b) = listOf("films", "séries").map { dao.source(dao.insertSource(nfsFolder(it)))!!.toFolder() }
        assertFalse(vm.isLast(b, listOf(a, b)))
        assertTrue(vm.isLast(a, listOf(a)))
        vm.removeFolder(a)
        assertTrue(a.id in vm.removing)
        assertTrue(vm.isLast(b, listOf(a, b)))
    }

    /** The kept server takes the last folder's place: the source first, home then, and the folder removed after. */
    @Test
    fun theKeptServerTakesTheLastFoldersPlace() = runTest {
        val store = keeping(home)
        val id = dao.insertSource(nfsFolder("films"))
        val vm = settings(store, work = backgroundScope)
        var homeShown: Boolean? = null
        vm.removeFolder(dao.source(id)!!.toFolder(), last = true, onResumed = {
            assertEquals(home, store.state.value.source)
            homeShown = runBlocking { dao.source(id) } != null
        })
        waitFor("the folder removed") { id !in vm.removing }
        assertEquals("home shown, the folder still there", true, homeShown)
        assertTrue(dao.sources().isEmpty())
        assertEquals(home, store.state.value.source)
        assertNull(store.state.value.keptServer)
        assertNull(vm.actionError)
    }

    /** A kept server that cannot be made the source changes nothing: the folder stays, and its card says why. */
    @Test
    fun aKeptServerThatCannotTakeOverLeavesTheFolder() = runTest {
        val store = keeping(home)
        val id = dao.insertSource(nfsFolder("films"))
        val vm = settings(store, work = backgroundScope, save = { _, _ -> throw IOException("No space left on device") })
        vm.removeFolder(dao.source(id)!!.toFolder(), last = true, onResumed = { error("Not resumed") })
        waitFor("the removal over") { id !in vm.removing }
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.REMOVE_FOLDER, id))
        assertEquals(1, dao.sources().size)
        assertEquals(SourceConfig.Local, store.state.value.source)
        assertEquals(home, store.state.value.keptServer)
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
        val broken = secretsOf { throw ProviderException("Keystore operation failed") }
        val store = testSettings(folder.root, backgroundScope, broken)
        val vm = settings(store, work = backgroundScope)
        vm.saveTmdbApiKey("k3y")
        waitFor("the key's outcome") { vm.tmdbKey?.outcome != null }
        assertEquals(TmdbKeySave.Failed(UiError.KEYSTORE), vm.tmdbKey?.outcome)
        assertFalse(vm.savingTmdbKey)
    }

    /**
     * « Nettoyer maintenant » pressed twice before it ends: one cleanup, which says what it deleted, never « Rien à
     * nettoyer » from a second over it; its result is the settings', so that a change of pane meanwhile keeps it.
     */
    @Test
    fun aCleanupPressedTwiceRunsOnceAndSaysWhatItDeleted() = runTest {
        val artwork = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("artwork/posters")
        artwork.mkdirs()
        listOf("dune.jpg", "heat.jpg").forEach { artwork.resolve(it).writeText("poster") }
        val vm = settings(testSettings(folder.root, backgroundScope), backgroundScope)
        vm.cleanup()
        assertTrue(vm.cleaning)
        vm.cleanup()
        waitFor("the cleanup done") { !vm.cleaning }
        assertEquals(2, vm.cleaned?.deletedFiles)
        assertNull(vm.actionError)
        waitFor("the storage measured again") { vm.storage.value != null }
        assertEquals(2, vm.cleaned?.deletedFiles)
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
        vm.refreshStorage()
        waitFor("the storage measured") { vm.storage.value != null }
        assertEquals(1_000_000L, vm.storage.value?.imageCacheMax)
        assertTrue("The size was never asked", askedOn.isNotEmpty())
        assertFalse("Asked on the main thread", Thread.currentThread() in askedOn)
    }

    @Test
    fun eachPaneSaysWhatOfItsOwnWasLost() {
        val lost = setOf(SecretSetting.TMDB_KEY, SecretSetting.PATH_MAPPINGS, SecretSetting.TRAKT)
        assertEquals(R.string.settings_lost_tmdb_key, lostSecretNote(Pane.Library, lost, folders = true))
        assertEquals(R.string.settings_lost_mappings, lostSecretNote(Pane.Playback, lost, folders = true))
        assertEquals(R.string.settings_lost_trakt, lostSecretNote(Pane.Trakt, lost, folders = true))
        assertNull(lostSecretNote(Pane.Display, lost, folders = true))
        assertNull(lostSecretNote(Pane.Library, setOf(SecretSetting.SOURCE), folders = true))
    }

    /** A secret the Keystore did not open for now is said where it shows, as one lost is; Trakt's with the folders. */
    @Test
    fun eachPaneSaysWhatOfItsOwnWasNotOpened() {
        val unread = setOf(SecretSetting.TMDB_KEY, SecretSetting.PATH_MAPPINGS, SecretSetting.TRAKT)
        assertTrue(unreadSecretIn(Pane.Library, unread, folders = true))
        assertTrue(unreadSecretIn(Pane.Playback, unread, folders = true))
        assertTrue(unreadSecretIn(Pane.Trakt, unread, folders = true))
        assertFalse(unreadSecretIn(Pane.Trakt, unread, folders = false))
        assertFalse(unreadSecretIn(Pane.Display, unread, folders = true))
        assertFalse(unreadSecretIn(Pane.Library, setOf(SecretSetting.SOURCE), folders = true))
        // The server kept beside the folders, which the pane would otherwise leave out unsaid.
        assertTrue(unreadSecretIn(Pane.Library, setOf(SecretSetting.KEPT_SERVER), folders = true))
    }

    /** Paused under a server, Trakt's pane can only disconnect: it never asks to connect again there. */
    @Test
    fun aLostTraktSessionIsNotToldUnderAServer() {
        val lost = setOf(SecretSetting.TMDB_KEY, SecretSetting.TRAKT)
        assertNull(lostSecretNote(Pane.Trakt, lost, folders = false))
        assertEquals(R.string.settings_lost_tmdb_key, lostSecretNote(Pane.Library, lost, folders = false))
    }

    /**
     * Mappings the Keystore did not open when the settings opened are never written over: nothing is added to them
     * meanwhile, and they show once Réessayer reads them.
     */
    @Test
    fun mappingsNotOpenedAreKeptAndShowOnceRead() = runTest {
        val key = testKey()
        val file = PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.root.resolve("settings.preferences_pb") }
        val films = PathMapping("/media/films", "nfs://192.168.1.20/volume1/films")
        SettingsStore(file, backgroundScope, secretsOf { key }, readOn = EmptyCoroutineContext).setPathMappings(listOf(films))
        var down = true
        val flaky = secretsOf { if (down) throw ProviderException("Keystore operation failed") else key }
        val store = SettingsStore(file, backgroundScope, flaky, readOn = EmptyCoroutineContext)
        assertEquals(setOf(SecretSetting.PATH_MAPPINGS), store.state.first { it.loaded }.unreadSecrets)

        val vm = settings(store, backgroundScope)
        assertTrue(vm.mappings.isEmpty())
        vm.putMapping(null, PathMapping("/media/series", "nfs://192.168.1.20/volume1/series"))
        advanceUntilIdle()
        assertTrue(vm.mappings.isEmpty())

        down = false
        store.readAgain()
        store.state.first { it.unreadSecrets.isEmpty() }
        advanceUntilIdle()
        assertEquals(listOf(films), store.state.value.pathMappings)
        assertEquals(listOf(films), vm.mappings)
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
