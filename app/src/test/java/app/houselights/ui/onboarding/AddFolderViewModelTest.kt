package app.houselights.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.testing.secretsOf
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException

/** The folder form on an in-memory library: tested, then saved whole even if the form is left meanwhile. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddFolderViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    /** The app's scope, for work that outlives the form: its steps run with the test's. */
    private val app = CoroutineScope(SupervisorJob() + main.dispatcher)
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = testDatabase { onCallingThread() }
        dao = db.dao()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after a save never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
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

    /** The folders' table out of reach: reading them fails as a failing database does, until [readableFolders]. */
    private fun unreadableFolders() {
        db.openHelper.writableDatabase.execSQL("ALTER TABLE sources RENAME TO sources_away")
    }

    private fun readableFolders() {
        db.openHelper.writableDatabase.execSQL("ALTER TABLE sources_away RENAME TO sources")
    }

    /** The form for [folderId] (null: a new one), as a screen holds it; [forms] clears it when the screen goes. */
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
        saved: SavedStateHandle = SavedStateHandle(),
    ): AddFolderViewModel {
        val factory = viewModelFactory {
            initializer {
                AddFolderViewModel(
                    library, folderId, app,
                    useFolders = useFolders,
                    probe = probe,
                    saved = saved,
                )
            }
        }
        return ViewModelProvider(forms, factory)[AddFolderViewModel::class.java]
    }

    /** What a new process gets of [saved]; the form's ViewModel went with the old one. */
    private fun afterAStoppedProcess(saved: SavedStateHandle): SavedStateHandle {
        forms.clear()
        return saved.afterProcessDeath()
    }

    /** After process death, the form returns as typed, over the last folder added, but never with its password. */
    @Test
    fun aFolderTypedOutlivesAStoppedProcess() = runTest {
        smbFolder()
        val saved = SavedStateHandle()
        val before = form(null, saved = saved)
        advanceUntilIdle()
        before.edit {
            copy(kind = SourceKind.SHOWS, protocol = Protocol.SMB, host = "192.168.1.30", share = "video", path = "series", user = "lea")
        }
        before.edit { copy(password = "secret") }
        assertFalse("the password is never kept", saved.keys().any { saved.get<Any?>(it) == "secret" })

        val after = form(null, saved = afterAStoppedProcess(saved))
        advanceUntilIdle()
        assertEquals(FolderForm(SourceKind.SHOWS, Protocol.SMB, "192.168.1.30", "video", "series", "lea"), after.form)
    }

    /** An edited folder returns with its typed changes; an empty password still keeps the stored one. */
    @Test
    fun aFolderEditedOutlivesAStoppedProcess() = runTest {
        val id = smbFolder()
        val saved = SavedStateHandle()
        val before = form(id, saved = saved)
        advanceUntilIdle()
        before.edit { copy(host = "192.168.1.20") }

        val after = form(id, saved = afterAStoppedProcess(saved))
        advanceUntilIdle()
        assertTrue(after.ready)
        assertEquals("192.168.1.20", after.form.host)
        assertEquals("films", after.form.path)
        assertTrue(after.keepsPassword)
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

    /** What a test said goes once a field it read changes; the kind, which no test reads, leaves it. */
    @Test
    fun whatATestSaidGoesWithTheFieldsItRead() = runTest {
        var answers = false
        val vm = form(null, probe = { if (answers) 12 else throw IOException("Connection timed out") })
        advanceUntilIdle()
        vm.test()
        assertTrue("nothing typed yet", vm.check is FolderCheck.Incomplete)
        vm.edit { copy(host = "nas-salon.local") }
        assertNull("the address asked for is typed", vm.check)

        vm.edit { copy(share = "/volume1/media") }
        vm.test()
        advanceUntilIdle()
        assertTrue(vm.check is FolderCheck.Failed)
        vm.edit { copy(kind = SourceKind.SHOWS) }
        assertTrue("the kind is not tested", vm.check is FolderCheck.Failed)
        vm.edit { copy(host = "192.168.1.20") }
        assertNull("another NAS, not tested yet", vm.check)

        answers = true
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderCheck.Reachable(12), vm.check)
        vm.edit { copy(kind = SourceKind.MOVIES) }
        assertEquals("the same folder still answers", FolderCheck.Reachable(12), vm.check)
        vm.edit { copy(path = "films") }
        assertNull(vm.check)
    }

    /** A field changed while the NAS was asked: its answer speaks of the folder the form no longer describes. */
    @Test
    fun anAnswerToFieldsChangedMeanwhileIsNotSaid() = runTest {
        var answer = CompletableDeferred<Int>()
        val vm = form(null, probe = { answer.await() })
        advanceUntilIdle()
        vm.edit { copy(host = "nas-salon.local", share = "/volume1/media") }
        vm.test()
        advanceUntilIdle()
        assertTrue(vm.testing)
        vm.edit { copy(host = "192.168.1.20") }
        answer.complete(12)
        advanceUntilIdle()
        assertFalse(vm.testing)
        assertNull("another NAS, not tested yet", vm.check)

        answer = CompletableDeferred()
        vm.test()
        advanceUntilIdle()
        vm.edit { copy(kind = SourceKind.SHOWS) }
        answer.complete(12)
        advanceUntilIdle()
        assertEquals("the kind is not tested", FolderCheck.Reachable(12), vm.check)
    }

    /** A field changed while Add tests the folder: nothing is added, and the form stays untested for Add again. */
    @Test
    fun aFolderChangedWhileAddTestsItIsNotAddedAsItWas() = runTest {
        var answer = CompletableDeferred<Int>()
        val vm = form(null, probe = { answer.await() })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "nas-salon.local", share = "/volume1/media") }
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()
        assertTrue(vm.testing)
        vm.edit { copy(host = "192.168.1.20") }
        answer.complete(12)
        advanceUntilIdle()
        assertTrue("nothing added", dao.sources().isEmpty())
        assertFalse("the form stays", done)
        assertNull("another NAS, not tested yet", vm.check)
        assertFalse(vm.busy)

        answer = CompletableDeferred()
        vm.submit { done = true }
        advanceUntilIdle()
        vm.edit { copy(kind = SourceKind.SHOWS) }
        answer.complete(12)
        advanceUntilIdle()
        assertTrue(done)
        val added = dao.sources().single()
        assertEquals("192.168.1.20", added.host)
        assertEquals(SourceKind.SHOWS, added.kind)
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

    /**
     * An edited folder the library cannot read says so, not as a save that failed, and is not saved: an empty password
     * would erase its own. Réessayer reads it again, until it is read.
     */
    @Test
    fun aFolderThatCannotBeReadSaysSoUntilReadAgain() = runTest {
        val id = smbFolder()
        unreadableFolders()
        val vm = form(id)
        advanceUntilIdle()

        assertTrue(vm.unread)
        assertFalse(vm.ready)
        assertNull(vm.check)
        vm.submit { fail("saved a folder never read") }
        vm.readAgain()
        advanceUntilIdle()
        assertTrue("still out of reach", vm.unread)
        assertEquals(emptyList<String>(), calls)

        readableFolders()
        vm.readAgain()
        assertTrue(vm.rereading)
        advanceUntilIdle()
        assertFalse(vm.unread)
        assertFalse(vm.rereading)
        assertTrue(vm.ready)
        assertEquals("192.168.1.10", vm.form.host)
        assertTrue(vm.keepsPassword)
    }

    /** After a folder inside a share, a new one starts on its NAS and share, path and password empty. */
    @Test
    fun aNewFolderStartsInTheShareOfTheLastSubfolder() = runTest {
        smbFolder()
        val vm = form(null)
        advanceUntilIdle()

        assertEquals(FolderForm(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", user = "kodi"), vm.form)
    }

    /** After a whole share, the share starts empty: the same one again could only be refused as already added. */
    @Test
    fun aNewFolderAfterAWholeShareStartsWithoutIt() = runTest {
        dao.insertSource(
            SourceEntity(
                name = "media", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.10", share = "media",
                path = "", username = "kodi", password = Secret("stored"),
            )
        )
        val vm = form(null)
        advanceUntilIdle()

        assertEquals(FolderForm(protocol = Protocol.SMB, host = "192.168.1.10", share = "", user = "kodi"), vm.form)
    }

    /** An NFS export inside which the last folder was starts as a path, with its leading slash. */
    @Test
    fun aNewFolderStartsInTheExportOfTheLastSubfolder() = runTest {
        dao.insertSource(
            SourceEntity(
                name = "films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "nas.local", share = "volume1/media",
                path = "films", username = "", password = Secret(""),
            )
        )
        val vm = form(null)
        advanceUntilIdle()

        assertEquals(FolderForm(protocol = Protocol.NFS, host = "nas.local", share = "/volume1/media"), vm.form)
    }

    /** After an NFS folder read as a user, a new one starts as that user, its row folded and saying it. */
    @Test
    fun aNewFolderStartsWithTheLastNfsIdentity() = runTest {
        nfsFolder(uid = 1026, gid = 100)
        val tested = mutableListOf<Pair<Int?, Int?>>()
        val vm = form(null, probe = { folder -> 12.also { tested += folder.uid to folder.gid } })
        advanceUntilIdle()

        val expected = FolderForm(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media", uid = "1026", gid = "100")
        assertEquals(expected, vm.form)
        assertFalse(vm.identityOpen)
        vm.edit { copy(path = "series") }
        vm.test()
        advanceUntilIdle()
        assertEquals(listOf<Pair<Int?, Int?>>(1026 to 100), tested)
    }

    /**
     * The tester's second folder: under the export the form starts with, its whole path from the NAS's root typed in
     * « Dossier ». It is read inside the export, so it is tested and added as the folder it names; over SMB the same,
     * its share in any case.
     */
    @Test
    fun aFolderPathRepeatingItsExportIsTakenFromIt() = runTest {
        nfsFolder()
        val tested = mutableListOf<String>()
        val vm = form(null, probe = { folder -> 12.also { tested += "${folder.share} ${folder.path}" } })
        advanceUntilIdle()
        assertEquals("/volume1/media", vm.form.share)
        vm.edit { copy(path = "/volume1/media/films2/") }
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertEquals(listOf("volume1/media films2"), tested)
        val added = dao.sources().maxBy { it.id }
        assertEquals(listOf("films2", "volume1/media", "films2"), listOf(added.name, added.share, added.path))
        val smb = FolderForm(protocol = Protocol.SMB, host = "192.168.1.10", share = "Video", path = "video\\Films2").toSource()
        assertEquals("Video" to "Films2", smb.share to smb.path)
    }

    /** A new folder starts empty when the last one cannot be read. */
    @Test
    fun aNewFolderStartsEmptyWhenTheLastCannotBeRead() = runTest {
        smbFolder()
        unreadableFolders()
        val vm = form(null)
        advanceUntilIdle()

        assertTrue(vm.ready)
        assertEquals("", vm.form.host)
        assertNull(vm.check)
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

    /** Test says what Add would refuse, before the network: a folder inside one the library holds, or that one again. */
    @Test
    fun aTestOfAFolderTheLibraryHoldsSaysSoBeforeAdd() = runTest {
        val id = smbFolder()
        val vm = form(null)
        advanceUntilIdle()
        vm.edit { copy(path = "films/4K") }
        vm.test()
        advanceUntilIdle()
        val inside = vm.check as FolderCheck.Overlaps
        assertEquals("films", inside.existing.name)
        assertFalse(inside.same)
        assertFalse(vm.busy)

        vm.edit { copy(path = "Films") }
        vm.test()
        advanceUntilIdle()
        assertTrue("the same folder, SMB ignoring case", (vm.check as FolderCheck.Overlaps).same)
        assertEquals("nothing asked of the NAS", emptyList<String>(), calls)

        // The folder's own form tests it on the NAS.
        forms.clear()
        val edited = form(id)
        advanceUntilIdle()
        edited.test()
        advanceUntilIdle()
        assertEquals(FolderCheck.Reachable(12), edited.check)
        assertEquals(listOf("test 192.168.1.10 with stored"), calls)
    }

    /** The storage is full when the source changes: reported in the form, never a crash. */
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

    /** The folder was added but making it the source failed: Add again makes it the source, without a test. */
    @Test
    fun aFolderAddedWhoseSourceDidNotChangeIsDoneOnTheNextAdd() = runTest {
        var full = true
        var local = false
        val vm = form(null, useFolders = {
            calls += "use folders"
            if (full) throw IOException("No space left on device")
            !local.also { local = true }
        })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.10", share = "/volume1/media", path = "films") }
        vm.submit { fail("said done") }
        advanceUntilIdle()
        assertEquals(FolderCheck.SaveFailed(UiError.NOT_SAVED), vm.check)
        assertEquals(1, dao.sources().size)

        full = false
        var switched: Boolean? = null
        vm.submit { switched = it }
        advanceUntilIdle()
        assertEquals(true, switched)
        assertEquals(listOf("test 192.168.1.10 with ", "use folders", "use folders"), calls)

        vm.submit { fail("added twice") }
        advanceUntilIdle()
        assertEquals(true, (vm.check as FolderCheck.Overlaps).same)
        assertEquals(1, dao.sources().size)
        assertFalse(vm.busy)
    }

    private suspend fun nfsFolder(uid: Int? = null, gid: Int? = null): Long = dao.insertSource(
        SourceEntity(
            name = "films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20", share = "volume1/media",
            path = "films", username = "", password = Secret(""), uid = uid, gid = gid,
        )
    )

    /** The user and group typed are what the test presents to the NAS, and what the folder keeps. */
    @Test
    fun anNfsFolderIsTestedAndAddedAsTheUserTyped() = runTest {
        val tested = mutableListOf<Pair<Int?, Int?>>()
        val vm = form(null, probe = { folder -> 12.also { tested += folder.uid to folder.gid } })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media", path = "films") }
        vm.test()
        advanceUntilIdle()
        vm.edit { copy(uid = "1026", gid = "100") }
        assertNull("another identity, not tested yet", vm.check)
        vm.submit {}
        advanceUntilIdle()
        assertEquals(listOf(null to null, 1026 to 100), tested)
        assertEquals(1026 to 100, dao.sources().single().let { it.uid to it.gid })
    }

    /** One field of two: the test asks for the other, its row opened to type it, and nothing goes to the NAS. */
    @Test
    fun anIdentityHalfTypedAsksForTheOtherField() = runTest {
        val vm = form(null)
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media", uid = "1026") }
        assertFalse(vm.identityOpen)
        vm.test()
        assertEquals(FolderCheck.Incomplete(R.string.error_gid_missing), vm.check)
        assertTrue("its fields shown", vm.identityOpen)

        vm.toggleIdentity()
        vm.edit { copy(uid = "", gid = "100") }
        vm.submit { fail("added half an identity") }
        advanceUntilIdle()
        assertEquals(FolderCheck.Incomplete(R.string.error_uid_missing), vm.check)
        assertTrue(vm.identityOpen)
        assertEquals(emptyList<String>(), calls)
    }

    /** SMB says who reads by its account: an identity typed for NFS is neither tested nor kept, nor asked for. */
    @Test
    fun anSmbFolderPresentsNoIdentity() = runTest {
        val tested = mutableListOf<Pair<Int?, Int?>>()
        val vm = form(null, probe = { folder -> 12.also { tested += folder.uid to folder.gid } })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, uid = "1026") }
        vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", user = "kodi", password = "secret") }
        vm.submit {}
        advanceUntilIdle()
        assertEquals(listOf<Pair<Int?, Int?>>(null to null), tested)
        assertEquals(null to null, dao.sources().single().let { it.uid to it.gid })
    }

    /** An edited folder shows the identity it keeps, folded; emptied, it is saved as root again. */
    @Test
    fun anEditedFolderKeepsItsIdentityUntilEmptied() = runTest {
        network.complete(Unit)
        val id = nfsFolder(uid = 1026, gid = 100)
        val vm = form(id)
        advanceUntilIdle()
        assertEquals("1026" to "100", vm.form.uid to vm.form.gid)
        assertFalse(vm.identityOpen)

        vm.edit { copy(uid = "", gid = "") }
        vm.submit {}
        advanceUntilIdle()
        assertEquals(null to null, dao.source(id)!!.let { it.uid to it.gid })
    }

    /** Digits alone, as many as a user or group number holds. */
    @Test
    fun anIdentityFieldTakesWholeNumbersOnly() {
        listOf("", "0", "1026", "2147483647").forEach { assertTrue(it, FolderForm.isId(it)) }
        listOf("-1", "+5", "10a", "1 026", "2147483648").forEach { assertFalse(it, FolderForm.isId(it)) }
    }

    /** The user and group typed, and whether their row is open, outlive a stopped process. */
    @Test
    fun anIdentityTypedOutlivesAStoppedProcess() = runTest {
        val saved = SavedStateHandle()
        val before = form(null, saved = saved)
        advanceUntilIdle()
        before.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media") }
        before.toggleIdentity()
        before.edit { copy(uid = "1026", gid = "100") }

        val after = form(null, saved = afterAStoppedProcess(saved))
        advanceUntilIdle()
        assertEquals("1026" to "100", after.form.uid to after.form.gid)
        assertTrue(after.identityOpen)
    }

    /** The Keystore refuses to seal the password: the folder is not added, and the form says why. */
    @Test
    fun aPasswordTheKeystoreCannotSealSaysWhy() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val broken = testDatabase(secretsOf { throw ProviderException("Keystore operation failed") }) { onCallingThread() }
        try {
            val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
            val idle = CoroutineScope(Job().apply { cancel() })
            val scanner = LibraryScanner(broken.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
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
