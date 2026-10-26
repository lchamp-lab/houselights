package app.houselights.providers.local

import app.houselights.core.DiskAbsentException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A folder of the player's own disks read as a NAS's is: what is listed and read, what a disk unplugged, moved or
 * refused does. A temporary folder stands for the disk; on Robolectric for the log.
 */
@RunWith(RobolectricTestRunner::class)
class LocalBrowserTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val disks = TestDisks()

    /** Whether Android lets the app read the player's disks. */
    private var readable = true
    private val browser = LocalBrowser(disks) { readable }
    private lateinit var disk: File

    @Before
    fun plug() {
        disk = temp.newFolder(USB_DISK)
        disks.plug(USB_DISK, disk, "Samsung T7")
    }

    private suspend fun list(folder: SourceEntity = diskFolder()) = browser.list(folder)

    private suspend fun videos(folder: SourceEntity = diskFolder()) = list(folder).videos.map { it.relativePath }.sorted()

    /** Unplugged, as Android sees it: no longer listed, its files gone with it. */
    private fun unplug() {
        disks.unplug(USB_DISK)
        check(disk.renameTo(File(temp.root, "away"))) { "The disk stayed" }
    }

    /** A disk's folder lists as a NAS's: its videos and sidecars, each with its size and date, what is not a title left out. */
    @Test
    fun aDisksFolderListsAsANassDoes() = runTest {
        disk.put("Films/Dune (2021)/Dune (2021).mkv", ByteArray(2048), modified = T1)
        disk.put("Films/Dune (2021)/Dune (2021).nfo", modified = T1)
        disk.put("Films/Dune (2021)/poster.jpg")
        disk.put("Films/Heat (1995).mkv", modified = T2)
        disk.put("Films/Heat (1995)-sample.mkv")
        disk.put("Films/notes.txt")
        disk.put("Films/.Trashes/Old (1990).mkv")
        disk.put("Films/\$RECYCLE.BIN/Old (1990).mkv")
        disk.put("Séries/Severance/Season 1/Severance - S01E01.mkv")

        val listing = list()
        assertEquals(
            listOf(RemoteFile("Dune (2021)/Dune (2021).mkv", 2048, T1), RemoteFile("Heat (1995).mkv", 16, T2)),
            listing.videos.sortedBy { it.relativePath },
        )
        assertEquals(listOf("Dune (2021)/Dune (2021).nfo", "Dune (2021)/poster.jpg"), listing.sidecars.map { it.relativePath }.sorted())
        assertEquals(emptyList<String>(), listing.unreadable)
        assertEquals(
            "the form's test counts the folder's subfolders and videos apart, samples, dot and system folders left out",
            FolderLevel(listOf("Dune (2021)"), videos = 1),
            browser.test(diskFolder()),
        )
    }

    /** At a disk's root, Android's own folders hold no film of the user's: left out, a folder of the same name further down kept. */
    @Test
    fun aDisksRootLeavesAndroidsOwnFoldersOut() = runTest {
        disk.put("Android/data/com.example/cache/clip.mp4")
        disk.put("LOST.DIR/1234.mkv")
        disk.put("Dune (2021).mkv")
        disk.put("Films/Android/Android Apocalypse (2006).mkv")

        val root = diskFolder(path = "")
        assertEquals(listOf("Dune (2021).mkv", "Films/Android/Android Apocalypse (2006).mkv"), videos(root))
        assertEquals(FolderLevel(listOf("Films"), videos = 1), browser.test(root))
    }

    /**
     * The add-folder flow opens a disk at its root and goes down a level at a time: its folders by name, Android's own,
     * hidden and system ones and a link back up left out, its own videos counted; a level gone is not found, and a disk
     * unplugged out of reach.
     */
    @Test
    fun aDiskIsBrowsedALevelAtATime() = runTest {
        disk.put("Android/data/com.example/cache/clip.mp4")
        disk.put("LOST.DIR/1234.mkv")
        disk.put(".Trashes/Old (1990).mkv")
        disk.put("Vidéos/Films/Dune (2021).mkv")
        disk.put("Vidéos/Films/Heat (1995).mkv")
        disk.put("Vidéos/Films/Heat (1995).nfo")
        disk.put("Vidéos/Séries/Severance/Season 1/Severance - S01E01.mkv")
        disk.put("Vidéos/été 2019/plage.mp4")
        disk.put("Arrival (2016).mkv")
        Files.createSymbolicLink(disk.resolve("Vidéos/Again").toPath(), disk.resolve("Vidéos").toPath())
        val root = diskFolder(path = "")

        assertEquals(FolderLevel(listOf("Vidéos"), videos = 1), browser.folders(root))
        assertEquals(FolderLevel(listOf("été 2019", "Films", "Séries"), videos = 0), browser.folders(root, "Vidéos"))
        assertEquals(FolderLevel(emptyList(), videos = 2), browser.folders(root, "/Vidéos//Films/"))
        assertEquals("from a folder inside the disk", browser.folders(root, "Vidéos"), browser.folders(diskFolder(path = "Vidéos")))

        val gone = assertThrows(Exception::class.java) { runBlocking { browser.folders(root, "Vidéos/Documentaires") } }
        assertEquals(ScanErrorKind.NOT_FOUND, ScanErrors.classify(gone).kind)
        unplug()
        assertThrows(DiskAbsentException::class.java) { runBlocking { browser.folders(root, "Vidéos") } }
    }

    /** The MKV probe, NFOs and artwork read a disk's file from any point, fewer bytes past its end. */
    @Test
    fun aFileReadsFromAnyPoint() = runTest {
        val bytes = ByteArray(100) { it.toByte() }
        disk.put("Films/Heat (1995).mkv", bytes)

        assertArrayEquals(bytes.copyOfRange(10, 15), browser.readAt(diskFolder(), "Heat (1995).mkv", 10, 5))
        assertArrayEquals(bytes.copyOfRange(95, 100), browser.readAt(diskFolder(), "Heat (1995).mkv", 95, 10))
        assertArrayEquals(bytes, browser.readPrefix(diskFolder(), "Heat (1995).mkv", 1000))
    }

    /** A link to a folder elsewhere on the disk is followed; one back up the walk, which would list it again, is not. */
    @Test
    fun aLinkBackUpTheWalkIsNotFollowed() = runTest {
        disk.put("Films/Dune (2021).mkv")
        disk.put("Archives/Heat (1995).mkv")
        Files.createSymbolicLink(disk.resolve("Films/More").toPath(), disk.resolve("Archives").toPath())
        Files.createSymbolicLink(disk.resolve("Films/Again").toPath(), disk.resolve("Films").toPath())

        val listing = list()
        assertEquals(listOf("Dune (2021).mkv", "More/Heat (1995).mkv"), listing.videos.map { it.relativePath }.sorted())
        assertEquals(listOf("Again"), listing.unreadable)
    }

    /** A disk unplugged is out of reach as a NAS asleep: it lists nothing, so no title is missing, and reads nothing. */
    @Test
    fun aDiskUnpluggedIsOutOfReach() = runTest {
        disk.put("Films/Heat (1995).mkv")
        assertEquals(listOf("Heat (1995).mkv"), videos())

        unplug()
        // Mid-pass, where the disk was is read first.
        val read = assertThrows(DiskAbsentException::class.java) { runBlocking { browser.readAt(diskFolder(), "Heat (1995).mkv", 0, 10) } }
        assertEquals(ScanErrorKind.UNREACHABLE, ScanErrors.classify(read).kind)
        browser.closeIdle()
        val listed = assertThrows(DiskAbsentException::class.java) { runBlocking { list() } }
        assertEquals(ScanErrorKind.UNREACHABLE, ScanErrors.classify(listed).kind)
        assertThrows(DiskAbsentException::class.java) { runBlocking { browser.test(diskFolder()) } }
    }

    /** Unplugged while its folder lists: what the listing did not reach is not gone, so the listing is no answer. */
    @Test
    fun aDiskUnpluggedWhileItListsIsOutOfReach() = runTest {
        disk.put("Films/Heat (1995).mkv")
        var asked = 0
        val leaving = LocalBrowser(Volumes { if (asked++ == 0) disks.mounted() else emptyList() }) { true }

        val e = assertThrows(DiskAbsentException::class.java) { runBlocking { leaving.list(diskFolder()) } }
        assertEquals(ScanErrorKind.UNREACHABLE, ScanErrors.classify(e).kind)
    }

    /**
     * Unplugged as its folder's entries come, the read failing partway (Java's DirectoryIteratorException, unchecked):
     * out of reach as when it fails opening, for the scan, the form's test and the flow alike.
     */
    @Test
    fun aDiskUnpluggedAsItsFolderIsReadIsOutOfReach() = runTest {
        disk.put("Films/Heat (1995).mkv")
        val cut = LocalBrowser(disks, directories = ::unpluggedAsRead) { true }
        val reads = listOf<suspend () -> Any>({ cut.list(diskFolder()) }, { cut.test(diskFolder()) }, { cut.folders(diskFolder()) })

        reads.forEach { read ->
            disks.plug(USB_DISK, disk)
            val e = assertThrows(DiskAbsentException::class.java) { runBlocking { read() } }
            assertEquals(ScanErrorKind.UNREACHABLE, ScanErrors.classify(e).kind)
        }
    }

    /** A folder whose entries fail as they come, the disk unplugged meanwhile, as Java's stream reports a failed read. */
    private fun unpluggedAsRead(dir: Path): DirectoryStream<Path> = object : DirectoryStream<Path> {
        override fun iterator(): MutableIterator<Path> = object : MutableIterator<Path> {
            override fun hasNext(): Boolean {
                disks.unplug(USB_DISK)
                throw DirectoryIteratorException(IOException("Input/output error reading ${dir.fileName}"))
            }

            override fun next(): Path = throw NoSuchElementException("No entry comes")

            override fun remove(): Unit = throw UnsupportedOperationException("Entries are only read")
        }

        override fun close() = Unit
    }

    /**
     * Without the access Android grants (all-files access from Android 11), a disk is refused before it is read, as
     * Android 11 would list its folders and none of its files: no answer of nothing, which the scan, the form's test and
     * the flow would take for an empty folder.
     */
    @Test
    fun aDiskTheAppMayNotReadIsRefused() = runTest {
        disk.put("Films/Heat (1995)/Heat (1995).mkv")
        readable = false
        val reads = listOf<suspend () -> Any>(
            { list() }, { browser.test(diskFolder()) }, { browser.folders(diskFolder()) },
            { browser.readAt(diskFolder(), "Heat (1995)/Heat (1995).mkv", 0, 10) },
        )

        reads.forEach { read ->
            val e = assertThrows(Exception::class.java) { runBlocking { read() } }
            assertEquals(ScanErrorKind.ACCESS_DENIED, ScanErrors.classify(e).kind)
        }
        readable = true
        assertEquals("granted again, read at once", listOf("Heat (1995)/Heat (1995).mkv"), videos())
    }

    /** Plugged back where Android mounts it now: the next pass reads it there; a read already under way says it moved, once. */
    @Test
    fun aDiskPluggedBackElsewhereIsReadWhereItIsNow() = runTest {
        disk.put("Films/Heat (1995).mkv", ByteArray(10) { 7 })
        assertEquals(listOf("Heat (1995).mkv"), videos())

        val moved = File(temp.root, "media_rw")
        check(disk.renameTo(moved)) { "The disk did not move" }
        disks.plug(USB_DISK, moved)
        assertThrows(DiskAbsentException::class.java) { runBlocking { browser.readAt(diskFolder(), "Heat (1995).mkv", 0, 10) } }
        assertArrayEquals(ByteArray(10) { 7 }, browser.readAt(diskFolder(), "Heat (1995).mkv", 0, 10))

        browser.closeIdle()
        assertEquals(listOf("Heat (1995).mkv"), videos())
    }

    /** On a disk still there, a folder gone is missing and one Android refuses is refused, as on a NAS. */
    @Test
    fun aFolderGoneOrRefusedSaysSo() = runTest {
        val gone = assertThrows(Exception::class.java) { runBlocking { list(diskFolder(path = "Films")) } }
        assertEquals(ScanErrorKind.NOT_FOUND, ScanErrors.classify(gone).kind)

        disk.put("Films/Dune (2021).mkv")
        disk.put("Films/Private/Heat (1995).mkv")
        val private = disk.resolve("Films/Private")
        Files.setPosixFilePermissions(private.toPath(), emptySet())
        try {
            assumeFalse("root reads every folder", private.canRead())
            val listing = list()
            assertEquals(listOf("Dune (2021).mkv"), listing.videos.map { it.relativePath })
            assertEquals("not missing, only out of sight", listOf("Private"), listing.unreadable)

            val refused = assertThrows(Exception::class.java) { runBlocking { list(diskFolder(path = "Films/Private")) } }
            assertEquals(ScanErrorKind.ACCESS_DENIED, ScanErrors.classify(refused).kind)
        } finally {
            assertTrue(private.setReadable(true) && private.setExecutable(true) && private.setWritable(true))
        }
    }

    private companion object {
        /** Two dates in whole seconds, as every file system keeps them. */
        const val T1 = 1_760_000_000_000L
        const val T2 = T1 + 60_000L
    }
}
