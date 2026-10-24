package app.houselights.providers.local

import app.houselights.core.RepeatedFailures
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.emc.ecs.nfsclient.rpc.RpcException
import com.emc.ecs.nfsclient.rpc.RpcStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.FileNotFoundException

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

    /** The source's unreadable paths, kept from one walk to the next as the app keeps them. */
    private val unreadable = RepeatedFailures("RemoteBrowser")

    private fun listing(isFatal: (Throwable) -> Boolean = ScanErrors::isUnreachable) = walk(folder(""), unreadable, isFatal)

    /** What nfs-client throws for a READDIR the NAS refuses. */
    private fun refused() = NfsException(NfsStatus.NFS3ERR_ACCES, "readdir error code: NfsStatus:13")

    /** What nfs-client throws, after its retries, for a request the NAS no longer answers. */
    private fun silent(): NfsException {
        val rpc = RpcException(RpcStatus.NETWORK_ERROR, "rpc request timeout on the connection: /192.168.1.20:2049")
        return NfsException(NfsStatus.NFS3ERR_IO, "network error, server: 192.168.1.20, RPC error: ${rpc.message}", rpc)
    }

    /** What nfs-client throws for a query about a file no longer there (NFS3ERR_NOENT). */
    private fun gone(path: String) = FileNotFoundException("lookup /volume1/media/$path")

    /** Another user's folder, refused at every pass: logged whole once, then counted in one line per walk. */
    @Test
    fun aFolderRefusedAtEveryWalkIsLoggedWholeOnce() {
        tree[""] = listOf("Private/", "Arrival (2016).mkv")
        failures["Private"] = refused()
        ShadowLog.clear()
        repeat(3) { assertEquals(listOf("Private"), listing().unreadable) }
        val told = ShadowLog.getLogsForTag("RemoteBrowser").map { it.msg to (it.throwable != null) }
        assertEquals(
            listOf("Could not read Private" to true) + List(2) { "Paths unreadable again, each logged before: 1" to false },
            told,
        )
    }

    @Test
    fun aFolderThatCannotBeReadIsNamedAndPassedOver() {
        tree[""] = listOf("Dune (2021)/", "Private/", "Shows/", "Arrival (2016).mkv", "poster.jpg")
        tree["Dune (2021)"] = listOf("Dune (2021).mkv", "movie.nfo")
        tree["Shows"] = listOf("Severance/", "Mine/")
        tree["Shows/Severance"] = listOf("Severance - S01E01.mkv")
        failures["Private"] = refused()
        failures["Shows/Mine"] = refused()

        val listing = listing()

        assertEquals(
            listOf("Dune (2021)/Dune (2021).mkv", "Shows/Severance/Severance - S01E01.mkv", "Arrival (2016).mkv"),
            listing.videos.map { it.relativePath },
        )
        assertEquals(listOf("Dune (2021)/movie.nfo", "poster.jpg"), listing.sidecars.map { it.relativePath })
        assertEquals(listOf("Private", "Shows/Mine"), listing.unreadable)
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
        kindFailures["Dune (2021)"] = silent()
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
        failures["Dune (2021)"] = silent()
        assertThrows(NfsException::class.java) { listing() }
        assertEquals("nothing more asked", listOf("", "Dune (2021)"), listed)
    }

    /** A connection the browser calls broken fails the whole walk; the browser, not the walk, opens another and lists again. */
    @Test
    fun aConnectionBrokenFailsTheListing() {
        tree[""] = listOf("Dune (2021)/")
        failures["Dune (2021)"] = IllegalStateException("session gone")
        assertThrows(IllegalStateException::class.java) { listing { it is IllegalStateException } }
    }

    /** The root is level 0: folders [RemoteBrowser.MAX_DEPTH] levels down are listed, deeper ones not even asked for. */
    @Test
    fun foldersDeeperThanTheLimitAreNotListed() {
        val folders = (1..RemoteBrowser.MAX_DEPTH + 1).map { "Level $it" }
        tree[""] = listOf("${folders.first()}/")
        for (i in folders.indices) {
            tree[folders.take(i + 1).joinToString("/")] = listOfNotNull("Film $i.mkv", folders.getOrNull(i + 1)?.let { "$it/" })
        }

        val listing = listing()

        val deepest = folders.take(RemoteBrowser.MAX_DEPTH).joinToString("/")
        assertEquals("$deepest/Film ${RemoteBrowser.MAX_DEPTH - 1}.mkv", listing.videos.last().relativePath)
        assertEquals(RemoteBrowser.MAX_DEPTH, listing.videos.size)
        assertEquals("the next level is not even asked for", RemoteBrowser.MAX_DEPTH + 1, listed.size)
        assertEquals(emptyList<String>(), listing.unreadable)
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
