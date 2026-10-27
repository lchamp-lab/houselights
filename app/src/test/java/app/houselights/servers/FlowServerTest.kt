package app.houselights.servers

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.ExportAccess
import app.houselights.providers.local.ExportedFolder
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.NasDiscovery
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.RemoteBrowsers
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SharedFolder
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.Volumes
import app.houselights.providers.local.exportAccess
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.ui.onboarding.FieldError
import app.houselights.ui.onboarding.FlowDone
import app.houselights.ui.onboarding.FlowKeys
import app.houselights.ui.onboarding.FlowMessage
import app.houselights.ui.onboarding.FlowNetwork
import app.houselights.ui.onboarding.FlowShare
import app.houselights.ui.onboarding.FlowStep
import app.houselights.ui.onboarding.FolderFlow
import app.houselights.ui.onboarding.RowFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.Inet4Address
import java.net.InetAddress

/**
 * « Ajouter un dossier » against the NAS of tools/test-servers (its Samba and its unfs3): the calls its steps make, and
 * the flow itself, from an address typed to a folder added, over NFS and SMB, with what the NAS refuses said where the
 * flow says it. This machine is the player, which /volume1/private does not allow.
 */
@RunWith(RobolectricTestRunner::class)
class FlowServerTest {
    private val browsers = RemoteBrowsers(Volumes.NONE) { false }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    /** The network the flow asks, as the app's (AppFlowNetwork) but for finding NAS: the test servers do not announce. */
    private val network = object : FlowNetwork {
        override fun discover(): Flow<FoundNas> = emptyFlow()

        override suspend fun protocols(host: String): Set<Protocol> = NasDiscovery.probePorts(host)

        override suspend fun shares(source: SourceEntity): List<SharedFolder> = browsers.shares(source)

        override suspend fun exports(host: String): List<ExportedFolder> = browsers.exports(host)

        override suspend fun level(source: SourceEntity, path: String): FolderLevel = browsers.of(source).folders(source, path)

        override fun player(): Inet4Address = InetAddress.getByName(PLAYER) as Inet4Address
    }

    @Before
    fun up() = TestServers.assumeUp()

    @After
    fun close() {
        browsers.closeIdle()
        scope.cancel()
        db.close()
    }

    @Test
    fun theNasAnswersBothProtocols() = runBlocking {
        assertEquals(setOf(Protocol.SMB, Protocol.NFS), network.protocols(TestServers.host))
    }

    /**
     * Its exports, each with the clients it allows: /volume1/private refuses this player, so step 4 marks it; unfs3 names
     * the clients of the others as only it resolves them (« * » as a pattern), which leaves them unmarked, not refused.
     */
    @Test
    fun theExportsAreListedWithTheClientsTheyAllow() = runBlocking {
        val exports = network.exports(TestServers.host).associateBy { it.path }
        assertTrue("${exports.keys}", exports.keys.containsAll(listOf("/volume1/video", "/volume1/media", "/volume1/private")))
        val player = network.player()
        val video = exports.getValue("/volume1/video").groups
        assertNotEquals("$video", ExportAccess.REFUSED, exportAccess(video, player))
        assertEquals(ExportAccess.REFUSED, exportAccess(exports.getValue("/volume1/private").groups, player))
    }

    /** The shares the account opens, the administrative one left out; listing them is the account's test. */
    @Test
    fun theSharesAreListedForTheAccount() = runBlocking {
        val names = network.shares(smb()).map { it.name }
        assertTrue("$names", names.containsAll(listOf("media", "photos", "public", "video")))
        assertFalse("$names", names.any { it.endsWith("$") })
    }

    /** A level as step 5 shows it, over each protocol: its folders, what the NAS hides left out, and its own videos. */
    @Test
    fun aLevelIsWhatStep5Shows() = runBlocking {
        listOf(nfs(), smb()).forEach { source ->
            assertEquals("${source.protocol}", FolderLevel(listOf("Films", "Séries"), 0), network.level(source, ""))
            val films = network.level(source, "Films")
            assertEquals("${source.protocol}: ${films.folders}", Samples.FILMS, films.folders.size to films.videos)
        }
    }

    /**
     * NFS from an address typed: both protocols ask which, the exports listed, /volume1/private marked and refused when
     * opened, then /volume1/video opened, Films chosen and added as films.
     */
    @Test
    fun aFolderIsAddedOverNfs() {
        val flow = flow()
        flow.typeAddress()
        flow.type(FlowKeys.FIELD_HOST, TestServers.host)
        flow.continueAddress()
        until("the protocols") { flow.step == FlowStep.PROTOCOLS }
        flow.chooseProtocol(Protocol.NFS)
        until("the exports") { flow.step == FlowStep.SHARES }
        val closed = flow.shares.single { it.path == "/volume1/private" }
        assertTrue("marked before it is opened", closed.refused)
        flow.openShare(closed)
        until("the refusal") { flow.message != null }
        assertEquals(RowFailure.NOT_ALLOWED, flow.rowFailures[FlowKeys.shareKey(closed)])
        assertEquals(FlowMessage.NotAllowed(PLAYER, "/volume1/private"), flow.message)
        flow.openShare(flow.shares.single { it.path == "/volume1/video" })
        until("the export's root") { flow.step == FlowStep.BROWSE }
        assertEquals(listOf("Films", "Séries"), flow.level?.folders)
        flow.openFolder("Films")
        until("Films") { flow.path == listOf("Films") }
        flow.chooseFolder()
        assertEquals(FlowStep.KIND, flow.step)
        val done = add(flow, SourceKind.MOVIES)
        val added = runBlocking { db.dao().sources() }.single()
        assertEquals(listOf(Protocol.NFS, "volume1/video", "Films"), listOf(added.protocol, added.share, added.path))
        assertEquals(added.id, done.folderId)
    }

