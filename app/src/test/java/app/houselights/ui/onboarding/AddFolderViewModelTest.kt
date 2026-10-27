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
import app.houselights.providers.local.FolderLevel
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
import app.houselights.testing.folderNames
import app.houselights.testing.secretsOf
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
            name = "films",
            kind = SourceKind.MOVIES,
            protocol = Protocol.SMB,
            host = "192.168.1.10",
            share = "media",
            path = "films",
            username = "kodi",
            password = Secret("stored"),
        ),
    )

    /** Without a transaction, which would wait for a thread the test does not give it. */
    private fun removeBehindTheForm(id: Long) {
        db.openHelper.writableDatabase.delete("sources", "id = ?", arrayOf<Any>(id))
    }

    /** The folders' table out of reach: reading them fails as a failing database does, until [readableFolders]. */
    private fun unreadableFolders() {
        db.openHelper.writableDatabase.execSQL("ALTER TABLE sources RENAME TO sources_away")
    }

    private fun readableFolders() {
        db.openHelper.writableDatabase.execSQL("ALTER TABLE sources_away RENAME TO sources")
    }

    /** The form for folder [folderId], as a screen holds it; [forms] clears it when the screen goes. */
    private fun form(
        folderId: Long,
        probe: suspend (SourceEntity) -> FolderLevel = { folder ->
            calls += "test ${folder.host} with ${folder.password.value}"
            FOUND
        },
        library: LocalLibrary = this.library,
        saved: SavedStateHandle = SavedStateHandle(),
    ): AddFolderViewModel {
        val factory = viewModelFactory {
            initializer {
                AddFolderViewModel(
                    library,
                    folderId,
                    app,
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

    /** After process death, the form returns as typed over the folder it edits, but never with its password. */
    @Test
    fun aFolderTypedOutlivesAStoppedProcess() = runTest {
        val id = smbFolder()
        val saved = SavedStateHandle()
        val before = form(id, saved = saved)
        advanceUntilIdle()
        before.edit { copy(host = "192.168.1.30", share = "video", path = "series", user = "lea") }
        before.edit { copy(password = "secret") }
        assertFalse("the password is never kept", saved.keys().any { saved.get<Any?>(it) == "secret" })

        val after = form(id, saved = afterAStoppedProcess(saved))
        advanceUntilIdle()
        assertEquals(FolderForm(SourceKind.MOVIES, Protocol.SMB, "192.168.1.30", "video", "series", "lea"), after.form)
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
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()

        // Tested; the library now waits for the session kept on the old address to close.
        assertTrue(vm.busy)
        assertTrue(vm.saving)
        assertFalse(done)
        vm.submit { fail("saved twice") }
        vm.test()

        network.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.busy)
        assertTrue(done)
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
        val vm = form(otherFolder(), probe = { if (answers) FOUND else throw IOException("Connection timed out") })
        advanceUntilIdle()
        vm.edit { copy(host = "") }
        vm.test()
        assertTrue("no address", vm.check is FolderCheck.Incomplete)
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
        assertEquals(REACHED, vm.check)
        vm.edit { copy(kind = SourceKind.MOVIES) }
        assertEquals("the same folder still answers", REACHED, vm.check)
        vm.edit { copy(path = "films") }
        assertNull(vm.check)
    }

    /** A field changed while the NAS was asked: its answer speaks of the folder the form no longer describes. */
    @Test
    fun anAnswerToFieldsChangedMeanwhileIsNotSaid() = runTest {
        var answer = CompletableDeferred<FolderLevel>()
        val vm = form(otherFolder(), probe = { answer.await() })
        advanceUntilIdle()
        vm.edit { copy(host = "nas-salon.local", share = "/volume1/media") }
        vm.test()
        advanceUntilIdle()
        assertTrue(vm.testing)
        vm.edit { copy(host = "192.168.1.20") }
        answer.complete(FOUND)
        advanceUntilIdle()
        assertFalse(vm.testing)
        assertNull("another NAS, not tested yet", vm.check)

        answer = CompletableDeferred()
        vm.test()
        advanceUntilIdle()
        vm.edit { copy(kind = SourceKind.SHOWS) }
        answer.complete(FOUND)
        advanceUntilIdle()
        assertEquals("the kind is not tested", REACHED, vm.check)
    }

    /** A field changed while Save tests the folder: nothing is saved, and the form stays untested for Save again. */
    @Test
    fun aFolderChangedWhileSaveTestsItIsNotSavedAsItWas() = runTest {
        network.complete(Unit)
        val id = otherFolder()
        var answer = CompletableDeferred<FolderLevel>()
        val vm = form(id, probe = { answer.await() })
        advanceUntilIdle()
        vm.edit { copy(host = "nas-salon.local", share = "/volume1/media") }
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()
        assertTrue(vm.testing)
        vm.edit { copy(host = "192.168.1.20") }
        answer.complete(FOUND)
        advanceUntilIdle()
        assertEquals("nothing saved", "192.168.1.99", dao.source(id)!!.host)
        assertFalse("the form stays", done)
        assertNull("another NAS, not tested yet", vm.check)
        assertFalse(vm.busy)

        answer = CompletableDeferred()
        vm.submit { done = true }
        advanceUntilIdle()
        answer.complete(FOUND)
        advanceUntilIdle()
        assertTrue(done)
        assertEquals("192.168.1.20" to "volume1/media", dao.source(id)!!.let { it.host to it.share })
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

    /**
     * Under the folder's export, its whole path from the NAS's root typed in « Sous-dossier »: it is read inside the
     * export, so it is tested and saved as the folder it names; over SMB the same, its share in any case.
     */
    @Test
    fun aFolderPathRepeatingItsExportIsTakenFromIt() = runTest {
        network.complete(Unit)
        val id = nfsFolder()
        val tested = mutableListOf<String>()
        val vm = form(id, probe = { folder -> FOUND.also { tested += "${folder.share} ${folder.path}" } })
        advanceUntilIdle()
        assertEquals("/volume1/media", vm.form.share)
        vm.edit { copy(path = "/volume1/media/films2/") }
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertEquals(listOf("volume1/media films2"), tested)
        val saved = dao.source(id)!!
        assertEquals(listOf("films2", "volume1/media", "films2"), listOf(saved.name, saved.share, saved.path))
        val smb = FolderForm(protocol = Protocol.SMB, host = "192.168.1.10", share = "Video", path = "video\\Films2").toSource()
        assertEquals("Video" to "Films2", smb.share to smb.path)
    }

    /**
     * A folder inside one named as its share, opened again to type its password: « Dossier » shows it from the share,
     * and it is tested and saved where it is, never moved to the share's root.
     */
    @Test
    fun aFolderInsideOneNamedAsItsShareIsSavedWhereItIs() = runTest {
        network.complete(Unit)
        val id = dao.insertSource(
            SourceEntity(
                name = "Films",
                kind = SourceKind.MOVIES,
                protocol = Protocol.SMB,
                host = "192.168.1.10",
                share = "video",
                path = "video/Films",
                username = "kodi",
                password = Secret("stored"),
            ),
        )
        val tested = mutableListOf<String>()
        val vm = form(id, probe = { folder -> FOUND.also { tested += "${folder.share} ${folder.path}" } })
        advanceUntilIdle()
        assertEquals("video/video/Films", vm.form.path)
        vm.edit { copy(password = "new") }
        var done = false
        vm.submit { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertEquals(listOf("video video/Films"), tested)
        assertEquals("video" to "video/Films", dao.source(id)!!.let { it.share to it.path })
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
        val vm = form(otherFolder())
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", path = "films/4K", user = "kodi") }
        vm.submit { fail("saved a folder the library holds") }
        advanceUntilIdle()
        val check = vm.check as FolderCheck.Overlaps
        assertEquals("films", check.existing.name)
        assertFalse(check.same)
        assertEquals(emptyList<String>(), calls)
        assertEquals("192.168.1.99", dao.sources().maxBy { it.id }.host)
        assertFalse(vm.busy)
    }

    /** Test says what Save would refuse, before the network: a folder inside another the library holds, or that one again. */
    @Test
    fun aTestOfAFolderTheLibraryHoldsSaysSoBeforeSave() = runTest {
        val id = smbFolder()
        val vm = form(otherFolder())
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", path = "films/4K", user = "kodi") }
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
        assertEquals(REACHED, edited.check)
        assertEquals(listOf("test 192.168.1.10 with stored"), calls)
    }

    /** A folder of the library on a NAS no test types, NFS and complete, so a test changes only what it is about. */
    private suspend fun otherFolder(): Long = dao.insertSource(
        SourceEntity(
            name = "archives",
            kind = SourceKind.MOVIES,
            protocol = Protocol.NFS,
            host = "192.168.1.99",
            share = "volume1/archives",
            path = "",
            username = "",
            password = Secret(""),
        ),
    )

    private suspend fun nfsFolder(uid: Int? = null, gid: Int? = null): Long = dao.insertSource(
        SourceEntity(
            name = "films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20", share = "volume1/media",
            path = "films", username = "", password = Secret(""), uid = uid, gid = gid,
        ),
    )

    /** The user and group typed are what the test presents to the NAS, and what the folder keeps. */
    @Test
    fun anNfsFolderIsTestedAndSavedAsTheUserTyped() = runTest {
        network.complete(Unit)
        val id = otherFolder()
        val tested = mutableListOf<Pair<Int?, Int?>>()
        val vm = form(id, probe = { folder -> FOUND.also { tested += folder.uid to folder.gid } })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media", path = "films") }
        vm.test()
        advanceUntilIdle()
        vm.edit { copy(uid = "1026", gid = "100") }
        assertNull("another identity, not tested yet", vm.check)
        vm.submit {}
        advanceUntilIdle()
        assertEquals(listOf(null to null, 1026 to 100), tested)
        assertEquals(1026 to 100, dao.source(id)!!.let { it.uid to it.gid })
    }

    /** One field of two: the test asks for the other, its row opened to type it, and nothing goes to the NAS. */
    @Test
    fun anIdentityHalfTypedAsksForTheOtherField() = runTest {
        val vm = form(otherFolder())
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media", uid = "1026") }
        assertFalse(vm.identityOpen)
        vm.test()
        assertEquals(FolderCheck.Incomplete(R.string.error_gid_missing), vm.check)
        assertTrue("its fields shown", vm.identityOpen)

        vm.toggleIdentity()
        vm.edit { copy(uid = "", gid = "100") }
        vm.submit { fail("saved half an identity") }
        advanceUntilIdle()
        assertEquals(FolderCheck.Incomplete(R.string.error_uid_missing), vm.check)
        assertTrue(vm.identityOpen)
        assertEquals(emptyList<String>(), calls)
    }

    /** SMB says who reads by its account: an identity typed for NFS is neither tested nor kept, nor asked for. */
    @Test
    fun anSmbFolderPresentsNoIdentity() = runTest {
        network.complete(Unit)
        val id = otherFolder()
        val tested = mutableListOf<Pair<Int?, Int?>>()
        val vm = form(id, probe = { folder -> FOUND.also { tested += folder.uid to folder.gid } })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, uid = "1026") }
        vm.edit { copy(protocol = Protocol.SMB, host = "192.168.1.10", share = "media", user = "kodi", password = "secret") }
        vm.submit {}
        advanceUntilIdle()
        assertEquals(listOf<Pair<Int?, Int?>>(null to null), tested)
        assertEquals(null to null, dao.source(id)!!.let { it.uid to it.gid })
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
        val id = otherFolder()
        val saved = SavedStateHandle()
        val before = form(id, saved = saved)
        advanceUntilIdle()
        before.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/media") }
        before.toggleIdentity()
        before.edit { copy(uid = "1026", gid = "100") }

        val after = form(id, saved = afterAStoppedProcess(saved))
        advanceUntilIdle()
        assertEquals("1026" to "100", after.form.uid to after.form.gid)
        assertTrue(after.identityOpen)
    }

    /** The Keystore refuses to seal the password: the folder is not saved, and the form says why. */
    @Test
    fun aPasswordTheKeystoreCannotSealSaysWhy() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val broken = testDatabase(secretsOf { throw ProviderException("Keystore operation failed") }) { onCallingThread() }
        try {
            val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
            val idle = CoroutineScope(Job().apply { cancel() })
            val scanner = LibraryScanner(broken.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
            val sealing = LocalLibrary(context, broken.dao(), scanner, artwork) {}
            // Without a password, nothing to seal: it is stored.
            val id = broken.dao().insertSource(
                SourceEntity(
                    name = "films",
                    kind = SourceKind.MOVIES,
                    protocol = Protocol.SMB,
                    host = "192.168.1.10",
                    share = "media",
                    path = "films",
                    username = "kodi",
                    password = Secret(""),
                ),
            )
            val vm = form(id, library = sealing)
            advanceUntilIdle()
            vm.edit { copy(host = "192.168.1.11", password = "secret") }
            vm.submit { fail("said saved") }
            advanceUntilIdle()
            assertEquals(FolderCheck.SaveFailed(UiError.KEYSTORE), vm.check)
            assertEquals("nothing saved", "192.168.1.10", broken.dao().source(id)!!.host)
        } finally {
            broken.close()
        }
    }

    private companion object {
        /** What the folder holds at its first level, as a test of it finds it. */
        val FOUND = FolderLevel(folderNames(12), videos = 3)
        val REACHED = FolderCheck.Reachable(folders = 12, videos = 3)
    }
}
