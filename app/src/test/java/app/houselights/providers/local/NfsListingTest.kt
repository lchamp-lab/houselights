package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException

/**
 * Listing an NFS export with READDIRPLUS, on a NAS held in memory: what a listing gives, as READDIR
 * and a lookup of each entry gave it, and what it asks the NAS; what reading a file asks it. On
 * Robolectric for the log.
 */
@RunWith(RobolectricTestRunner::class)
class NfsListingTest {
    /** A file or folder of the export; [size] and [date]: as a GETATTR tells them. */
    private class Item(val isFolder: Boolean, val size: Long = 0, val date: Long? = null)

    /**
     * An export holding [items] by path from its root ("/" separators), handles their paths. Each
     * READDIRPLUS answers [perAnswer] entries at most, "." and ".." first; the entries of [withheld]
     * come without their attributes and handle, as a NAS may answer. What was asked, in order, and the
     * files read, by path.
     */
    private inner class Export(private val perAnswer: Int = 100) : NfsExport {
        val asked = mutableListOf<String>()
        val read = mutableListOf<String>()

        /** Handles the NAS no longer knows, by path: each is refused once, as a file replaced since it was looked up. */
        val stale = mutableSetOf<String>()

        private fun handle(path: String) = path.toByteArray()

        private fun children(dir: String) = items.keys.filter { it.substringBeforeLast('/', "") == dir && it.isNotEmpty() }.sorted()

        override fun folder(path: String): ByteArray {
            val dir = path.trim('/')
            if (dir.isNotEmpty() && items[dir]?.isFolder != true) throw FileNotFoundException(path)
            return handle(dir)
        }

        override fun readdirplus(dir: ByteArray, cookie: Long, verifier: Long): NfsPage {
            val path = String(dir)
            asked += "READDIRPLUS /$path from $cookie"
            refused[path]?.let { throw it }
            val all = listOf(".", "..") + children(path).map { it.substringAfterLast('/') }
            val answered = all.drop(cookie.toInt()).take(perAnswer)
            val entries = answered.map { name ->
                val child = if (path.isEmpty()) name else "$path/$name"
                val item = items[child]
                if (item == null || child in withheld) NfsEntry(name, null, null) else NfsEntry(name, handle(child), attributes(item))
            }
            val next = cookie + answered.size
            return NfsPage(entries, next, verifier = 7, isLast = next >= all.size)
        }

        override fun lookup(dir: ByteArray, name: String): NfsEntry {
            val path = String(dir).let { if (it.isEmpty()) name else "$it/$name" }
            asked += "LOOKUP /$path"
            if (stale.remove(String(dir))) throw NfsException(NfsStatus.NFS3ERR_STALE, "lookup error code: NfsStatus:70")
            val item = items[path]?.takeIf { path !in gone } ?: throw FileNotFoundException("lookup /volume1/media/$path")
            return NfsEntry(name, handle(path), attributes(item))
        }

        override fun read(file: ByteArray, offset: Long, length: Int): ByteArray {
            val path = String(file)
            if (stale.remove(path)) throw NfsException(NfsStatus.NFS3ERR_STALE, "read error code: NfsStatus:70")
            read += "/$path"
            return ByteArray(0)
        }

        private fun attributes(item: Item) = NfsAttributes(item.isFolder, item.size, item.date)
    }

    private val items = mutableMapOf<String, Item>()
    private val withheld = mutableSetOf<String>()
    private val refused = mutableMapOf<String, Throwable>()

    /** Listed, then gone before its lookup. */
    private val gone = mutableSetOf<String>()

    private fun folder(path: String) {
        items[path] = Item(isFolder = true)
    }

    private fun file(path: String, size: Long, date: Long? = null) {
        items[path] = Item(isFolder = false, size = size, date = date)
    }

    /** Times the export was mounted. */
    private var mounts = 0

    private fun browser(export: Export) = NfsBrowser(mount = { export.also { mounts++ } }, clock = { 0L })

