package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Walking the folders of a NAS: what is listed, what is passed over, what fails it all. On Robolectric for the log. */
@RunWith(RobolectricTestRunner::class)
class RemoteWalkTest {
    /** Folders by path, "" the one walked; a name ending in "/" is a folder. */
    private val tree = mutableMapOf<String, List<String>>()
    private val failures = mutableMapOf<String, Throwable>()
    private val listed = mutableListOf<String>()

    private fun folder(path: String): RemoteFolder = RemoteFolder {
        listed += path
        failures[path]?.let { throw it }
        tree.getValue(path).map { entry(path, it) }
    }

    private fun entry(parent: String, listedAs: String): RemoteEntry = object : RemoteEntry {
        override val name = listedAs.removeSuffix("/")

        override fun folder() = if (listedAs.endsWith("/")) folder(if (parent.isEmpty()) name else "$parent/$name") else null

        override fun size() = 1L
    }

    private fun listing(isFatal: (Throwable) -> Boolean = ScanErrors::isUnreachable) = walk(folder(""), isFatal)

    /** As nfs-client answers a READDIR the NAS refuses. */
    private fun refused() = NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")

    @Test
    fun aFolderThatCannotBeReadIsNamedAndPassedOver() {
        tree[""] = listOf("Dune (2021)/", "Private/", "lost+found/", "Shows/", "Arrival (2016).mkv", "poster.jpg")
        tree["Dune (2021)"] = listOf("Dune (2021).mkv", "movie.nfo")
        tree["Shows"] = listOf("Severance/", "Mine/")
        tree["Shows/Severance"] = listOf("Severance - S01E01.mkv")
        failures["Private"] = refused()
        failures["Shows/Mine"] = refused()
        failures["lost+found"] = IOException("lost+found is root's alone")

        val listing = listing()

        assertEquals(
            listOf("Dune (2021)/Dune (2021).mkv", "Shows/Severance/Severance - S01E01.mkv", "Arrival (2016).mkv"),
            listing.videos.map { it.relativePath },
        )
        assertEquals(listOf("Dune (2021)/movie.nfo", "poster.jpg"), listing.sidecars.map { it.relativePath })
        assertEquals(listOf("Private", "Shows/Mine"), listing.unreadable)
        assertFalse("lost+found is not even asked for", "lost+found" in listed)
    }

    @Test
    fun theFolderItselfRefusedFailsTheListing() {
        failures[""] = refused()
        assertThrows(NfsException::class.java) { listing() }
    }

    /** What follows would fail the same way, each after its timeouts. */
    @Test
    fun aNasThatStopsAnsweringFailsTheListing() {
        tree[""] = listOf("Dune (2021)/", "Arrival (2016)/")
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "rpc request timeout on the connection: /192.168.1.20:2049")
        failures["Dune (2021)"] = NfsException(NfsStatus.NFS3ERR_IO, "network error, server: 192.168.1.20, RPC error: ${rpc.message}", rpc)
        assertThrows(NfsException::class.java) { listing() }
        assertEquals("nothing more asked", listOf("", "Dune (2021)"), listed)
    }

    /** The browser's own word: a broken session is made again, and the listing read anew. */
    @Test
    fun aConnectionBrokenFailsTheListing() {
        tree[""] = listOf("Dune (2021)/")
        failures["Dune (2021)"] = IllegalStateException("session gone")
        assertThrows(IllegalStateException::class.java) { listing { it is IllegalStateException } }
    }
}
