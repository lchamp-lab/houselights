package app.houselights.providers.local

import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.FileNotFoundException

/** Listing an NFS export with READDIRPLUS on an in-memory NAS, and what each listing or read asks of it. */
@RunWith(RobolectricTestRunner::class)
class NfsListingTest {
    /** A file, folder or symbolic link to [link] of the export; [size] and [date] as a GETATTR returns them. */
    private class Item(val isFolder: Boolean, val size: Long = 0, val date: Long? = null, val link: String? = null)

    /**
     * An export of [items] by path, paths as handles: each READDIRPLUS answers at most [perAnswer] entries,
     * those in [withheld] without attributes or handle; it records requests and reads.
     */
    private inner class Export(private val perAnswer: Int = 100) : NfsExport {
        val asked = mutableListOf<String>()
        val read = mutableListOf<String>()

        /** Handles the NAS no longer knows, by path: each refused once, like a file replaced since its lookup. */
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
            if (path.isNotEmpty() && items[path]?.isFolder != true) throw NfsException(NfsStatus.NFS3ERR_NOTDIR, "readdirplus error")
            val all = listOf(".", "..") + children(path).map { it.substringAfterLast('/') }
            val answered = all.drop(cookie.toInt()).take(perAnswer)
            val entries = answered.map { name ->
                val child = if (path.isEmpty()) name else "$path/$name"
                val item = items[child]
                if (item == null || child in withheld) NfsEntry(name, null, null) else NfsEntry(name, handle(child), attributes(item))
            }
            // A NAS that ignores the cookie it is given answers the same page, never the last, at every request.
            if (path in looping) return NfsPage(entries, cookie = 2, verifier = 7, isLast = false)
            val next = cookie + answered.size
            return NfsPage(entries, next, verifier = 7, isLast = next >= all.size)
        }

        override fun lookup(dir: ByteArray, name: String): NfsEntry {
            val path = String(dir).let { if (it.isEmpty()) name else "$it/$name" }
            asked += "LOOKUP /$path"
            if (stale.remove(String(dir))) throw NfsException(NfsStatus.NFS3ERR_STALE, "lookup error code: NfsStatus:70")
            val parent = String(dir)
            if (parent.isNotEmpty() && items[parent]?.isFolder != true) throw NfsException(NfsStatus.NFS3ERR_NOTDIR, "lookup error")
            val item = items[path]?.takeIf { path !in gone } ?: throw FileNotFoundException("lookup /volume1/media/$path")
            return NfsEntry(name, handle(path), attributes(item))
        }

        override fun read(file: ByteArray, offset: Long, length: Int): ByteArray {
            val path = String(file)
            if (stale.remove(path)) throw NfsException(NfsStatus.NFS3ERR_STALE, "read error code: NfsStatus:70")
            // An NFSv3 server reads no symbolic link.
            if (items[path]?.link != null) throw NfsException(NfsStatus.NFS3ERR_INVAL, "read error code: NfsStatus:22")
            read += "/$path"
            return ByteArray(0)
        }

        override fun readlink(link: ByteArray): String {
            val path = String(link)
            asked += "READLINK /$path"
            return items[path]?.link ?: throw NfsException(NfsStatus.NFS3ERR_INVAL, "readlink error code: NfsStatus:22")
        }