    private val films = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20", share = "volume1/media",
        path = "", username = "", password = Secret(""),
    )

    /** A library as Radarr and Kodi leave it: a folder per film, its NFO and artwork beside the video. */
    private fun library() {
        folder("Dune (2021)")
        file("Dune (2021)/Dune (2021).mkv", size = 80_000_000_000, date = 1_760_000_000_000)
        file("Dune (2021)/movie.nfo", size = 4_096, date = 1_760_000_060_000)
        file("Dune (2021)/poster.jpg", size = 500_000)
        file("Dune (2021)/Dune (2021).srt", size = 90_000)
        folder("Arrival (2016)")
        file("Arrival (2016)/Arrival (2016).mkv", size = 30_000_000_000)
        file("Arrival (2016)/._Arrival (2016).mkv", size = 4_096)
        folder("@eaDir")
        file("@eaDir/thumb.jpg", size = 10)
        file("Sicario (2015).mp4", size = 9_000_000_000, date = 1_700_000_000_000)
    }

    @Test
    fun aListingGivesWhatEachFileIsItsSizeAndItsDate() = runTest {
        library()
        val listing = browser(Export()).list(films)
        assertEquals(
            listOf(
                "Arrival (2016)/Arrival (2016).mkv" to 30_000_000_000L,
                "Dune (2021)/Dune (2021).mkv" to 80_000_000_000L,
                "Sicario (2015).mp4" to 9_000_000_000L,
            ),
            listing.videos.map { it.relativePath to it.size },
        )
        assertEquals(listOf(1_760_000_000_000L), listing.videos.filter { it.relativePath.startsWith("Dune") }.map { it.lastModified })
        assertEquals(
            listOf("Dune (2021)/movie.nfo" to 1_760_000_060_000L, "Dune (2021)/poster.jpg" to null),
            listing.sidecars.map { it.relativePath to it.lastModified },
        )
        assertEquals(emptyList<String>(), listing.unreadable)
    }

    /** One request per folder (the system's left unread), where READDIR then a LOOKUP and a GETATTR per entry took about three per file. */
    @Test
    fun aFolderIsListedInOneRequest() = runTest {
        library()
        val export = Export()
        browser(export).list(films)
        assertEquals(listOf("READDIRPLUS / from 0", "READDIRPLUS /Arrival (2016) from 0", "READDIRPLUS /Dune (2021) from 0"), export.asked)
    }

    /** A folder longer than an answer: each next one goes on from the last entry's cookie, none listed twice. */
    @Test
    fun aLongFolderTakesSeveralAnswers() = runTest {
        repeat(7) { file("Film $it (2000).mkv", size = 1L + it) }
        val export = Export(perAnswer = 3)
        val listing = browser(export).list(films)
        assertEquals((0 until 7).map { "Film $it (2000).mkv" }, listing.videos.map { it.relativePath })
        assertEquals(listOf("READDIRPLUS / from 0", "READDIRPLUS / from 3", "READDIRPLUS / from 6"), export.asked)
    }

    /** A NAS may leave out an entry's attributes or handle: that entry alone is looked up, as every entry was before. */
    @Test
    fun anEntryAnsweredWithoutItsAttributesIsLookedUp() = runTest {
        library()
        withheld += listOf("Dune (2021)", "Sicario (2015).mp4")
        val export = Export()
        val listing = browser(export).list(films)
        assertEquals(3, listing.videos.size)
        assertEquals(9_000_000_000L, listing.videos.single { it.relativePath == "Sicario (2015).mp4" }.size)
        assertEquals(listOf("LOOKUP /Dune (2021)", "LOOKUP /Sicario (2015).mp4"), export.asked.filter { it.startsWith("LOOKUP") })
    }

    /** Radarr replaced a file between the answer and the lookup: it is passed over, and named. */
    @Test
    fun anEntryGoneBeforeItsLookupIsPassedOver() = runTest {
        library()
        file("Heat (1995).mkv", size = 1)
        withheld += "Heat (1995).mkv"
        gone += "Heat (1995).mkv"
        val listing = browser(Export()).list(films)
        assertEquals(listOf("Heat (1995).mkv"), listing.unreadable)
        assertEquals(3, listing.videos.size)
    }

    /** lost+found, or a folder of another user: passed over, named, the rest listed. */
    @Test
    fun aSubfolderTheNasRefusesIsPassedOver() = runTest {
        library()
        refused["Dune (2021)"] = NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13")
        val listing = browser(Export()).list(films)
        assertEquals(listOf("Dune (2021)"), listing.unreadable)
        assertEquals(listOf("Arrival (2016)/Arrival (2016).mkv", "Sicario (2015).mp4"), listing.videos.map { it.relativePath })
    }

    /** The form's test counts what the folder holds, hidden and system entries aside, "." and ".." too. */
    @Test
    fun aTestCountsTheFolder() = runTest {
        library()
        assertEquals(3, browser(Export()).test(films))
    }

    /** A file of a folder deep in the export, by its path from the export's root: each name looked up from the root. */
    @Test
    fun aFileIsReadByItsPathInTheExport() = runTest {
        folder("films")
        folder("films/4K")
        folder("films/4K/Dune (2021)")
        file("films/4K/Dune (2021)/movie.nfo", size = 4_096)
        file("Sicario (2015).mp4", size = 9_000_000_000)
        val export = Export()
        val nas = browser(export)
        nas.readPrefix(films.copy(path = "/films/4K/"), "Dune (2021)/movie.nfo", 16)
        nas.readPrefix(films, "Sicario (2015).mp4", 16)
        assertEquals(listOf("/films/4K/Dune (2021)/movie.nfo", "/Sicario (2015).mp4"), export.read)
        assertEquals(
            listOf(
                "LOOKUP /films", "LOOKUP /films/4K", "LOOKUP /films/4K/Dune (2021)", "LOOKUP /films/4K/Dune (2021)/movie.nfo",
                "LOOKUP /Sicario (2015).mp4",
            ),
            export.asked,
        )
    }

    /**
     * A probe reads its file three or four times, then the title's NFO and artwork beside it are read:
     * the file is looked up once, and its folder once for all of them.
     */
    @Test
    fun aFolderAndItsFilesAreLookedUpOnceForAllTheirReads() = runTest {
        library()
        val export = Export()
        val nas = browser(export)
        repeat(3) { nas.readAt(films, "Dune (2021)/Dune (2021).mkv", offset = it * 1024L, length = 1024) }
        nas.readPrefix(films, "Dune (2021)/movie.nfo", 4_096)
        nas.readPrefix(films, "Dune (2021)/poster.jpg", 4_096)
        assertEquals(
            listOf(
                "LOOKUP /Dune (2021)", "LOOKUP /Dune (2021)/Dune (2021).mkv", "LOOKUP /Dune (2021)/movie.nfo",
                "LOOKUP /Dune (2021)/poster.jpg",
            ),
            export.asked,
        )
        assertEquals(5, export.read.size)
    }

    /** Radarr replaced the file since it was read: its handle refused, it is looked up once more and read, on the same mount. */
    @Test
    fun aFileReplacedSinceItWasReadIsLookedUpAgain() = runTest {
        library()
        val export = Export()
        val nas = browser(export)
        nas.readPrefix(films, "Dune (2021)/movie.nfo", 4_096)
        export.stale += "Dune (2021)/movie.nfo"
        nas.readPrefix(films, "Dune (2021)/movie.nfo", 4_096)
        assertEquals(listOf("/Dune (2021)/movie.nfo", "/Dune (2021)/movie.nfo"), export.read)
        assertEquals(2, export.asked.count { it == "LOOKUP /Dune (2021)/movie.nfo" })
        assertEquals(1, mounts)
    }

    /** A folder whose handle the NAS no longer knows (moved, made again): its path is looked up again, on the same mount. */
    @Test
    fun aFolderForgottenSinceItWasReadIsLookedUpAgain() = runTest {
        library()
        val export = Export()
        val nas = browser(export)
        nas.readPrefix(films, "Dune (2021)/movie.nfo", 4_096)
        export.stale += "Dune (2021)"
        nas.readPrefix(films, "Dune (2021)/poster.jpg", 4_096)
        assertEquals(listOf("/Dune (2021)/movie.nfo", "/Dune (2021)/poster.jpg"), export.read)
        assertEquals(2, export.asked.count { it == "LOOKUP /Dune (2021)" })
        assertEquals(1, mounts)
    }

    /** A mount dropped (a scan over, the folder's address changed) takes its handles along: the next read looks its file up. */
    @Test
    fun aMountDroppedTakesItsHandlesAlong() = runTest {
        library()
        val export = Export()
        val nas = browser(export)
        nas.readPrefix(films, "Sicario (2015).mp4", 16)
        nas.closeIdle()
        nas.readPrefix(films, "Sicario (2015).mp4", 16)
        nas.forget(films)
        nas.readPrefix(films, "Sicario (2015).mp4", 16)
        assertEquals(3, export.asked.count { it == "LOOKUP /Sicario (2015).mp4" })
    }

    /** A few titles' handles are kept, not a whole library's: one read long ago is looked up again. */
    @Test
    fun aFileReadLongAgoIsLookedUpAgain() = runTest {
        repeat(100) { file("Film $it (2000).mkv", size = 1) }
        val export = Export()
        val nas = browser(export)
        repeat(100) { nas.readPrefix(films, "Film $it (2000).mkv", 16) }
        nas.readPrefix(films, "Film 0 (2000).mkv", 16)
        nas.readPrefix(films, "Film 99 (2000).mkv", 16)
        assertEquals(2, export.asked.count { it == "LOOKUP /Film 0 (2000).mkv" })
        assertEquals(1, export.asked.count { it == "LOOKUP /Film 99 (2000).mkv" })
    }

    /** A file gone since it was listed is not found, and the mount kept. */
    @Test
    fun aFileGoneIsNotFound() = runTest {
        library()
        gone += "Sicario (2015).mp4"
        val nas = browser(Export())
        val failure = runCatching { nas.readPrefix(films, "Sicario (2015).mp4", 16) }.exceptionOrNull()
        assertEquals(ScanErrorKind.NOT_FOUND, failure?.let { ScanErrors.classify(it).kind })
        nas.readPrefix(films, "Dune (2021)/movie.nfo", 16)
        assertEquals(1, mounts)
    }

    @Test
    fun aFolderNotThereIsNotFound() = runTest {
        library()
        val failure = runCatching { browser(Export()).list(films.copy(path = "Films")) }.exceptionOrNull()
        assertEquals(ScanErrorKind.NOT_FOUND, failure?.let { ScanErrors.classify(it).kind })
    }
}
