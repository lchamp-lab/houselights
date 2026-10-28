package app.houselights.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.FailurePlace
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanError
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.testing.folderNames
import app.houselights.testing.mountFailure
import app.houselights.testing.smbStatus
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.util.UiError
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.hierynomus.mserref.NtStatus
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
import java.io.FileNotFoundException
import java.net.SocketTimeoutException

/**
 * What the folder form says of each answer to a press, and where: what a test found, folders and videos apart, a folder
 * holding nothing, each failure as the NAS said it under the field that changes it, an overlap the right way round.
 */
@RunWith(RobolectricTestRunner::class)
class FolderAnswerTest {
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

    /** What the form asked of the network, in order. */
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

    /**
     * The form of a folder of the library, SMB's « autres » in media on 192.168.1.10, its test answering [probe], this
     * player at [player], the NFS server listing [exports], the NAS proposing [nearby] for a path it lacks.
     */
    private suspend fun form(
        probe: suspend (SourceEntity) -> FolderLevel = { folder ->
            calls += "test ${folder.host}"
            FolderLevel(folderNames(12), videos = 3)
        },
        player: String? = null,
        exports: suspend (host: String) -> List<ExportedFolder>? = { null },
        nearby: suspend (SourceEntity, FolderTrouble) -> FolderSuggestion? = { _, _ -> null },
    ): AddFolderViewModel {
        val id = dao.insertSource(EDITED)
        val factory = viewModelFactory {
            initializer {
                AddFolderViewModel(
                    library,
                    id,
                    app,
                    probe = probe,
                    playerAddress = { player },
                    exports = exports,
                    nearby = nearby,
                )
            }
        }
        return ViewModelProvider(forms, factory)[AddFolderViewModel::class.java]
    }

