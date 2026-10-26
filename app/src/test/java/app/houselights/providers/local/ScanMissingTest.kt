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
import java.net.ConnectException

/**
 * Rows of files a pass did not list: kept whole for a NAS off, an empty listing or a folder out of sight; removed on
 * the pass that misses them, what the user did kept by title; a sudden drop, as when a disk of the NAS's pool is
 * offline, held whatever the time until the files are back or the user answers, keeping them or removing them.
 */
@RunWith(RobolectricTestRunner::class)
class ScanMissingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val log = StatementLog()
    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"), tmdbKey = false, onQuery = log)
        films = bench.folder("Films")
    }

    @After
    fun close() = bench.close()

    private suspend fun filePaths() = bench.paths(films)

    private fun film(n: Int) = "Film $n (2020).mkv"

    private suspend fun filmFolder(count: Int) {
        repeat(count) { nas.put(film(it)) }
        bench.scan()
    }

    /** The rows held in [source], as its sheet lists them. */
    private suspend fun heldIds(source: SourceEntity = films) = dao.heldIn(source.id)

    private suspend fun folder() = checkNotNull(dao.source(films.id))

    private suspend fun missingSince(paths: List<String>) =
        dao.filesOf(films.id).filter { it.filePath in paths }.map { it.missingSince }.toSet()

    private suspend fun idOf(path: String) = dao.filesOf(films.id).single { it.filePath == path }.id

    @Test
    fun anUnreachableNasRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        dao.setFavorite(dao.filesOf(films.id).single().id, true)

        nas.listFailure = ConnectException("Connection refused")
        bench.scan()

        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(films))
        assertTrue(dao.filesOf(films.id).single().favorite)
    }

    @Test
    fun anEmptyListingRemovesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        bench.scan()
        val dune = dao.filesOf(films.id).first { it.title == "Dune" }
        dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = bench.now)
        dao.setFavorite(dune.id, true)

        // The share answers, but its disk is not mounted.
        nas.clear()
        bench.scan()

        assertEquals(ScanErrorKind.EMPTY, bench.error(films))
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
        val kept = checkNotNull(dao.item(dune.id))
        assertEquals(600_000L, kept.positionMs)
        assertTrue(kept.favorite)
        assertEquals(null, kept.missingSince)
    }

    /** lost+found, another user's folder: what the library holds under it is out of sight, not gone. */
    @Test
    fun aFolderThatCannotBeReadRemovesNothingUnderIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Private/Arrival (2016)/Arrival (2016).mkv")
        bench.scan()

        nas.unreadable = listOf("Private")
        nas.put("Blade Runner (1982)/Blade Runner (1982).mkv")
        bench.scan()
        bench.later(DAY)
        bench.scan()

        assertEquals(null, bench.error(films))
        assertEquals(
            listOf(
                "Blade Runner (1982)/Blade Runner (1982).mkv",
                "Dune (2021)/Dune (2021).mkv",
                "Private/Arrival (2016)/Arrival (2016).mkv",
            ),
            filePaths(),
        )
        assertEquals("not even held", null, dao.filesOf(films.id).single { it.title == "Arrival" }.missingSince)
    }

    @Test
    fun aFolderNeverFilledMayListEmpty() = runTest {
        bench.scan()
        assertEquals(null, bench.error(films))
    }

    /** Twelve of twenty-two at once: nothing leaves, then or a month later; they are held from that pass, and asked about. */
    @Test
    fun aSuddenDropRemovesNothingWhateverTheTime() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        offline.forEach(nas::remove)
        bench.scan()
        assertEquals(22, filePaths().size)
        assertEquals(setOf(START), missingSince(offline))
        assertEquals(null, bench.error(films))
        assertFalse("asked about", folder().heldKept)

        bench.later(30 * DAY)
        bench.scan()
        assertEquals("no clock decides", 22, filePaths().size)
        assertEquals("held since they went", setOf(START), missingSince(offline))
        assertEquals(offline.sorted(), dao.observeHeld().first().mapNotNull { it.filePath }.sorted())
    }

    /** Ten of forty, a quarter: no drop, they go on the pass that misses them. */
    @Test
    fun fewFilesGoneGoOnTheNextPass() = runTest {
        filmFolder(40)
        (0 until 10).map(::film).forEach(nas::remove)
        bench.scan()
        assertEquals((10 until 40).map(::film).sorted(), filePaths())
    }

    /** A film deleted goes on the pass that misses it, the app's own after a restart included; what the user did is kept. */
    @Test
    fun aFileDeletedIsRemovedOnTheNextPass() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        bench.scan()
        dao.setFavorite(dao.filesOf(films.id).single { it.title == "Dune" }.id, true)

        nas.remove("Dune (2021).mkv")
        bench.autoScan()
        assertEquals(listOf("Heat (1995).mkv"), filePaths())
        assertEquals(listOf("film:name:${films.id}::dune|2021"), bench.keptTitles())
    }

    /** The files back end the hold without a word: every row as it was, nothing held, nothing to answer. */
    @Test
    fun filesBackEndTheHold() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        val favourite = idOf(film(0))
        dao.setFavorite(favourite, true)
        offline.forEach(nas::remove)
        bench.scan()

        offline.forEach { nas.put(it) }
        val told = bench.changesDuring { bench.scan() }
        assertEquals(setOf(null), missingSince(offline))
        assertEquals(22, filePaths().size)
        assertTrue(checkNotNull(dao.item(favourite)).favorite)
        assertEquals(emptyList<HeldRow>(), dao.observeHeld().first())
        assertTrue("screens no longer mark them", told > 0)
    }

    /** Back but one, deleted meanwhile: that one stays held, its answer awaited, however long. */
    @Test
    fun aTitleStillMissingOnceTheOthersAreBackWaitsForTheAnswer() = runTest {
        filmFolder(40)
        val offline = (0 until 21).map(::film)
        offline.forEach(nas::remove)
        bench.scan()

        offline.drop(1).forEach { nas.put(it) }
        bench.later(30 * DAY)
        bench.scan()
        assertEquals(40, filePaths().size)
        assertEquals(listOf(film(0)), dao.observeHeld().first().map { it.filePath })
        assertFalse(folder().heldKept)
    }

    /** « Garder »: they stay held and nothing asks again, until another drop, which asks about them all. */
    @Test
    fun keptTheyWaitForTheirFilesUntilAnotherDrop() = runTest {
        filmFolder(40)
        (0 until 12).map(::film).forEach(nas::remove)
        bench.scan()
        bench.scanner.keepHeld(films.id, heldIds())
        assertTrue(folder().heldKept)

        bench.later(DAY)
        bench.scan()
        assertTrue("still kept", folder().heldKept)
        assertEquals(40, filePaths().size)

        (12 until 24).map(::film).forEach(nas::remove)
        bench.scan()
        assertFalse("asked again", folder().heldKept)
        assertEquals(24, heldIds().size)
    }

    /** Kept as shown: a drop held after the sheet was read is still asked about. */
    @Test
    fun keepingKeepsWhatWasShownOnly() = runTest {
        filmFolder(40)
        (0 until 12).map(::film).forEach(nas::remove)
        bench.scan()
        val shown = heldIds()
        (12 until 24).map(::film).forEach(nas::remove)
        bench.scan()

        bench.scanner.keepHeld(films.id, shown)
        assertFalse(folder().heldKept)
        bench.scanner.keepHeld(films.id, heldIds())
        assertTrue(folder().heldKept)
    }

    /** « Retirer »: they leave at once, nothing on the NAS touched, what the user did kept by title for when they return. */
    @Test
    fun removedTheyLeaveKeepingWhatTheUserDid() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        dao.setFavorite(idOf(film(0)), true)
        offline.forEach(nas::remove)
        bench.scan()

        val told = bench.changesDuring { bench.scanner.removeHeld(heldIds()) }
        assertEquals((12 until 22).map(::film).sorted(), filePaths())
        assertTrue("screens told", told > 0)
        assertEquals("only what the user did: ${bench.keptTitles()}", 1, bench.keptTitles().size)
        assertEquals(emptyList<HeldRow>(), dao.observeHeld().first())

        // Back, it is the film it was.
        nas.put(film(0))
        bench.scan()
        assertTrue(dao.filesOf(films.id).single { it.filePath == film(0) }.favorite)
    }

    /** One title held removed from its page: it alone leaves; a title there is never removed so. */
    @Test
    fun aTitleHeldRemovedAloneLeavesTheOthersHeld() = runTest {
        filmFolder(22)
        (0 until 12).map(::film).forEach(nas::remove)
        bench.scan()

        bench.scanner.removeHeldTitle(idOf(film(0)))
        assertEquals(21, filePaths().size)
        assertEquals(11, heldIds().size)
        bench.scanner.removeHeldTitle(idOf(film(20)))
        assertEquals(21, filePaths().size)
    }

    /** Back meanwhile: an answer given for them removes nothing. */
    @Test
    fun removingTitlesBackRemovesNothing() = runTest {
        filmFolder(22)
        val offline = (0 until 12).map(::film)
        offline.forEach(nas::remove)
        bench.scan()
        val shown = heldIds()
        offline.forEach { nas.put(it) }
        bench.scan()

        bench.scanner.removeHeld(shown)
        assertEquals(22, filePaths().size)
    }

    /** A show's episodes held, then removed: the show goes with them, what the user did to it kept. */
    @Test
    fun aShowWhoseEpisodesHeldAreRemovedGoesWithThem() = runTest {
        val series = bench.folder("Séries", SourceKind.SHOWS)
        val gone = (1..12).map { "MobLand/Season 1/MobLand - S01E${"%02d".format(it)}.mkv" }
        gone.forEach { bench.showsNas.put(it) }
        (1..10).forEach { bench.showsNas.put("Severance/Season 1/Severance - S01E${"%02d".format(it)}.mkv") }
        bench.scan()
        dao.setFavorite(bench.series("MobLand").id, true)

        gone.forEach { bench.showsNas.remove(it) }
        bench.scan()
        assertEquals(12, heldIds(series).size)

        bench.scanner.removeHeld(heldIds(series))
        assertTrue(bench.paths(series).none { it.startsWith("MobLand/") })
        assertEquals(10, bench.paths(series).size)
        assertNull(dao.seriesByKey(FilenameParser.showKey("MobLand", null)))
        assertTrue(bench.keptTitles().toString(), bench.keptTitles().any { it.startsWith("show:name:") })
    }

    @Test
    fun moreFilesGoneThanAStatementTakes() = runTest {
        filmFolder(1_201)
        (0 until 1_200).map(::film).forEach(nas::remove)
        log.reset()
        bench.scan()
        assertEquals(null, bench.error(films))
        assertEquals("held", 1_201, filePaths().size)

        bench.scanner.keepHeld(films.id, heldIds())
        bench.scanner.removeHeld(heldIds())
        assertEquals(listOf(film(1_200)), filePaths())
        log.assertNoStatementTooWideForAndroid9()
    }

    private companion object {
        const val START = ScanBench.START
        const val DAY = 24 * 60 * 60_000L
    }
}
