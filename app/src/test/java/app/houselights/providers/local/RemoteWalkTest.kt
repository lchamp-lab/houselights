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
import java.io.FileNotFoundException
import java.io.IOException

/** Walking a NAS's folders: what is listed or skipped, what fails the whole walk. On Robolectric for the log. */
@RunWith(RobolectricTestRunner::class)
class RemoteWalkTest {
    /** Folders by path, "" being the one walked; a name ending in "/" is a folder. */
    private val tree = mutableMapOf<String, List<String>>()
    private val failures = mutableMapOf<String, Throwable>()

    /** Entries, by path, whose kind or size cannot be queried: each is a request of its own on NFS. */
    private val kindFailures = mutableMapOf<String, Throwable>()
    private val sizeFailures = mutableMapOf<String, Throwable>()
    private val listed = mutableListOf<String>()

    private fun folder(path: String): RemoteFolder = RemoteFolder {
        listed += path
        failures[path]?.let { throw it }
        tree.getValue(path).map { entry(path, it) }
    }

    private fun entry(parent: String, listedAs: String): RemoteEntry = object : RemoteEntry {
        override val name = listedAs.removeSuffix("/")
        private val path = if (parent.isEmpty()) name else "$parent/$name"

        override fun folder(): RemoteFolder? {
            kindFailures[path]?.let { throw it }
            return if (listedAs.endsWith("/")) folder(path) else null
        }

        override fun size(): Long {
            sizeFailures[path]?.let { throw it }
            return 1L
        }
    }

    private fun listing(isFatal: (Throwable) -> Boolean = ScanErrors::isUnreachable) = walk(folder(""), isFatal)

    /** What nfs-client throws for a READDIR the NAS refuses. */
    private fun refused() = NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")

    /** What nfs-client throws for a query about a file no longer there (NFS3ERR_NOENT). */
    private fun gone(path: String) = FileNotFoundException("lookup /volume1/media/$path")

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

    /** Radarr or Sonarr replaced a file between the listing and its GETATTR: only that entry is skipped. */
    @Test
    fun anEntryGoneSinceTheListingIsNamedAndPassedOver() {
        tree[""] = listOf("Dune (2021)/", "Arrival (2016)/", "Blade Runner (1982).mkv", "Heat (1995).mkv")
        tree["Dune (2021)"] = listOf("Dune (2021).mkv", "Dune (2021).nfo")
        tree["Arrival (2016)"] = listOf("Arrival (2016).mkv")
        kindFailures["Dune (2021)/Dune (2021).mkv"] = gone("Dune (2021)/Dune (2021).mkv")
        kindFailures["Arrival (2016)"] = gone("Arrival (2016)")
        sizeFailures["Blade Runner (1982).mkv"] = gone("Blade Runner (1982).mkv")

        val listing = listing()

        assertEquals(listOf("Heat (1995).mkv"), listing.videos.map { it.relativePath })
        assertEquals(listOf("Dune (2021)/Dune (2021).nfo"), listing.sidecars.map { it.relativePath })
        assertEquals(listOf("Dune (2021)/Dune (2021).mkv", "Arrival (2016)", "Blade Runner (1982).mkv"), listing.unreadable)
    }

    @Test
    fun aNasThatStopsAnsweringAboutAnEntryFailsTheListing() {
        tree[""] = listOf("Dune (2021)/", "Arrival (2016)/")
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "rpc request timeout on the connection: /192.168.1.20:2049")
        val message = "network error, server: 192.168.1.20, RPC error: ${rpc.message}"
        kindFailures["Dune (2021)"] = NfsException(NfsStatus.NFS3ERR_IO, message, rpc)
        assertThrows(NfsException::class.java) { listing() }
        assertEquals("nothing more asked", listOf(""), listed)
    }

    @Test
    fun theFolderItselfRefusedFailsTheListing() {
        failures[""] = refused()
        assertThrows(NfsException::class.java) { listing() }
    }

    /** The rest would fail the same way, each after its timeouts. */
    @Test
    fun aNasThatStopsAnsweringFailsTheListing() {
        tree[""] = listOf("Dune (2021)/", "Arrival (2016)/")
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "rpc request timeout on the connection: /192.168.1.20:2049")
        failures["Dune (2021)"] = NfsException(NfsStatus.NFS3ERR_IO, "network error, server: 192.168.1.20, RPC error: ${rpc.message}", rpc)
        assertThrows(NfsException::class.java) { listing() }
        assertEquals("nothing more asked", listOf("", "Dune (2021)"), listed)
    }

    /** The browser reports a broken connection: the session is reopened and the listing read again. */
    @Test
    fun aConnectionBrokenFailsTheListing() {
        tree[""] = listOf("Dune (2021)/")
        failures["Dune (2021)"] = IllegalStateException("session gone")
        assertThrows(IllegalStateException::class.java) { listing { it is IllegalStateException } }
    }

    @Test
    fun systemFoldersArePassedOverButNotTitlesThatStartLikeThem() {
        tree[""] = listOf(
            "#Alive (2020)/", "@Zombie (2018).mkv", "#recycle/", "#SNAPSHOT/", "@eaDir/", "@Recycle/", "@Recently-Snapshot/",
            ".@__thumb/", ".AppleDouble/", ".Trash-1000/", "\$RECYCLE.BIN/", "System Volume Information/", "lost+found/",
            "._Heat (1995).mkv", ".DS_Store", "#SynoRecycle/",
        )
        tree["#Alive (2020)"] = listOf("#Alive (2020).mkv", "@eaDir/", "@Transcode/")
        tree["#Alive (2020)/@Transcode"] = listOf("#Alive (2020).mkv.720.mp4")

        val listing = listing()

        assertEquals(listOf("#Alive (2020)/#Alive (2020).mkv", "@Zombie (2018).mkv"), listing.videos.map { it.relativePath })
        assertEquals("no system folder is even asked for", listOf("", "#Alive (2020)"), listed)
    }
}