        private fun attributes(item: Item) = NfsAttributes(item.isFolder, item.size, item.date, isLink = item.link != null)
    }

    private val items = mutableMapOf<String, Item>()
    private val withheld = mutableSetOf<String>()
    private val refused = mutableMapOf<String, Throwable>()

    /** Listed, then gone before its lookup. */
    private val gone = mutableSetOf<String>()

    /** Folders whose every answer gives the same cookie, never the last ([Export.readdirplus]). */
    private val looping = mutableSetOf<String>()

    private fun folder(path: String) {
        items[path] = Item(isFolder = true)
    }

    private fun file(path: String, size: Long, date: Long? = null) {
        items[path] = Item(isFolder = false, size = size, date = date)
    }

    private fun link(path: String, target: String) {
        items[path] = Item(isFolder = false, size = target.length.toLong(), link = target)
    }

    private var mounts = 0

    private fun browser(export: Export) = NfsBrowser(mount = { _, _, _ -> export.also { mounts++ } }, clock = { 0L })

    private val films = nfsFolder(host = "192.168.1.20", share = "volume1/media", path = "")

    /** A library as Radarr and Kodi leave it: a folder per film, with its NFO and artwork beside the video. */
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

    /** An export's listing counts its videos as it finds them, about a request a folder: a disc once, a trailer never. */
    @Test
    fun aListingCountsItsVideosAsItFindsThem() = runTest {
        library()
        file("Dune (2021)/Dune (2021)-trailer.mkv", size = 1_000_000)
        listOf("Heat (1995)", "Heat (1995)/BDMV", "Heat (1995)/BDMV/STREAM").forEach(::folder)
        file("Heat (1995)/BDMV/STREAM/00000.m2ts", size = 1_000_000)
        file("Heat (1995)/BDMV/STREAM/00001.m2ts", size = 40_000_000_000)
        val counted = mutableListOf<Int>()
        val listing = browser(Export()).list(films) { counted += it }
        assertEquals("Arrival, Dune, Heat's disc, Sicario", listOf(1, 2, 3, 4), counted)
        assertEquals(6, listing.videos.size)
    }

    /** One request per folder, where READDIR then LOOKUP and GETATTR per entry would take about three per file. */
    @Test
    fun aFolderIsListedInOneRequest() = runTest {
        library()
        val export = Export()
        browser(export).list(films)
        assertEquals(listOf("READDIRPLUS / from 0", "READDIRPLUS /Arrival (2016) from 0", "READDIRPLUS /Dune (2021) from 0"), export.asked)
    }

    /** A folder longer than one answer: each answer resumes from the last entry's cookie, none listed twice. */
    @Test
    fun aLongFolderTakesSeveralAnswers() = runTest {
        repeat(7) { file("Film $it (2000).mkv", size = 1L + it) }
        val export = Export(perAnswer = 3)
        val listing = browser(export).list(films)
        assertEquals((0 until 7).map { "Film $it (2000).mkv" }, listing.videos.map { it.relativePath })
        assertEquals(listOf("READDIRPLUS / from 0", "READDIRPLUS / from 3", "READDIRPLUS / from 6"), export.asked)
    }

    /** A NAS may leave out an entry's attributes or handle: only that entry is looked up. */
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

    /** Radarr replaced a file between the answer and the lookup: it is skipped, and named. */
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

    /** lost+found, or another user's folder: skipped and named, the rest listed. */
    @Test
    fun aSubfolderTheNasRefusesIsPassedOver() = runTest {
        library()
        refused["Dune (2021)"] = NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13")
        val listing = browser(Export()).list(films)
        assertEquals(listOf("Dune (2021)"), listing.unreadable)
        assertEquals(listOf("Arrival (2016)/Arrival (2016).mkv", "Sicario (2015).mp4"), listing.videos.map { it.relativePath })
    }

    /** A NAS paging a folder in a loop, its cookie coming back: that folder is passed over and named, the rest listed. */
    @Test
    fun aFolderWhoseCookieComesBackIsPassedOver() = runTest {
        library()
        looping += "Dune (2021)"
        val export = Export(perAnswer = 2)
        val listing = browser(export).list(films)
        assertEquals(listOf("Dune (2021)"), listing.unreadable)
        assertEquals(listOf("Arrival (2016)/Arrival (2016).mkv", "Sicario (2015).mp4"), listing.videos.map { it.relativePath })
        assertEquals("asked again once", 2, export.asked.count { it.startsWith("READDIRPLUS /Dune (2021)") })
    }

    /**
     * The form's test counts what the folder holds, its subfolders and its videos apart, leaving out hidden and system
     * entries, "." and "..", and files that are not videos.
     */
    @Test
    fun aTestCountsTheFolder() = runTest {
        library()
        file("movie.nfo", size = 4_096)
        assertEquals(FolderLevel(listOf("Arrival (2016)", "Dune (2021)"), videos = 1), browser(Export()).test(films))
    }

    /**
     * The add-folder flow's level: subfolders by name as a reader looks for them (« été » before « Zodiac »), hidden
     * and system ones left out, and the videos of this level alone counted, from one READDIRPLUS.
     */
    @Test
    fun aLevelGivesItsFoldersByNameAndCountsItsOwnVideos() = runTest {
        library()
        folder("Zodiac (2007)")
        folder("été")
        folder("#recycle")
        folder(".snapshot")
        file("Sicario (2015).nfo", size = 4_096)
        val export = Export()
        val level = browser(export).folders(films)
        assertEquals(listOf("Arrival (2016)", "Dune (2021)", "été", "Zodiac (2007)"), level.folders)
        assertEquals(1, level.videos)
        assertEquals(listOf("READDIRPLUS / from 0"), export.asked)
    }

    /** A level inside the folder, however its path is slashed; one holding only a video and its files has no folder. */
    @Test
    fun aLevelIsReadByItsPathFromTheFolder() = runTest {
        library()
        folder("Dune (2021)/Extras")
        val level = browser(Export()).folders(films, "/Dune (2021)/")
        assertEquals(FolderLevel(folders = listOf("Extras"), videos = 1), level)
        assertEquals(FolderLevel(emptyList(), 0), browser(Export()).folders(films, "Dune (2021)/Extras"))
        assertTrue(browser(Export()).folders(films, "Dune (2021)/Extras").isEmpty)
    }

    /** An entry READDIRPLUS gave without its type is looked up; a link to a folder is a folder, one leading nowhere left out. */
    @Test
    fun aLevelTellsFoldersFromFilesThroughLinksAndLookups() = runTest {
        linkedLibrary()
        link("films/Arrival (2016)", "/volume1/media/storage/arrival")
        folder("films/Sicario (2015)")
        withheld += "films/Sicario (2015)"
        val level = browser(Export()).folders(linked)
        assertEquals(listOf("Dune (2021)", "Sicario (2015)"), level.folders)
        assertEquals("Heat, through its link", 1, level.videos)
    }

    @Test
    fun aLevelNotThereIsNotFound() = runTest {
        library()
        val failure = runCatching { browser(Export()).folders(films, "Films2") }.exceptionOrNull()
        assertEquals(ScanErrorKind.NOT_FOUND, failure?.let { ScanErrors.classify(it).kind })
    }

    /** A file deep in the export, by its path from the export's root: each name is looked up from the root. */
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
                "LOOKUP /films",
                "LOOKUP /films/4K",
                "LOOKUP /films/4K/Dune (2021)",
                "LOOKUP /films/4K/Dune (2021)/movie.nfo",
                "LOOKUP /Sicario (2015).mp4",
            ),
            export.asked,
        )
    }

    /** A probe's three or four reads, then the NFO and artwork: one lookup for the file, one for its folder. */
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
                "LOOKUP /Dune (2021)",
                "LOOKUP /Dune (2021)/Dune (2021).mkv",
                "LOOKUP /Dune (2021)/movie.nfo",
                "LOOKUP /Dune (2021)/poster.jpg",
            ),
            export.asked,
        )
        assertEquals(5, export.read.size)
    }

    /** Radarr replaced the file since it was read: its handle refused, it is looked up again on the same mount. */
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

    /** A folder handle the NAS no longer knows (moved, recreated): its path is looked up again, same mount. */
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

    /** A dropped mount (scan over, folder address changed) drops its handles: the next read looks the file up. */
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

    /** Handles are kept for a few titles, not a whole library: a file read long ago is looked up again. */
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

    /** Films kept elsewhere in the export and linked from the folder, as a debrid or "category" setup leaves them. */
    private fun linkedLibrary() {
        folder("storage")
        folder("storage/Dune (2021)")
        file("storage/Dune (2021)/Dune (2021).mkv", size = 80_000_000_000, date = 1_760_000_000_000)
        file("storage/Dune (2021)/movie.nfo", size = 4_096)
        file("storage/heat.mkv", size = 9_000_000_000, date = 1_700_000_000_000)
        folder("films")
        link("films/Dune (2021)", "../storage/Dune (2021)")
        link("films/Heat (1995).mkv", "../storage/heat.mkv")
    }

    private val linked = nfsFolder(host = "192.168.1.20", share = "volume1/media", path = "films")

    /** A linked folder is walked and a linked video has its target's size and date, as Samba shows them over SMB. */
    @Test
    fun linksAreListedAsWhatTheyLeadTo() = runTest {
        linkedLibrary()
        val listing = browser(Export()).list(linked)
        assertEquals(
            listOf("Dune (2021)/Dune (2021).mkv" to 80_000_000_000L, "Heat (1995).mkv" to 9_000_000_000L),
            listing.videos.map { it.relativePath to it.size },
        )
        assertEquals(listOf(1_760_000_000_000L, 1_700_000_000_000L), listing.videos.map { it.lastModified })
        assertEquals(listOf("Dune (2021)/movie.nfo"), listing.sidecars.map { it.relativePath })
        assertEquals(emptyList<String>(), listing.unreadable)
    }

    /** Reads go to the target, never to the link, which an NFSv3 server refuses to read. */
    @Test
    fun aFileIsReadThroughItsLinks() = runTest {
        linkedLibrary()
        val export = Export()
        val nas = browser(export)
        nas.readPrefix(linked, "Heat (1995).mkv", 16)
        nas.readPrefix(linked, "Dune (2021)/movie.nfo", 16)
        assertEquals(listOf("/storage/heat.mkv", "/storage/Dune (2021)/movie.nfo"), export.read)
    }

    /** A link to a link, on the way to a folder or to a file, is followed to the end. */
    @Test
    fun aLinkToALinkIsFollowed() = runTest {
        linkedLibrary()
        folder("shortcuts")
        link("shortcuts/dune", "../storage/./Dune (2021)/")
        link("shortcuts/heat", "../storage/heat.mkv")
        link("films/Dune (2021)", "../shortcuts/dune")
        link("films/Heat (1995).mkv", "../shortcuts/heat")
        val export = Export()
        val nas = browser(export)
        val listing = nas.list(linked)
        assertEquals(
            listOf("Dune (2021)/Dune (2021).mkv" to 80_000_000_000L, "Heat (1995).mkv" to 9_000_000_000L),
            listing.videos.map { it.relativePath to it.size },
        )
        nas.readPrefix(linked, "Dune (2021)/movie.nfo", 16)
        assertEquals(listOf("/storage/Dune (2021)/movie.nfo"), export.read)
    }

    /**
     * Links leading nowhere are left out, neither listed as videos of a few bytes nor kept out of sight as a folder that
     * could not be read: what was behind them is missing, as a deleted file is (#52). An absolute target resolves on the
     * player, where a NAS path means nothing; a target gone (an expired debrid link), out of the export or in a loop.
     */
    @Test
    fun linksLeadingNowhereAreLeftOut() = runTest {
        linkedLibrary()
        link("films/Arrival (2016).mkv", "/volume1/media/storage/arrival.mkv")
        link("films/Sicario (2015).mkv", "../storage/sicario.mkv")
        link("films/Up (2009).mkv", "../../up.mkv")
        link("films/Looper (2012).mkv", "Looper (2012).mkv")
        link("films/Prey (2022)", "../storage/Prey (2022)/")
        val listing = browser(Export()).list(linked)
        assertEquals(listOf("Dune (2021)/Dune (2021).mkv", "Heat (1995).mkv"), listing.videos.map { it.relativePath })
        assertEquals(emptyList<String>(), listing.unreadable)
    }

    /** A link to a folder the NAS refuses to list keeps its titles out of sight, as that folder unlinked would (#52). */
    @Test
    fun aLinkToAFolderRefusedKeepsItUnread() = runTest {
        linkedLibrary()
        refused["storage/Dune (2021)"] = NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13")
        val listing = browser(Export()).list(linked)
        assertEquals(listOf("Heat (1995).mkv"), listing.videos.map { it.relativePath })
        assertEquals(listOf("Dune (2021)"), listing.unreadable)
    }

    /** A link back up the walk would list the same folders again and again: passed over, the rest listed once. */
    @Test
    fun aLinkBackUpTheWalkIsPassedOver() = runTest {
        linkedLibrary()
        link("films/All", ".")
        link("storage/Dune (2021)/Films", "../../films")
        val listing = browser(Export()).list(linked)
        assertEquals(listOf("Dune (2021)/Dune (2021).mkv", "Heat (1995).mkv"), listing.videos.map { it.relativePath })
        assertEquals(emptyList<String>(), listing.unreadable)
    }

    /** Logged whole the first time, as any path that could not be read, then counted at each listing. */
    @Test
    fun aLinkNotFollowedIsLoggedWholeOnce() = runTest {
        linkedLibrary()
        link("films/Arrival (2016).mkv", "/volume1/media/storage/arrival.mkv")
        val nas = browser(Export())
        ShadowLog.clear()
        repeat(2) { nas.list(linked) }
        val told = ShadowLog.getLogsForTag("RemoteBrowser").map { it.msg to (it.throwable != null) }
        assertEquals(
            listOf(
                "Passed over Arrival (2016).mkv, which leads nowhere" to true,
                "Paths unreadable again, each logged before: 1" to false,
            ),
            told,
        )
    }
}
