package app.houselights.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.testing.folderNames
import app.houselights.testing.mountFailure
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.afterProcessDeath
import com.emc.ecs.nfsclient.mount.MountStatus
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * « Modifier le dossier »'s model and the NAS's shared folders (#59): a mount path the NAS lacks, nothing near it, offers
 * them once the test read their list, the one of a NAS sharing one proposed; « Choisir un dossier partagé » opens the
 * guided flow's step 4 on them, and the folder chosen there is tested, the subfolder kept, nothing saved; the failure,
 * its list and the way back outlive a stopped process. The form's other answers: [AddFolderViewModelTest],
 * [FolderAnswerTest].
 */
@RunWith(RobolectricTestRunner::class)
class AddFolderSharesTest {
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

    /** What the form asked of the NAS, in order. */
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
        library = LocalLibrary(context, dao, scanner, artwork) {}
    }

    @After
    fun close() {
        forms.clear()
        app.cancel()
        db.close()
    }

    /** The form for folder [folderId], its test answering [probe], the NAS listing [exports] and proposing [nearby]. */
    private fun form(
        folderId: Long,
        probe: suspend (SourceEntity) -> FolderLevel = synology,
        saved: SavedStateHandle = SavedStateHandle(),
        exports: suspend (host: String) -> List<ExportedFolder>? = { null },
        nearby: suspend (SourceEntity, FolderTrouble) -> FolderSuggestion? = { _, _ -> null },
    ): AddFolderViewModel {
        val factory = viewModelFactory {
            initializer {
                AddFolderViewModel(
                    library,
                    folderId,
                    app,
                    probe = probe,
                    saved = saved,
                    playerAddress = { PLAYER },
                    exports = exports,
                    nearby = nearby,
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

    /** « Films », over NFS from the old QNAP's /share/Multimedia, at the address the new Synology took. */
    private suspend fun qnapFolder(): Long = dao.insertSource(
        SourceEntity(
            name = "Films",
            kind = SourceKind.MOVIES,
            protocol = Protocol.NFS,
            host = DS920,
            share = "share/Multimedia",
            path = "Films",
            username = "",
            password = Secret(""),
        ),
    )

    /** The Synology's test: what it shares opens, anything else refused as Linux's mountd refuses a path not exported. */
    private val synology: suspend (SourceEntity) -> FolderLevel = { folder ->
        calls += "test ${folder.share} ${folder.path}"
        if (EXPORTS.none { it.path.trim('/') == folder.share }) throw mountFailure(MountStatus.MNT3ERR_ACCES)
        FOUND
    }

    /** The failure the last test said, as it says it. */
    private fun AddFolderViewModel.trouble() = (check as FolderCheck.Failed).trouble

    /**
     * A mount path the NAS does not share, nothing near it, the NAS's list read (#59): « Choisir un dossier partagé »
     * on the folders it shares, « Non autorisé » included, its list read once for the test; never with a proposal. A
     * field changed takes it away.
     */
    @Test
    fun nothingNearAnExportTheNasLacksOffersItsSharedFolders() = runTest {
        var listings = 0
        val vm = form(
            qnapFolder(),
            probe = synology,
            exports = {
                listings++
                EXPORTS
            },
        )
        advanceUntilIdle()
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.ExportMissing("/share/Multimedia"), vm.trouble())
        assertEquals(EXPORTS, vm.shared)
        assertNull(vm.suggestion)
        assertEquals("the list read once", 1, listings)
        assertEquals(FaultSpot.SHARE, spotOf(vm.check, vm.form))

        vm.edit { copy(share = "/share/Video") }
        assertNull(vm.shared)
    }

    /** A NAS sharing one folder proposes it, « Le seul dossier partagé du NAS »: OK writes it, the subfolder kept, and tests. */
    @Test
    fun aNasSharingOneFolderProposesIt() = runTest {
        val vm = form(qnapFolder(), probe = synology, exports = { EXPORTS.take(1) })
        advanceUntilIdle()
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderSuggestion("volume1/video", "Films", only = true), vm.suggestion)
        assertNull("one row, never a list of one", vm.shared)

        vm.useSuggestion()
        advanceUntilIdle()
        assertEquals("/volume1/video" to "Films", vm.form.share to vm.form.path)
        assertEquals(REACHED, vm.check)
    }

    /** What the NAS proposes near the path typed is the one row: no list beside it. */
    @Test
    fun somethingNearIsProposedAlone() = runTest {
        val near = FolderSuggestion("volume1/video", "Films")
        val vm = form(qnapFolder(), probe = synology, exports = { EXPORTS }, nearby = { _, _ -> near })
        advanceUntilIdle()
        vm.test()
        advanceUntilIdle()
        assertEquals(near, vm.suggestion)
        assertNull(vm.shared)
    }

    /**
     * Only a test that had the NAS's list offers it: without it, or with none listed, the message alone; a path missing
     * the NAS says so of (NOENT) offers the list read then.
     */
    @Test
    fun withoutTheNasListThereIsNothingToChoose() = runTest {
        var listed: List<ExportedFolder>? = null
        var status = MountStatus.MNT3ERR_ACCES
        val vm = form(qnapFolder(), probe = { throw mountFailure(status) }, exports = { listed })
        advanceUntilIdle()
        vm.test()
        advanceUntilIdle()
        assertEquals("unlisted, the player refused", FolderTrouble.PlayerRefused("/share/Multimedia", PLAYER), vm.trouble())
        assertNull(vm.shared)

        status = MountStatus.MNT3ERR_NOENT
        listOf(null, emptyList<ExportedFolder>()).forEach {
            listed = it
            vm.test()
            advanceUntilIdle()
            assertEquals(FolderTrouble.ExportMissing("/share/Multimedia"), vm.trouble())
            assertNull("$it", vm.shared)
            assertNull("$it", vm.suggestion)
        }
        listed = EXPORTS
        vm.test()
        advanceUntilIdle()
        assertEquals(EXPORTS, vm.shared)
    }

    /** A folder the NAS lists that refuses this player is the panel's refusal, as ever: nothing to choose. */
    @Test
    fun aListedExportRefusingThisPlayerOffersNothing() = runTest {
        val vm = form(qnapFolder(), probe = { throw mountFailure(MountStatus.MNT3ERR_ACCES) }, exports = { EXPORTS })
        advanceUntilIdle()
        vm.edit { copy(share = "/volume1/photo") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.PlayerRefused("/volume1/photo", PLAYER), vm.trouble())
        assertNull(vm.shared)
        assertEquals(FaultSpot.PANEL, spotOf(vm.check, vm.form))
    }

    /**
     * « Choisir un dossier partagé » opens the flow's step 4 on the list read, with the NAS and the NFS account typed; the
     * folder chosen there is written in « Chemin de montage », « Films » kept, and tested at once; nothing is saved.
     */
    @Test
    fun aSharedFolderChosenIsTestedItsSubfolderKept() = runTest {
        val id = qnapFolder()
        val vm = form(id, probe = synology, exports = { EXPORTS })
        advanceUntilIdle()
        vm.toggleIdentity()
        vm.edit { copy(uid = "1026", gid = "100") }
        vm.test()
        advanceUntilIdle()

        assertEquals(SharePick(id, DS920, EXPORTS, "1026", "100"), vm.openPick())
        assertTrue(vm.away)
        vm.pick("/volume1/video")
        advanceUntilIdle()
        assertFalse(vm.away)
        assertEquals("/volume1/video" to "Films", vm.form.share to vm.form.path)
        assertEquals(REACHED, vm.check)
        assertNull(vm.shared)
        assertEquals(listOf("test share/Multimedia Films", "test volume1/video Films"), calls.filter { it.startsWith("test") })
        assertEquals("nothing saved", "share/Multimedia", dao.source(id)!!.share)
    }

    /** Back from the step with nothing chosen: the form as it was, its failure and its line. */
    @Test
    fun backFromTheStepLeavesTheFormAsItWas() = runTest {
        val vm = form(qnapFolder(), probe = synology, exports = { EXPORTS })
        advanceUntilIdle()
        vm.test()
        advanceUntilIdle()
        val failed = vm.check
        vm.openPick()

        vm.pickLeft()
        assertFalse(vm.away)
        assertEquals(failed, vm.check)
        assertEquals(EXPORTS, vm.shared)
    }

    /** A process stopped while the step is open: the form comes back with its failure, its line and its way back. */
    @Test
    fun theFailureAndItsSharedFoldersOutliveAStoppedProcess() = runTest {
        val id = qnapFolder()
        val saved = SavedStateHandle()
        val before = form(id, probe = synology, exports = { EXPORTS }, saved = saved)
        advanceUntilIdle()
        before.test()
        advanceUntilIdle()
        before.openPick()

        val after = form(id, probe = synology, saved = afterAStoppedProcess(saved))
        advanceUntilIdle()
        assertEquals(FolderTrouble.ExportMissing("/share/Multimedia"), after.trouble())
        assertEquals(EXPORTS, after.shared)
        assertTrue(after.away)
        assertEquals(SharePick(id, DS920, EXPORTS), after.openPick())
    }

    private companion object {
        /** What the folder holds at its first level, as a test of it finds it. */
        val FOUND = FolderLevel(folderNames(12), videos = 3)
        val REACHED = FolderCheck.Reachable(folders = 12, videos = 3)

        const val DS920 = "192.168.1.20"
        const val PLAYER = "192.168.1.42"

        /** What the Synology exports over NFS: video and musique to the home network, photo to one computer. */
        val EXPORTS = listOf(
            ExportedFolder("/volume1/video", listOf("192.168.1.0/24")),
            ExportedFolder("/volume1/musique", listOf("192.168.1.0/24")),
            ExportedFolder("/volume1/photo", listOf("192.168.1.10")),
        )
    }
}
