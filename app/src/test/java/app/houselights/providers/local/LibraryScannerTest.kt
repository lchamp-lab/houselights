package app.houselights.providers.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration

/** Whole passes over folders on a NAS held in memory, without TMDB: when they run, what they wait for and write. */
@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {
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

    /** Scans unless one ended less than ten minutes ago, as the app does when it returns, and awaits it. */
    private suspend fun scanIfStale() = bench.autoScan(maxAgeMs = TEN_MINUTES)

    /** Time passes since boot, as the scanner measures how long ago a pass ended. */
    private fun elapse(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    /** A pass tells screens of its progress as it writes, and of the change once, at its end. */
    @Test
    fun aPassTellsItsProgressApartFromItsEnd() = runTest {
        repeat(12) { nas.put("Film $it (2001).mkv") }
        val told = bench.toldDuring { bench.scan() }
        assertTrue("progress told", told.progress > 0)
        assertEquals("the change told once", 1, told.changes)
    }

    /**
     * A pass that begins on an empty library is its very first scan, which screens follow closer while it writes; the next
     * one, over the titles it found, is not, nor is any once it ends.
     */
    @Test
    fun aPassOnAnEmptyLibraryIsItsFirstScan() = runTest {
        repeat(12) { nas.put("Film $it (2001).mkv") }
        assertEquals(listOf(true), firstScanAsItWrites())
        assertFalse("over", bench.scanner.state.value.firstScan)

        repeat(12) { nas.put("Film ${it + 12} (2002).mkv") }
        assertEquals(listOf(false), firstScanAsItWrites())
    }

    /** Whether the pass is the library's first, as each of its progress says it, once each. */
    private suspend fun firstScanAsItWrites(): List<Boolean> {
        val seen = mutableSetOf<Boolean>()
        coroutineScope {
            val watch = launch(Dispatchers.Unconfined) { bench.scanner.progress.collect { seen += bench.scanner.state.value.firstScan } }
            bench.scan()
            watch.cancel()
        }
        return seen.toList()
    }

    @Test
    fun indexesFilmsAndShowsThenReadsNothingAgain() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        bench.showsNas.put("Severance/Season 1/Severance - S01E02.mkv")
        bench.scan()

        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021)/Dune (2021).mkv"), filePaths())
        assertEquals(listOf("Arrival", "Dune"), dao.filesOf(films.id).map { it.title }.sorted())
        val episodes = dao.filesOf(shows.id).sortedBy { it.episodeNumber }
        assertEquals(listOf(1, 2), episodes.map { it.episodeNumber })
        assertEquals("Severance", dao.item(episodes.first().seriesId!!)?.title)
        assertEquals(4, nas.reads.size + bench.showsNas.reads.size)

        assertEquals("a pass with nothing new reads no file", emptyList<String>(), bench.pass().reads)
    }

    /** Every ten minutes, on thousands of files, a pass finding nothing new reads only what tells them apart. */
    @Test
    fun aPassFindingNothingNewReadsNeitherDetailsNorWhatFilesHold() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.put("Arrival (2016).mkv")
        bench.scan()
        log.recording = true
        bench.scan()
        log.recording = false
        val items = Regex("""\bitems\b""")
        val reads = log.recorded.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) && items.containsMatchIn(it) }
        assertTrue("the files known are read", reads.isNotEmpty())
        assertEquals(emptyList<String>(), reads.filter { "mediaInfo" in it || "overview" in it })
    }

    @Test
    fun theFirstScanAskedRunsEvenMomentsAfterTheDeviceStarted() = runTest {
        nas.put("Dune (2021).mkv")
        scanIfStale()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aScanEndedMomentsAgoIsNotRunAgain() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        nas.put("Arrival (2016).mkv")
        elapse(TEN_MINUTES - 1)
        scanIfStale()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())

        elapse(1)
        scanIfStale()
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
    }

    @Test
    fun theDateSetBackDoesNotHoldScans() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        nas.put("Arrival (2016).mkv")
        // The date goes back three hours; eleven minutes pass.
        bench.now -= 3 * HOUR
        elapse(TEN_MINUTES + 60_000)
        scanIfStale()
        assertEquals(listOf("Arrival (2016).mkv", "Dune (2021).mkv"), filePaths())
    }

    /** The app checks every ten minutes: a pass that lasts longer (a first scan) is not followed by another. */
    @Test
    fun aScanUnderWayIsNotFollowedByAnother() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        // The pass is under way, waiting for the film that plays.
        bench.playing.value = true
        bench.scanner.scanAll()
        bench.playing.subscriptionCount.first { it > 0 }
        elapse(TEN_MINUTES)
        bench.scanner.scanIfStale(TEN_MINUTES)

        bench.playing.value = false
        bench.awaitScans()
        assertEquals("one pass", 1, listed)
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aScanWaitsForTheFilmPlayingBeforeListingAFolder() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        bench.playing.value = true
        bench.scanner.scanAll()
        bench.playing.subscriptionCount.first { it > 0 }
        assertEquals(0, listed)

        bench.playing.value = false
        bench.awaitScans()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aPassCutShortLetsTheNextOneStart() = runTest {
        nas.put("Dune (2021).mkv")
        val listing = CompletableDeferred<Unit>()
        nas.onList = {
            listing.complete(Unit)
            awaitCancellation()
        }
        bench.scanner.scanAll()
        listing.await()
        bench.cancelScans()

        nas.onList = null
        bench.scan()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    @Test
    fun aNasThatStopsAnsweringEndsThePassAfterThreeFiles() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()

        repeat(5) { nas.put("New $it (2024).mkv") }
        nas.readFailure = SocketTimeoutException("timeout")
        nas.reads.clear()
        bench.scan()

        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(films))
        assertEquals("gives up after three files, each with long timeouts", 3, nas.reads.size)
        assertTrue("Dune (2021).mkv" in filePaths())

        // Files it gave up on are read at the next pass.
        nas.readFailure = null
        assertEquals(5, bench.pass().reads.size)
        assertEquals(null, bench.error(films))
    }

    @Test
    fun twoFoldersOfOneNameKeepTheirOwnState() = runTest {
        // "Films" on two NAS: the second one is off.
        val otherNas = FakeRemoteBrowser().apply { listFailure = ConnectException("Connection refused") }
        val other = bench.folder("Films", on = otherNas)
        nas.put("Dune (2021).mkv")
        val read = mutableListOf<Long?>()
        nas.onList = { read += bench.scanner.state.value.sourceId }
        bench.scan()

        assertEquals(listOf<Long?>(films.id), read)
        assertEquals(ScanErrorKind.UNREACHABLE, bench.error(other))
        assertEquals("the folder read is not said off", null, bench.error(films))
    }

    private suspend fun shareWithPassword(): SourceEntity = checkNotNull(
        dao.source(
            dao.insertSource(
                SourceEntity(
                    name = "Partage",
                    kind = SourceKind.MOVIES,
                    protocol = Protocol.SMB,
                    host = "nas",
                    share = "media",
                    path = "films",
                    username = "zidoo",
                    password = Secret("s3cret"),
                ),
            ),
        ),
    )

    private fun sealedPassword(source: SourceEntity): String =
        bench.db.openHelper.readableDatabase.query("SELECT password FROM sources WHERE id = ${source.id}").use {
            check(it.moveToFirst())
            it.getString(0)
        }

    @Test
    fun theEndOfAScanWritesItsTimesAlone() = runTest {
        val share = shareWithPassword()
        val sealed = sealedPassword(share)
        nas.put("Dune (2021).mkv")
        bench.scan()
        assertEquals(START to START, checkNotNull(dao.source(share.id)).let { it.lastScanAt to it.firstScanAt })

        bench.now += HOUR
        bench.scan()
        val scanned = checkNotNull(dao.source(share.id))
        assertEquals(START + HOUR to START, scanned.lastScanAt to scanned.firstScanAt)
        assertEquals("the password is not sealed again", sealed, sealedPassword(share))
        assertEquals("s3cret", scanned.password.value)
    }

    @Test
    fun aFolderEditedDuringAScanKeepsTheEdit() = runTest {
        val share = shareWithPassword()
        nas.put("Dune (2021).mkv")
        // The NAS moves to another address, and the user enters it while a scan lists the folder.
        nas.onList = { dao.updateSource(checkNotNull(dao.source(share.id)).copy(host = "192.168.1.20")) }
        bench.scan()
        val edited = checkNotNull(dao.source(share.id))
        assertEquals("192.168.1.20", edited.host)
        assertEquals(START, edited.lastScanAt)
    }

    private companion object {
        const val START = ScanBench.START
        const val HOUR = 60 * 60_000L
        const val TEN_MINUTES = 10 * 60_000L
    }
}
