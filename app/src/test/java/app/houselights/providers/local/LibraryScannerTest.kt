package app.houselights.providers.local

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.Collections

/** Whole passes over folders on a NAS held in memory, without TMDB: when they run, what they wait for and write. */
@RunWith(RobolectricTestRunner::class)
class LibraryScannerTest {
    private val log = StatementLog()

    @get:Rule
    val scan = ScanBenchRule(tmdbKey = false, onQuery = log)

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
    }

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
     * The very first scan is followed closer only while home's row of latest films fills: once it holds all it shows,
     * the rest of the pass is told of as any other pass is, the wall then past its first titles.
     */
    @Test
    fun theFirstScanIsFollowedCloserOnlyWhileHomesRowFills() = runTest {
        repeat(40) { nas.put("Film $it (2001).mkv") }
        val told = homeFillingAsItWrites()
        assertEquals("closer, then as any pass", listOf(true, false), told.map { it.filling }.distinct())
        assertTrue("closer while the row fills: $told", told.filter { it.filling }.all { it.films < LocalProvider.LATEST })
        assertTrue("as any pass once it is full: $told", told.filterNot { it.filling }.all { it.films >= LocalProvider.LATEST })
    }

    /**
     * The library's very first pass stays its first scan until it ends, past home's rows full, where screens follow it as
     * any pass ([ScanState.homeFilling]); the next, over the titles it found, is not, nor is any once it ends.
     */
    @Test
    fun theFirstScanLastsItsWholePass() = runTest {
        repeat(40) { nas.put("Film $it (2001).mkv") }
        val first = statesAsItRuns().filter { it.running }
        assertEquals("closer, then as any pass", listOf(true, false), first.map { it.homeFilling }.distinct())
        assertEquals("its first scan all along", listOf(true), first.map { it.firstPass }.distinct())
        assertFalse("over", bench.scanner.state.value.firstPass)

        assertFalse(bench.scanner.state.value.homeFilling)

        nas.put("Film 40 (2002).mkv")
        val next = statesAsItRuns().filter { it.running }
        assertEquals(listOf(false), next.map { it.firstPass }.distinct())
        assertEquals("never followed closer", listOf(false), next.map { it.homeFilling }.distinct())
    }

    /**
     * Once every folder's files are read, the pass says it is finishing while what is left of it goes on, never while a
     * file is read; its end says it no more.
     */
    @Test
    fun aPassSaysItIsFinishingOnceEveryFoldersFilesAreRead() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        repeat(6) { nas.put("Film $it (2001).mkv") }
        bench.showsNas.put("Severance/Season 1/Severance - S01E01.mkv")
        val states = statesAsItRuns()
        val at = states.indexOfFirst { it.finishing }
        assertTrue("finishing as it runs: $states", at > 0 && states[at].running)
        assertEquals("after the last folder's files", shows.id, states[at - 1].sourceId)
        assertTrue("nothing read once finishing", states.drop(at).filter { it.running }.all { it.finishing && it == states[at] })
        assertFalse("its end", bench.scanner.state.value.finishing)
    }

    /**
     * While a folder is listed, the videos found so far reach screens at most once a second, the listing's start counting
     * as one, a disc once and a film's trailer never: a word that changes, never figures scrolling.
     */
    @Test
    fun theVideosAFolderListsAreToldAtMostOnceASecond() = runTest {
        repeat(9) { nas.put("Film $it (2001)/Film $it (2001).mkv") }
        nas.put("Film 0 (2001)/Film 0 (2001)-trailer.mkv")
        nas.put("Heat (1995)/BDMV/STREAM/00001.m2ts")
        nas.put("Heat (1995)/BDMV/STREAM/00002.m2ts")
        // The NAS lists a film's folder each 400 ms.
        nas.onFound = { SystemClock.sleep(LISTED_EACH_MS) }
        val listing = statesAsItRuns().filter { it.running && it.total == 0 }.map { it.listed }.distinct()
        assertEquals("at 1.2 s, 2.4 s and 3.6 s: ten videos, a disc once", listOf(0, 4, 7, 10), listing)
    }

    /** A folder listed in less than a second says no count: the files are counted at once. */
    @Test
    fun aFolderListedAtOnceSaysNoCount() = runTest {
        repeat(9) { nas.put("Film $it (2001).mkv") }
        assertEquals(listOf(0), statesAsItRuns().filter { it.running }.map { it.listed }.distinct())
    }

    /** Every state a pass goes through, in order, each read as it is written. */
    private suspend fun statesAsItRuns(): List<ScanState> {
        val states = Collections.synchronizedList(mutableListOf<ScanState>())
        coroutineScope {
            val watch = launch(Dispatchers.Unconfined) { bench.scanner.state.collect { states += it } }
            bench.scan()
            watch.cancel()
        }
        return states.toList()
    }

    /** What a pass told screens at one of its progress: whether home was still filling, and how many films it had read. */
    private data class Told(val filling: Boolean, val films: Int)

    /** What each progress of a pass told ([Told]), in order, each read as it is told, a film a file. */
    private suspend fun homeFillingAsItWrites(): List<Told> {
        val told = mutableListOf<Told>()
        coroutineScope {
            val watch = launch(Dispatchers.Unconfined) {
                bench.scanner.progress.collect {
                    val state = bench.scanner.state.value
                    told += Told(state.homeFilling, state.processed + 1)
                }
            }
            bench.scan()
            watch.cancel()
        }
        return told
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

    /**
     * The app left while a folder lists, another app perhaps playing from the NAS: the listing stops between two folders,
     * its session closed and nothing read, and lists the folder anew once the app is back.
     */
    @Test
    fun aListingStopsWhenTheAppLeavesAndBeginsAnewOnceItIsBack() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { if (++listed == 1) bench.started.value = false }
        val waiting = CompletableDeferred<Unit>()
        bench.onClose = { waiting.complete(Unit) }
        bench.scanner.scanAll()
        waiting.await()
        assertEquals("stopped as the app left", 1, listed)
        assertEquals("nothing read", emptyList<String>(), nas.reads.toList())
        assertEquals(emptyList<String>(), filePaths())

        bench.started.value = true
        bench.awaitScans()
        assertEquals("listed anew", 2, listed)
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    /**
     * Android's screensaver over the app is no other player reading the NAS: a pass waiting for the app to be on screen
     * goes on under it, so a first scan of hours does not stop at the first one.
     */
    @Test
    fun aScanGoesOnUnderAndroidsScreensaver() = runTest {
        nas.put("Dune (2021).mkv")
        var listed = 0
        nas.onList = { listed++ }
        bench.started.value = false
        bench.scanner.scanAll()
        bench.started.subscriptionCount.first { it > 0 }
        assertEquals("the app left: it waits", 0, listed)

        bench.dreaming.value = true
        bench.awaitScans()
        assertEquals(listOf("Dune (2021).mkv"), filePaths())
    }

    /** Scans asked twice while a pass lists, a refresh the second time: one more pass follows, and it refreshes. */
    @Test
    fun scansAskedDuringAPassQueueOneMoreThatRefreshesIfEitherAsked() = runTest {
        nas.put("Dune (2021).mkv")
        nas.putText("Dune (2021).nfo", "<movie><title>Dune</title></movie>")
        bench.scan()
        var listed = 0
        val listing = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        nas.onList = {
            if (++listed == 1) {
                listing.complete(Unit)
                go.await()
            }
        }
        nas.reads.clear()
        bench.scanner.scanAll()
        listing.await()
        bench.scanner.scanAll()
        bench.scanner.scanAll(refreshMetadata = true)
        go.complete(Unit)
        bench.awaitScans()

        assertEquals("one more pass", 2, listed)
        assertEquals("its NFO read again by the refresh", 1, nas.reads.count { it == "Dune (2021).nfo" })
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

    /**
     * A NAS that stops answering as a pass reads its headers, its posters or its NFOs: each folder gives up after three
     * files, every one a long timeout, never reading one twice; the files it gave up on are read at the next pass.
     */
    @Test
    fun aNasThatStopsAnsweringEndsThePassAfterThreeFiles() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        repeat(5) { nas.put("New $it (2024).mkv") }
        // Files the probe does not open: their posters alone, or their NFOs alone, are read.
        val posters = FakeRemoteBrowser()
        val nfos = FakeRemoteBrowser()
        repeat(5) {
            posters.put("New $it (2024).mp4")
            posters.putImage("New $it (2024)-poster.jpg")
            nfos.put("New $it (2024).mp4")
            nfos.putText("New $it (2024).nfo", "<movie><title>New $it</title></movie>")
        }
        val withPosters = bench.folder("Films affiches", on = posters)
        val withNfos = bench.folder("Films NFO", on = nfos)
        listOf(nas, posters, nfos).forEach { it.readFailure = SocketTimeoutException("timeout") }
        nas.reads.clear()
        bench.scan()

        for ((folder, browser) in listOf(films to nas, withPosters to posters, withNfos to nfos)) {
            assertEquals(folder.name, ScanErrorKind.UNREACHABLE, bench.error(folder))
            assertEquals("${folder.name}: gives up after three files", 3, browser.reads.size)
            assertEquals("${folder.name}: each read once", browser.reads.distinct(), browser.reads)
        }
        assertTrue("Dune (2021).mkv" in filePaths())

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

        /** A film's folder listed, on a NAS listing one folder a request. */
        const val LISTED_EACH_MS = 400L
    }
}
