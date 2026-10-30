package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.Credential
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import com.emc.ecs.nfsclient.rpc.Xdr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Which NFS failures, as nfs-client 1.1 raises them, remount the export, what waits for a mount, and who mounts it. */
class NfsBrowserTest {
    /**
     * A folder presents its user and group to the NAS, root when it has none, as AUTH_SYS writes them on the wire: for
     * its listing, its test and the reads of its NFO, artwork and headers.
     */
    @Test
    fun aFolderPresentsItsIdentityToTheNas() = runBlocking {
        val presented = mutableListOf<Pair<Int, Pair<Int, Int>>>()
        val nfs = NfsBrowser(mount = { _, _, credential -> EmptyExport.also { presented += presented(credential) } }, clock = { 0L })
        val folder = nfsFolder(host = "192.168.1.20", share = "volume1/media")
        nfs.list(folder)
        nfs.test(folder.copy(uid = 1026, gid = 100))
        // Nothing to read in an empty export: the mount is what counts.
        runCatching { nfs.readAt(folder.copy(uid = 1000, gid = 10), "Film.nfo", 0, 1) }
        assertEquals(listOf(1 to (0 to 0), 1 to (1026 to 100), 1 to (1000 to 10)), presented)
    }

    /** AUTH_SYS's flavour, then the uid and gid that follow its stamp and machine name. */
    private fun presented(credential: Credential): Pair<Int, Pair<Int, Int>> {
        val wire = Xdr(XDR_BYTES).apply { credential.marshalling(this) }
        wire.setOffset(0)
        val flavour = wire.int
        val body = Xdr(wire.byteArray)
        body.int
        body.string
        return flavour to (body.int to body.int)
    }

    /**
     * Folders of one export mount it once per identity, as the NAS grants each its own access; forgetting the export,
     * after its address changed, drops its mounts under every identity.
     */
    @Test
    fun anExportIsMountedOncePerIdentity() = runBlocking {
        val mounts = mutableListOf<Pair<Int, Int>>()
        val nfs = NfsBrowser(
            mount = { _, _, credential ->
                mounts += presented(credential).second
                EmptyExport
            },
            clock = { 0L },
        )
        val root = nfsFolder(host = "192.168.1.20", share = "volume1/media", path = "Films")
        val user = root.copy(uid = 1026, gid = 100)
        listOf(root, user, user.copy(path = "Series"), root).forEach { nfs.folders(it) }
        assertEquals(listOf(0 to 0, 1026 to 100), mounts)

        nfs.forget(root)
        listOf(root, user).forEach { nfs.folders(it) }
        assertEquals(listOf(0 to 0, 1026 to 100, 0 to 0, 1026 to 100), mounts)
    }

    /** An export with nothing in it. */
    private object EmptyExport : NfsExport {
        override fun folder(path: String) = ByteArray(0)

        override fun readdirplus(dir: ByteArray, cookie: Long, verifier: Long) = NfsPage(emptyList(), 0, 0, isLast = true)

        override fun lookup(dir: ByteArray, name: String): NfsEntry = throw FileNotFoundException(name)

        override fun read(file: ByteArray, offset: Long, length: Int) = ByteArray(0)

        override fun readlink(link: ByteArray): String = throw FileNotFoundException("no link")
    }

    /**
     * A scan listing thousands of folders of the export, and the add-folder flow going down a level of it as the same
     * user: the level does not wait for the listing to end, the listing mounting the export of its own.
     */
    @Test
    fun aLevelWhileAPassListsTheExportWaitsForNoListing() {
        val listing = CountDownLatch(1)
        val released = CountDownLatch(1)
        val held = object : NfsExport by EmptyExport {
            override fun readdirplus(dir: ByteArray, cookie: Long, verifier: Long): NfsPage {
                listing.countDown()
                released.await()
                return EmptyExport.readdirplus(dir, cookie, verifier)
            }
        }
        val mounted = mutableListOf<NfsExport>()
        val nfs = NfsBrowser(mount = { _, _, _ -> (if (mounted.isEmpty()) held else EmptyExport).also(mounted::add) }, clock = { 0L })
        val folder = nfsFolder(host = "192.168.1.20", share = "volume1/media", path = "Films")
        val pass = thread { runBlocking { nfs.list(folder) } }
        assertTrue(listing.await(5, TimeUnit.SECONDS))

        var level: FolderLevel? = null
        val asked = thread { level = runBlocking { nfs.folders(folder) } }
        asked.join(5_000)
        val answered = !asked.isAlive
        released.countDown()
        pass.join(5_000)
        asked.join(5_000)
        assertTrue("a level shown while the pass lists", answered)
        assertEquals(FolderLevel(emptyList(), videos = 0), level)
        assertEquals("one mount for the listing, one kept for the levels", 2, mounted.size)
    }

    /** NFS has no session to log off: saving a folder never waits for a listing on a NAS that may be retrying. */
    @Test
    fun forgettingAFolderWaitsForNoNfsListing() {
        val mounting = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val nfs = NfsBrowser(
            mount = { _, _, _ ->
                mounting.countDown()
                answer.await()
                throw IOException("rpc request timeout on the connection: /192.168.1.20:635")
            },
            clock = { 0L },
        )
        val browsers = RemoteBrowsers(SmbBrowser(connect = { error("No SMB here") }, clock = { 0L }), nfs)
        val folder = nfsFolder(host = "192.168.1.20", share = "volume1/media", path = "Films")
        val listing = thread { runCatching { runBlocking { nfs.list(folder) } } }
        assertTrue(mounting.await(5, TimeUnit.SECONDS))

        val saving = thread { runBlocking { browsers.forget(folder) } }
        saving.join(5_000)
        val saved = !saving.isAlive
        answer.countDown()
        listing.join(5_000)
        saving.join(5_000)
        assertTrue("saved while the listing waits for the NAS", saved)
    }

    @Test
    fun aRequestWithoutAnswerMountsAgain() {
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "the connection is broken: /192.168.1.20:2049")
        val message = "network error, server: 192.168.1.20, RPC error: ${rpc.message}"
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_IO, message, rpc)))
    }

    /** The NAS restarted, or the export moved: the mount's handles mean nothing to it. */
    @Test
    fun aHandleTheNasForgotMountsAgain() {
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_STALE, "readdir error code: NfsStatus:70")))
        assertTrue(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_BADHANDLE, "readdir error code: NfsStatus:10001")))
    }

    /** lost+found refused, a file gone since the listing, a NAS disk error: the mount is still sound. */
    @Test
    fun aFileRefusedOrGoneKeepsTheMount() {
        assertFalse(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")))
        assertFalse(NfsBrowser.isBroken(FileNotFoundException("lookup /volume1/media/movie.nfo")))
        assertFalse(NfsBrowser.isBroken(NfsException(NfsStatus.NFS3ERR_IO, "read error code: NfsStatus:5")))
    }

    private companion object {
        /** Room for a credential and its verifier. */
        const val XDR_BYTES = 512
    }
}
