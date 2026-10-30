package app.houselights.providers.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A folder of the player's own disks scanned as a NAS's folder is (#33): its films, their NFOs, artwork and tracks; a
 * file gone leaves as on a NAS; a disk unplugged is out of reach as a NAS asleep, nothing removed nor held, and read
 * again once back; a sudden drop while it answers is held as #76 holds a NAS's. A temporary folder stands for the disk.
 */
@RunWith(RobolectricTestRunner::class)
class ScanLocalDiskTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private val disks = TestDisks()

    /** Whether Android lets the app read the player's disks. */
    private var readable = true
    private val browser = LocalBrowser(disks) { readable }
    private lateinit var disk: File
    private lateinit var films: SourceEntity

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false)
        // As the app's browsers do at the end of each pass: the next one asks Android where each disk is.
        bench.onClose = browser::closeIdle
        disk = temp.newFolder(USB_DISK)
        disks.plug(USB_DISK, disk, "Samsung T7")
        films = bench.folder("Films", on = browser, stored = diskFolder("Films"))
    }

    @After
    fun close() = bench.close()

    private suspend fun paths() = bench.paths(films)

    private fun film(title: String) = disk.put("Films/$title/$title.mkv")

    /** Unplugged, as Android sees it: no longer listed, its files gone with it. */
    private fun unplug(): File {
        disks.unplug(USB_DISK)
        return File(temp.root, "away").also { check(disk.renameTo(it)) { "The disk stayed" } }
    }

    private fun plugBack(away: File) {
        check(away.renameTo(disk)) { "The disk did not come back" }
        disks.plug(USB_DISK, disk, "Samsung T7")
    }

    @Test
    fun aDisksFolderIsScannedAsANassIs() = runTest {
        disk.put("Films/Dune (2021)/Dune (2021).mkv", Matroska.sample())
        disk.put("Films/Dune (2021)/Dune (2021).nfo", "<movie><title>Dune Part One</title><year>2021</year></movie>".toByteArray())
        disk.put("Films/Dune (2021)/Dune (2021)-poster.jpg", ByteArray(100))
        bench.scan()

        assertNull(bench.error(films))
        val dune = bench.item(films, "Dune (2021)/Dune (2021).mkv")
        assertEquals("its NFO read", "Dune Part One", dune.title)
        assertEquals("Dune (2021)/Dune (2021).nfo", dune.nfoPath)
        assertTrue("its poster copied", dune.posterPath.isCachedArtwork())
        val info = checkNotNull(MediaProbe.decode(dune.mediaInfo)) { "its tracks never read" }
        assertEquals("HEVC" to 3840, info.video?.codec to info.video?.width)
        assertEquals(2 to 1, info.audio.size to info.subtitles.size)
        assertEquals("a pass finding nothing new changes nothing", 0, bench.changesDuring { bench.scan() })
    }

    /** A file deleted from the disk leaves the library at the next pass, as one deleted from a NAS. */
    @Test
    fun aFileGoneFromTheDiskLeaves() = runTest {
        film("Dune (2021)")
        film("Heat (1995)")
        bench.scan()
        assertEquals(listOf("Dune (2021)/Dune (2021).mkv", "Heat (1995)/Heat (1995).mkv"), paths())

        assertTrue(disk.resolve("Films/Heat (1995)").deleteRecursively())
        bench.scan()
        assertNull(bench.error(films))
        assertEquals(listOf("Dune (2021)/Dune (2021).mkv"), paths())
    }

    /**
     * A disk unplugged is a NAS asleep: the folder is out of reach, every title stays as it was, none held nor asked
     * about (the sheet of #76 is for files missing while the folder answers); plugged back, it is read again, unchanged.
     */
    @Test
    fun aDiskUnpluggedRemovesNothingAndIsReadAgainOnceBack() = runTest {
        val titles = (1..12).map { "Film $it (20${10 + it})" }
        titles.forEach(::film)
        bench.scan()
        val heat = bench.item(films, "Film 1 (2011)/Film 1 (2011).mkv")
        bench.dao.setFavorite(heat.id, true)

        val away = unplug()
        bench.scan()
        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(films))
        assertEquals(titles.map { "$it/$it.mkv" }.sorted(), paths())
        assertEquals("none held", emptyList<Long>(), bench.dao.heldIn(films.id))
        assertEquals("nothing for home to ask about", emptyList<HeldRow>(), bench.dao.observeHeld().first())
        assertFalse(checkNotNull(bench.dao.source(films.id)).heldKept)

        plugBack(away)
        assertEquals("read again, as it was", 0, bench.changesDuring { bench.scan() })
        assertNull(bench.error(films))
        assertEquals(titles.map { "$it/$it.mkv" }.sorted(), paths())
        assertTrue("what was done kept", bench.item(films, "Film 1 (2011)/Film 1 (2011).mkv").favorite)
    }

    /**
     * The disk plugged in but its folder not there: not found, yet unlike a NAS's folder gone nothing is held nor asked,
     * nothing removed, as with the disk unplugged; a disk's failures say no place on it.
     */
    @Test
    fun aDisksFolderNotThereHoldsNothing() = runTest {
        val titles = (1..12).map { "Film $it (20${10 + it})" }
        titles.forEach(::film)
        bench.scan()

        val away = File(temp.root, "Films away")
        check(disk.resolve("Films").renameTo(away)) { "The folder stayed" }
        bench.scan()
        bench.scan()
        assertEquals(ScanErrorKind.NOT_FOUND, bench.error(films))
        assertEquals(titles.map { "$it/$it.mkv" }.sorted(), paths())
        assertEquals("none held", emptyList<Long>(), bench.dao.heldIn(films.id))
        assertEquals("nothing for home to ask about", emptyList<HeldRow>(), bench.dao.observeHeld().first())
    }

    /**
     * The access withdrawn in Android's settings: the folder is refused, not read, as from Android 11 it would list its
     * folders but none of its files; every title stays, none held, and the folder is read again once it is granted.
     */
    @Test
    fun aDiskTheAppMayNoLongerReadIsRefusedAndKeepsItsTitles() = runTest {
        val titles = (1..8).map { "Film $it (20${10 + it})" }
        titles.forEach(::film)
        bench.scan()

        readable = false
        // What Android 11 then shows the app: the folders, and no file in them.
        val hidden = disk.walkBottomUp().filter { it.isFile }.associateWith { it.readBytes() to it.lastModified() }
        hidden.keys.forEach { check(it.delete()) { "$it stayed" } }
        bench.scan()
        assertEquals(ScanErrorKind.ACCESS_DENIED, bench.error(films))
        assertEquals(titles.map { "$it/$it.mkv" }.sorted(), paths())
        assertEquals("none held", emptyList<Long>(), bench.dao.heldIn(films.id))

        readable = true
        hidden.forEach { (file, kept) -> disk.put(file.relativeTo(disk).path, kept.first, modified = kept.second) }
        assertEquals("read again, as it was", 0, bench.changesDuring { bench.scan() })
        assertNull(bench.error(films))
    }

    /** Ten of twelve gone at once while the disk answers (another of its folders, a copy under way): held, as on a NAS. */
    @Test
    fun aSuddenDropWhileTheDiskAnswersIsHeldAsOnANas() = runTest {
        val titles = (1..12).map { "Film $it (20${10 + it})" }
        titles.forEach(::film)
        bench.scan()

        titles.drop(2).forEach { assertTrue(disk.resolve("Films/$it").deleteRecursively()) }
        bench.scan()
        assertNull(bench.error(films))
        assertEquals("nothing removed", 12, paths().size)
        assertEquals(10, bench.dao.heldIn(films.id).size)
    }
}
