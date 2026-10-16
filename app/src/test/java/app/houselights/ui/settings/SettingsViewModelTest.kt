package app.houselights.ui.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SecretConverter
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.settings.SecretSetting
import app.houselights.settings.SettingsStore
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.testSecrets
import app.houselights.ui.testing.testSettings
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import javax.crypto.KeyGenerator

/** The settings' changes: never a crash, and what must not be cut off goes on once the settings are left. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val screens = ViewModelStore()
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var library: LocalLibrary

    @Before
    fun open() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // No scan runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        library = LocalLibrary(context, dao, LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }), artwork) {}
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
    ): SettingsViewModel {
        val factory = viewModelFactory {
            initializer { SettingsViewModel(store, library, { FakeProvider() }, work, images = null, checkTmdbKey = checkKey) }
        }
        return ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
    }

    /** Settings that read, and refuse every write: the device's storage is full. */
    private class FullStore : DataStore<Preferences> {
        override val data = flowOf(emptyPreferences())

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("No space left on device")
    }

    /** Lets work on Room's and the scanner's own threads finish. */
    private suspend fun TestScope.settle(done: suspend () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (done()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
    }

    /** Said on the row it was made from, where focus is: never on another one. */
    @Test
    fun aSettingThatCannotBeWrittenSaysSoAndNeverCrashes() = runTest {
        val store = SettingsStore(FullStore(), backgroundScope, testSecrets())
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

    /** "My files" chosen in the settings, refused by the device's storage: said on the source's row, until the next change. */
    @Test
    fun foldersThatCannotBecomeTheSourceSaySoOnTheSourceRow() = runTest {
        val vm = settings(testSettings(folder.root, backgroundScope), backgroundScope)
        vm.actionFailed(SettingAction.SOURCE, UiError.NOT_SAVED)
        assertEquals(UiError.NOT_SAVED, vm.errorOf(SettingAction.SOURCE))
        assertNull(vm.errorOf(SettingAction.COLLECTIONS))
        vm.setCollectionMinFilms(0)
        assertNull(vm.errorOf(SettingAction.SOURCE))
    }

    /** A folder that could not be removed says so on its own card, not on the others. */
    @Test
    fun aFolderThatCannotBeRemovedSaysSoOnItsCard() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val id = dao.insertSource(
            SourceEntity(
                name = "films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media",
                path = "films", username = "", password = Secret(""),
            )
        )
        val vm = settings(store, work = backgroundScope)
        val folder = dao.source(id)!!
        // The library's database refuses every write.
        db.openHelper.writableDatabase.execSQL("PRAGMA query_only = ON")
        vm.removeFolder(folder)
        settle { id !in vm.removing }
        assertEquals(UiError.FAILED, vm.errorOf(SettingAction.REMOVE_FOLDER, id))
        assertNull(vm.errorOf(SettingAction.REMOVE_FOLDER, id + 1))
        assertNull(vm.errorOf(SettingAction.CLEANUP))
    }

    /** A folder removed waits for the file a scan reads in it: leaving the settings meanwhile does not stop it. */
    @Test
    fun aFolderRemovedIsRemovedEvenWhenTheSettingsAreLeft() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val id = dao.insertSource(
            SourceEntity(
                name = "films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas", share = "media",
                path = "films", username = "", password = Secret(""),
            )
        )
        val vm = settings(store, work = backgroundScope)
        vm.removeFolder(dao.source(id)!!)
        assertTrue(id in vm.removing)
        screens.clear()
        settle { dao.sources().isEmpty() }
        assertTrue(dao.sources().isEmpty())
    }

    /** TMDB takes a few seconds to check a key; the sheet and the settings are left meanwhile: the key is saved all the same. */
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
        settle { vm.tmdbKey?.outcome != null }
        assertEquals(TmdbKeySave.Checked(TmdbKeyCheck.ACCEPTED), vm.tmdbKey?.outcome)
        assertFalse(vm.savingTmdbKey)
    }

    /**
     * The sheet closed, then opened again while TMDB checks the key: how it went is the ViewModel's,
     * which the sheet opened again reads, the key as typed with it, until a key is typed again.
     */
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
        settle { !vm.savingTmdbKey }
        assertEquals(TmdbKeyAttempt(number, "k3y", TmdbKeySave.Checked(TmdbKeyCheck.REFUSED)), vm.tmdbKey)
        assertNull(store.state.value.tmdbApiKey)

        vm.editTmdbKey()
        assertNull(vm.tmdbKey)
    }

    /** A key typed while the last one is checked: how that one went is never said over the new one. */
    @Test
    fun aKeyTypedMeanwhileIsNotToldHowTheLastOneWent() = runTest {
        val store = testSettings(folder.root, backgroundScope)
        val tmdb = CompletableDeferred<TmdbKeyCheck>()
        val vm = settings(store, work = backgroundScope, checkKey = { tmdb.await() })
        val first = vm.saveTmdbApiKey("k3y")
        advanceUntilIdle()
        vm.editTmdbKey()
        tmdb.complete(TmdbKeyCheck.REFUSED)
        settle { !vm.savingTmdbKey }
        assertNull(vm.tmdbKey)

        // The next one has a number of its own: a sheet waiting on the first never takes it for its own.
        val second = vm.saveTmdbApiKey("")
        settle { vm.tmdbKey?.outcome != null }
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
        settle { vm.tmdbKey?.outcome != null }
        assertEquals(TmdbKeySave.Failed(UiError.KEYSTORE), vm.tmdbKey?.outcome)
        assertFalse(vm.savingTmdbKey)
    }

    @Test
    fun eachPaneSaysWhatOfItsOwnWasLost() {
        val lost = setOf(SecretSetting.TMDB_KEY, SecretSetting.PATH_MAPPINGS)
        assertEquals(app.houselights.R.string.settings_lost_tmdb_key, lostSecretNote(Pane.Library, lost))
        assertEquals(app.houselights.R.string.settings_lost_mappings, lostSecretNote(Pane.Playback, lost))
        assertNull(lostSecretNote(Pane.Display, lost))
        assertNull(lostSecretNote(Pane.Library, setOf(SecretSetting.SOURCE)))
    }
}