    /**
     * SMB: a password refused said under it, then the account signing in, its shares listed, a guest refused a share of
     * the account; « video » opened and Séries added as shows, with the account.
     */
    @Test
    fun aFolderIsAddedOverSmb() {
        val flow = flow()
        flow.typeAddress()
        flow.type(FlowKeys.FIELD_HOST, TestServers.host)
        flow.continueAddress()
        until("the protocols") { flow.step == FlowStep.PROTOCOLS }
        flow.chooseProtocol(Protocol.SMB)
        until("the account") { flow.step == FlowStep.ACCOUNT }
        flow.type(FlowKeys.FIELD_USER, TestServers.USER)
        flow.type(FlowKeys.FIELD_PASSWORD, "wrong")
        flow.signIn()
        until("the refusal") { flow.fieldError != null }
        assertEquals(FieldError(FlowKeys.FIELD_PASSWORD, FlowMessage.LoginRefused), flow.fieldError)
        flow.type(FlowKeys.FIELD_PASSWORD, TestServers.PASSWORD)
        flow.signIn()
        until("the shares") { flow.step == FlowStep.SHARES }
        assertTrue("${flow.shares}", flow.shares.map { it.path }.containsAll(listOf("media", "photos", "public", "video")))
        flow.openShare(FlowShare("video"))
        until("the share's root") { flow.step == FlowStep.BROWSE }
        flow.openFolder("Séries")
        until("Séries") { flow.path == listOf("Séries") }
        flow.chooseFolder()
        add(flow, SourceKind.SHOWS)
        val added = runBlocking { db.dao().sources() }.single()
        val where = listOf(added.protocol, added.share, added.path, added.username)
        assertEquals(listOf(Protocol.SMB, "video", "Séries", TestServers.USER), where)
        assertEquals(TestServers.PASSWORD, added.password.value)
    }

    /**
     * A guest is refused the account's share, said as a guest's, which sends to « Changer de compte »: on the shares'
     * row when the NAS lists them to a guest, else under the path typed; the guests' share opens.
     */
    @Test
    fun aGuestIsRefusedTheAccountsShare() {
        val flow = flow()
        flow.typeAddress()
        flow.type(FlowKeys.FIELD_HOST, TestServers.host)
        flow.continueAddress()
        until("the protocols") { flow.step == FlowStep.PROTOCOLS }
        flow.chooseProtocol(Protocol.SMB)
        until("the account") { flow.step == FlowStep.ACCOUNT }
        flow.signIn(asGuest = true)
        until("the shares or the path") { flow.step == FlowStep.SHARES || flow.step == FlowStep.TYPE_PATH }
        if (flow.step == FlowStep.SHARES) {
            flow.openShare(FlowShare("video"))
            until("the refusal") { flow.message != null }
            assertEquals(FlowMessage.GuestShare("video"), flow.message)
            flow.openShare(FlowShare("public"))
        } else {
            flow.type(FlowKeys.FIELD_PATH, "video")
            flow.continuePath()
            until("the refusal") { flow.fieldError != null }
            assertEquals(FieldError(FlowKeys.FIELD_PATH, FlowMessage.GuestShare("video")), flow.fieldError)
            flow.type(FlowKeys.FIELD_PATH, "public")
            flow.continuePath()
        }
        until("the guests' share") { flow.step == FlowStep.BROWSE }
    }

    private fun flow(): FolderFlow {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after an add never runs: these tests are about the flow.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        return FolderFlow(network, library, scope, useFolders = { false }).also { flow -> until("the flow") { flow.ready } }
    }

    /** Adds the folder chosen as [kind], OK on its row; how the flow ended. */
    private fun add(flow: FolderFlow, kind: SourceKind): FlowDone {
        var done: FlowDone? = null
        flow.add(kind) { done = it }
        until("the folder added") { done != null }
        return checkNotNull(done)
    }

    /** Runs the main looper, where the flow lives, until [done]: the servers answer on threads of their own. */
    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "Waited for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun nfs() = SourceEntity(
        name = "Test",
        kind = SourceKind.MOVIES,
        protocol = Protocol.NFS,
        host = TestServers.host,
        share = "volume1/video",
        path = "",
        username = "",
        password = Secret(""),
    )

    private fun smb() = SourceEntity(
        name = "Test",
        kind = SourceKind.MOVIES,
        protocol = Protocol.SMB,
        host = TestServers.host,
        share = "video",
        path = "",
        username = TestServers.USER,
        password = Secret(TestServers.PASSWORD),
    )

    private companion object {
        /** The player: this machine, as the servers see it. */
        const val PLAYER = "127.0.0.1"
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 20L
    }
}