    /** What the folder holds is said, folders and videos apart; a folder holding neither is said so and never saved. */
    @Test
    fun aTestSaysWhatTheFolderHoldsAndAnEmptyOneIsNotSaved() = runTest {
        var found = FolderLevel(emptyList(), videos = 0)
        val vm = form(probe = { found })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/video", path = "Films") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderCheck.Empty, vm.check)
        vm.submit { fail("saved a folder holding nothing") }
        advanceUntilIdle()
        assertEquals(FolderCheck.Empty, vm.check)
        assertEquals("nothing saved", listOf("192.168.1.10"), dao.sources().map { it.host })
        assertEquals("said under the subfolder typed", FaultSpot.SUBFOLDER, spotOf(vm.check, vm.form))

        found = FolderLevel(folderNames(412), videos = 3)
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderCheck.Reachable(folders = 412, videos = 3), vm.check)
        assertNull("a success has no field at fault", spotOf(vm.check, vm.form))
    }

    /**
     * Each failure says what to change, as the NAS said it: the export or the share missing, or a folder missing inside
     * one that answers; the mount refused to this player, by its address; a read refused to root; an SMB account
     * without the right, a guest refused, a password refused; the NAS silent.
     */
    @Test
    fun eachFailureSaysWhatToChange() {
        val nfs = SourceEntity(
            name = "Films",
            kind = SourceKind.MOVIES,
            protocol = Protocol.NFS,
            host = "192.168.1.20",
            share = "volume1/video",
            path = "Films",
            username = "",
            password = Secret(""),
        )
        val smb = nfs.copy(protocol = Protocol.SMB, share = "video", username = "houselights")
        fun trouble(e: Throwable, folder: SourceEntity, player: String? = "192.168.1.42") =
            troubleOf(ScanErrors.classify(e), folder, player)

        assertEquals(FolderTrouble.ExportMissing("/volume1/video"), trouble(mountFailure(MountStatus.MNT3ERR_NOENT), nfs))
        assertEquals(
            FolderTrouble.SubfolderMissing("/volume1/video", "Films", nfs = true),
            trouble(FileNotFoundException("lookup /volume1/video/Films"), nfs),
        )
        assertEquals(FolderTrouble.PlayerRefused("/volume1/video", "192.168.1.42"), trouble(mountFailure(MountStatus.MNT3ERR_ACCES), nfs))
        assertEquals(
            "without an address",
            FolderTrouble.PlayerRefused("/volume1/video", null),
            trouble(mountFailure(MountStatus.MNT3ERR_ACCES), nfs, player = null),
        )
        val readRefused = NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13")
        assertEquals(FolderTrouble.ReadRefused("/volume1/video/Films", uid = null), trouble(readRefused, nfs))
        assertEquals(FolderTrouble.ReadRefused("/volume1/video/Films", uid = 1026), trouble(readRefused, nfs.copy(uid = 1026, gid = 100)))
        val root = nfs.copy(share = "", path = "")
        assertEquals("the NAS's root exported", FolderTrouble.ReadRefused("/", uid = null), trouble(readRefused, root))
        assertEquals(FolderTrouble.ReadRefused("/Films", uid = null), trouble(readRefused, root.copy(path = "Films")))

        assertEquals(FolderTrouble.ShareMissing("video"), trouble(smbStatus(NtStatus.STATUS_BAD_NETWORK_NAME), smb))
        assertEquals(
            FolderTrouble.SubfolderMissing("video", "Films", nfs = false),
            trouble(smbStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND), smb),
        )
        assertEquals(FolderTrouble.AccountDenied("houselights", "video"), trouble(smbStatus(NtStatus.STATUS_ACCESS_DENIED), smb))
        assertEquals(FolderTrouble.GuestRefused, trouble(smbStatus(NtStatus.STATUS_LOGON_FAILURE), smb.copy(username = "")))
        assertEquals(FolderTrouble.GuestRefused, trouble(smbStatus(NtStatus.STATUS_ACCESS_DENIED), smb.copy(username = "")))
        assertEquals(FolderTrouble.LoginFailed, trouble(smbStatus(NtStatus.STATUS_LOGON_FAILURE), smb))
        assertEquals(FolderTrouble.Unreachable, trouble(SocketTimeoutException(), smb))
        assertEquals("a place the NAS does not say", FolderTrouble.Missing, troubleOf(ScanError(ScanErrorKind.NOT_FOUND), smb, null))
        assertEquals(FailurePlace.SHARE, ScanErrors.classify(smbStatus(NtStatus.STATUS_BAD_NETWORK_NAME)).place)
    }

    /**
     * Each failure is said under the field that changes it, else in the panel: the NAS silent under its address, a
     * missing export under « Chemin de montage », a missing folder under « Sous-dossier », an account under its user, a
     * password refused under it; a refusal no field fixes, and a folder not saved, in the panel.
     */
    @Test
    fun eachFailureIsSaidUnderTheFieldThatChangesIt() {
        val nfs = FolderForm(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/video", path = "Films")
        fun failed(trouble: FolderTrouble) = FolderCheck.Failed(ScanError(ScanErrorKind.OTHER), "192.168.1.20", trouble)
        mapOf(
            failed(FolderTrouble.Unreachable) to FaultSpot.HOST,
            failed(FolderTrouble.ExportMissing("/volume1/video")) to FaultSpot.SHARE,
            failed(FolderTrouble.SubfolderMissing("/volume1/video", "Films", nfs = true)) to FaultSpot.SUBFOLDER,
            failed(FolderTrouble.AccountDenied("houselights", "video")) to FaultSpot.USER,
            failed(FolderTrouble.GuestRefused) to FaultSpot.USER,
            failed(FolderTrouble.LoginFailed) to FaultSpot.PASSWORD,
            failed(FolderTrouble.PlayerRefused("/volume1/video", "192.168.1.42")) to FaultSpot.PANEL,
            failed(FolderTrouble.ReadRefused("/volume1/video/Films", null)) to FaultSpot.PANEL,
            FolderCheck.Incomplete(R.string.error_host_missing) to FaultSpot.HOST,
            FolderCheck.Incomplete(R.string.error_export_missing) to FaultSpot.SHARE,
            FolderCheck.Incomplete(R.string.error_gid_missing) to FaultSpot.GID,
            FolderCheck.SaveFailed(UiError.NOT_SAVED) to FaultSpot.PANEL,
        ).forEach { (check, spot) -> assertEquals("$check", spot, spotOf(check, nfs)) }
        // A folder split from « Dossier partagé » alone, its subfolder field empty: said under the one typed.
        val typedWhole = FolderForm(protocol = Protocol.SMB, host = "192.168.1.20", share = "video/Films")
        assertEquals(FaultSpot.SHARE, spotOf(failed(FolderTrouble.SubfolderMissing("video", "Films", nfs = false)), typedWhole))
    }

    /**
     * An overlap is said the right way round: the folder again, already in the library; one holding a folder of the
     * library, under « Sous-dossier » even empty, where a subfolder is to be chosen; one inside it, under the path's last
     * field. Testing says it as saving does.
     */
    @Test
    fun anOverlapIsSaidTheRightWayRound() = runTest {
        smbFolder()
        val vm = form()
        advanceUntilIdle()
        vm.edit { copy(path = "") }
        vm.test()
        advanceUntilIdle()
        val holding = vm.check as FolderCheck.Overlaps
        assertTrue("media holds films", holding.holds && !holding.same)
        assertEquals(FaultSpot.SUBFOLDER, spotOf(holding, vm.form))

        vm.edit { copy(path = "films/4K") }
        vm.submit { fail("added a folder inside one of the library") }
        advanceUntilIdle()
        val inside = vm.check as FolderCheck.Overlaps
        assertFalse(inside.holds || inside.same)
        assertEquals(FaultSpot.SUBFOLDER, spotOf(inside, vm.form))
        vm.edit { copy(share = "media/films/4K", path = "") }
        vm.test()
        advanceUntilIdle()
        assertEquals("typed in the share's field alone", FaultSpot.SHARE, spotOf(vm.check, vm.form))
        assertEquals(emptyList<String>(), calls)
    }

    /** Each answer to a press is counted, the same failure again included, so focus goes back to its field each time. */
    @Test
    fun theSameFailureSaidAgainIsAnotherAnswer() = runTest {
        val vm = form()
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "") }
        vm.test()
        vm.submit { fail("saved without an address") }
        advanceUntilIdle()
        assertEquals(FolderCheck.Incomplete(R.string.error_host_missing), vm.check)
        assertEquals(2, vm.answers)
        vm.edit { copy(host = "192.168.1.20") }
        assertEquals("a change clears the answer without counting", 2, vm.answers)
    }

    /** NFS refusing this player names its address, read from the network, and a read refused to root opens the account's row. */
    @Test
    fun aRefusalSaysWhatToAllowAndToWhom() = runTest {
        var refusal: Exception = mountFailure(MountStatus.MNT3ERR_ACCES)
        val vm = form(probe = { throw refusal }, player = "192.168.1.42")
        advanceUntilIdle()
        assertEquals("192.168.1.42", vm.player)
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/video", path = "Films") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.PlayerRefused("/volume1/video", "192.168.1.42"), (vm.check as FolderCheck.Failed).trouble)
        assertFalse(vm.identityOpen)

        refusal = NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13")
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.ReadRefused("/volume1/video/Films", null), (vm.check as FolderCheck.Failed).trouble)
        assertTrue("the account to change, its row open", vm.identityOpen)
    }

    /**
     * A mount refused for an export the NAS does not list is an export it lacks, as a Synology refuses « /media » for
     * « /volume1/media » (#59): said under « Chemin de montage », never as the player to allow; one it lists, or inside
     * one it lists, is the player refused.
     */
    @Test
    fun aMountRefusedForAnExportTheNasLacksIsTheExportMissing() = runTest {
        val vm = form(
            probe = { throw mountFailure(MountStatus.MNT3ERR_ACCES) },
            player = "192.168.1.42",
            exports = { listOf("/volume1/video", "/volume1/media").map { path -> ExportedFolder(path, emptyList()) } },
        )
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/media", path = "") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.ExportMissing("/media"), (vm.check as FolderCheck.Failed).trouble)
        assertEquals(FaultSpot.SHARE, spotOf(vm.check, vm.form))

        vm.edit { copy(share = "/volume1/media") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.PlayerRefused("/volume1/media", "192.168.1.42"), (vm.check as FolderCheck.Failed).trouble)
        vm.edit { copy(share = "/volume1/video/Films") }
        vm.test()
        advanceUntilIdle()
        val inside = (vm.check as FolderCheck.Failed).trouble
        assertEquals("inside an export", FolderTrouble.PlayerRefused("/volume1/video/Films", "192.168.1.42"), inside)
    }

    /**
     * Once the NAS answered a test, the path's fields show the folder as it was read: a whole path typed in
     * « Sous-dossier » under its « Chemin de montage » shows the subfolder alone, an SMB « Dossier partagé » typed with
     * its NAS and subfolder shows them apart, a subfolder missing included; the answer stays, and a test again reads the
     * same folder.
     */
    @Test
    fun afterATestTheFieldsShowThePathAsRead() = runTest {
        val tested = mutableListOf<String>()
        var missing = false
        val vm = form(probe = { folder ->
            tested += "${folder.share} ${folder.path}"
            if (missing) throw smbStatus(NtStatus.STATUS_OBJECT_NAME_NOT_FOUND)
            FolderLevel(folderNames(412), videos = 3)
        })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/volume1/video", path = "/volume1/video/Films") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderCheck.Reachable(folders = 412, videos = 3), vm.check)
        assertEquals("/volume1/video" to "Films", vm.form.share to vm.form.path)
        vm.test()
        advanceUntilIdle()

        missing = true
        vm.edit { copy(protocol = Protocol.SMB, share = "\\\\192.168.1.20\\video\\Film", path = "") }
        vm.test()
        advanceUntilIdle()
        assertEquals(FolderTrouble.SubfolderMissing("video", "Film", nfs = false), (vm.check as FolderCheck.Failed).trouble)
        assertEquals("video" to "Film", vm.form.share to vm.form.path)
        assertEquals("said under the subfolder it shows", FaultSpot.SUBFOLDER, spotOf(vm.check, vm.form))
        assertEquals(listOf("volume1/video Films", "volume1/video Films", "video Film"), tested)
    }

    /**
     * A field changed while the NAS is asked which paths it exports, to tell a mount refused: the answer speaks of the
     * folder the form no longer describes, so nothing is said, nor does focus leave the field being typed.
     */
    @Test
    fun aRefusalToFieldsChangedWhileTheExportsAreListedIsNotSaid() = runTest {
        val listed = CompletableDeferred<List<ExportedFolder>?>()
        val vm = form(probe = { throw mountFailure(MountStatus.MNT3ERR_ACCES) }, player = "192.168.1.42", exports = { listed.await() })
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/media", path = "") }
        vm.test()
        advanceUntilIdle()
        assertTrue("the exports still asked", vm.testing)
        vm.edit { copy(share = "/volume1/media") }
        listed.complete(listOf("/volume1/video", "/volume1/media").map { ExportedFolder(it, emptyList()) })
        advanceUntilIdle()
        assertFalse(vm.testing)
        assertNull("another export, not tested yet", vm.check)
        assertEquals("no answer, focus left where it is", 0, vm.answers)
        assertEquals("/volume1/media", vm.form.share)
    }

    /**
     * A path the NAS lacks comes with what it proposes, asked once the test failed: OK on « Utiliser … » writes it in the
     * path's fields, as the form shows a folder, and tests again; a field changed meanwhile takes it away.
     */
    @Test
    fun aPathTheNasLacksComesWithWhatItProposes() = runTest {
        val tested = mutableListOf<String>()
        val asked = mutableListOf<FolderTrouble>()
        val vm = form(
            probe = { folder ->
                tested += "${folder.share} ${folder.path}"
                if (folder.share != "volume1/media") throw mountFailure(MountStatus.MNT3ERR_NOENT)
                FolderLevel(folderNames(2), videos = 4)
            },
            nearby = { _, trouble -> FolderSuggestion("volume1/media", "Films").also { asked += trouble } },
        )
        advanceUntilIdle()
        vm.edit { copy(protocol = Protocol.NFS, host = "192.168.1.20", share = "/media", path = "Films") }
        vm.test()
        advanceUntilIdle()
        assertEquals(listOf<FolderTrouble>(FolderTrouble.ExportMissing("/media")), asked)
        assertEquals(FolderSuggestion("volume1/media", "Films"), vm.suggestion)
        val said = vm.suggestion?.export to vm.suggestion?.named
        assertEquals("« Utiliser /volume1/media », the export alone", "/volume1/media" to null, said)

        vm.useSuggestion()
        advanceUntilIdle()
        assertEquals("/volume1/media" to "Films", vm.form.share to vm.form.path)
        assertEquals(FolderCheck.Reachable(folders = 2, videos = 4), vm.check)
        assertNull("nothing proposed for a folder found", vm.suggestion)
        assertEquals(listOf("media Films", "volume1/media Films"), tested)

        vm.edit { copy(share = "/médias") }
        vm.test()
        advanceUntilIdle()
        assertTrue(vm.suggestion != null)
        vm.edit { copy(path = "Séries") }
        assertNull("another path, not tested yet", vm.suggestion)
    }

    private companion object {
        /** The folder each test's form edits, on a NAS and in a folder no test types. */
        val EDITED = SourceEntity(
            name = "autres",
            kind = SourceKind.MOVIES,
            protocol = Protocol.SMB,
            host = "192.168.1.10",
            share = "media",
            path = "autres",
            username = "kodi",
            password = Secret("stored"),
        )
    }
}
