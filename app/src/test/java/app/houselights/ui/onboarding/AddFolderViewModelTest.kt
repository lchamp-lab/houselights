package app.houselights.ui.onboarding

import android.content.Context
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
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SecretConverter
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.houselights.ui.util.UiError
import java.io.IOException
import java.security.ProviderException
import javax.crypto.KeyGenerator

/**
 * The folder form, on a library in memory: a folder is tested, then saved whole, even when the form
 * is left meanwhile; a folder edited is saved once read, never once gone, and signs in again to be tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddFolderViewModelTest {
    private val main = StandardTestDispatcher()

    /** The app's scope, for work that outlives the form: its steps run with the test's. */
    private val app = CoroutineScope(SupervisorJob() + main)
    private val forms = ViewModelStore()
    private lateinit var db: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var library: LocalLibrary

    /** The library's save waits here, where it closes the session kept on the folder's old address. */
    private val network = CompletableDeferred<Unit>()

    /** Folders whose sessions the library closed once saved. */
    private val forgotten = mutableListOf<SourceEntity>()

    /** What the form asked of the network and of the settings, in order. */
    private val calls = mutableListOf<String>()

    @Before
    fun open() = runBlocking {
        Dispatchers.setMain(main)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { key }))
            .allowMainThreadQueries()
            // Queries answer on the test's own thread, so that its scheduler sees every step.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        dao = db.dao()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after a save never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() })
        library = LocalLibrary(context, dao, scanner, artwork) { folder ->
            network.await()
            forgotten += folder
        }
    }

    @After
    fun close() {
        forms.clear()
        app.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun smbFolder(): Long = dao.insertSource(
        SourceEntity(
            name = "films", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.10", share = "media",
            path = "films", username = "kodi", password = Secret("stored"),
        )
    )

    /** Without a transaction, which would wait for a thread the test does not give it. */
    private fun removeBehindTheForm(id: Long) {
        db.openHelper.writableDatabase.delete("sources", "id = ?", arrayOf(id))
    }

    /** The form of [folderId] (a new folder for null), as a screen holds it: [forms] clears it when the screen is left. */
    private fun form(
        folderId: Long?,
        probe: suspend (SourceEntity) -> Int = { folder ->
            calls += "test ${folder.host} with ${folder.password.value}"
            12
        },
        library: LocalLibrary = this.library,
        useFolders: suspend () -> Boolean = {
            calls += "use folders"
            true
        },
    ): AddFolderViewModel {
        val factory = viewModelFactory {
            initializer {
                AddFolderViewModel(
                    library, folderId, app,
                    useFolders = useFolders,
                    probe = probe,
                )
            }
        }
        return ViewModelProvider(forms, factory)[AddFolderViewModel::class.java]
    }

    @Test
    fun aFolderEditedCanBeSavedOnceRead() = runTest {
        val id = smbFolder()
        val vm = form(id)
        assertFalse("an empty password field would erase the one stored", vm.ready)
        vm.submit { fail("saved before it was read") }
        advanceUntilIdle()
        assertTrue(vm.ready)
        assertEquals("192.168.1.10", vm.form.host)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aSaveStaysBusyUntilTheLibraryHasTheFolder() = runTest {
        val id = smbFolder()
        val vm = form(id)
        advanceUntilIdle()
        var done: Boolean? = null
        vm.submit { done = it }
        advanceUntilIdle()

        // Tested; the library now waits for the session kept on the old address to close.
        assertTrue(vm.busy)
        assertTrue(vm.saving)
        assertNull(done)
        vm.submit { fail("saved twice") }
        vm.test()

        network.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.busy)
        assertEquals(false, done)
        assertEquals(listOf("test 192.168.1.10 with stored"), calls)
    }

    @Test
    fun aSaveGoesOnWhenTheFormIsLeft() = runTest {
        val id = smbFolder()
        val vm = form(id)
        advanceUntilIdle()
        vm.edit { copy(host = "192.168.1.20") }
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()

        // Back, while the library waits on the network: the form's own work stops.
        forms.clear()
        network.complete(Unit)
        advanceUntilIdle()

        assertEquals("192.168.1.20", dao.source(id)!!.host)
        assertEquals("the old session was closed, and a scan asked after it", listOf("192.168.1.10"), forgotten.map { it.host })
        assertFalse("a screen left hears nothing", done)
    }

    @Test
    fun aFolderRemovedBeforeTheFormOpensSaysSo() = runTest {
        val id = smbFolder()
        removeBehindTheForm(id)
        val vm = form(id)
        advanceUntilIdle()

        assertTrue(vm.ready)
        assertTrue(vm.gone)
        vm.submit { fail("saved a folder gone") }
        vm.test()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aFolderRemovedWhileTheFormIsOpenSaysSo() = runTest {
        network.complete(Unit)
        val id = smbFolder()
        val vm = form(id)
        advanceUntilIdle()
        removeBehindTheForm(id)

        var done = false
        vm.submit { done = true }
        advanceUntilIdle()
        assertTrue(vm.gone)
        assertFalse("the form stays, to say it", done)
        assertFalse(vm.busy)
        assertTrue(dao.sources().isEmpty())
    }

    @Test
    fun aFolderAddedLeavesTheSessionsOfOthersOpen() = runTest {
        val vm = form(null)
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", user = "kodi", password = "secret") }
        var switched: Boolean? = null
        vm.submit { switched = it }
        advanceUntilIdle()
        assertEquals(listOf("test 192.168.1.10 with secret", "use folders"), calls)
        assertEquals(true, switched)
        assertEquals(1, dao.sources().size)
    }

    @Test
    fun aFolderThatRefusesTheSignInIsNotSaved() = runTest {
        network.complete(Unit)
        val id = smbFolder()
        val vm = form(id, probe = { throw IOException("STATUS_LOGON_FAILURE") })
        advanceUntilIdle()
        vm.edit { copy(host = "192.168.1.20") }
        vm.submit { fail("saved a folder that refused") }
        advanceUntilIdle()
        assertEquals(ScanErrorKind.LOGIN_FAILED, (vm.check as FolderCheck.Failed).error.kind)
        assertEquals("192.168.1.10", dao.source(id)!!.host)
        assertFalse(vm.busy)
    }

    /** The same folder again, or one inside it: refused before the network, with the folder it would double. */
    @Test
    fun aFolderTheLibraryHoldsIsRefusedBeforeItIsTested() = runTest {
        network.complete(Unit)
        smbFolder()
        val vm = form(null)
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", path = "films/4K", user = "kodi") }
        vm.submit { fail("added a folder the library holds") }
        advanceUntilIdle()
        val check = vm.check as FolderCheck.Overlaps
        assertEquals("films", check.existing.name)
        assertFalse(check.same)
        assertEquals(emptyList<String>(), calls)
        assertEquals(1, dao.sources().size)
        assertFalse(vm.busy)
    }

    /** The storage full when the source changes: said in the form, never a crash. */
    @Test
    fun aFolderThatCannotBeSavedSaysWhy() = runTest {
        val vm = form(null, useFolders = { throw IOException("No space left on device") })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.10", share = "/volume1/media") }
        vm.submit { fail("said saved") }
        advanceUntilIdle()
        assertEquals(FolderCheck.SaveFailed(UiError.NOT_SAVED), vm.check)
        assertFalse(vm.busy)
    }

    /** The Keystore refuses to seal the password: the folder is not added, and the form says why. */
    @Test
    fun aPasswordTheKeystoreCannotSealSaysWhy() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val broken = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
            .addTypeConverter(SecretConverter(Secrets { throw ProviderException("Keystore operation failed") }))
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        try {
            val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
            val idle = CoroutineScope(Job().apply { cancel() })
            val scanner = LibraryScanner(broken.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() })
            val sealing = LocalLibrary(context, broken.dao(), scanner, artwork) {}
            val vm = form(null, library = sealing)
            advanceUntilIdle()
            vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", user = "kodi", password = "secret") }
            vm.submit { fail("said saved") }
            advanceUntilIdle()
            assertEquals(FolderCheck.SaveFailed(UiError.KEYSTORE), vm.check)
            assertTrue(broken.dao().sources().isEmpty())
        } finally {
            broken.close()
        }
    }
}
