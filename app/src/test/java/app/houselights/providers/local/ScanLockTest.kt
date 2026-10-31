package app.houselights.providers.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A pass holds the library's lock only to read and write it: while it waits for TMDB or the NAS, what the user does
 * goes through at once, and the pass then writes what the library has become, never an answer collected for another.
 */
@RunWith(RobolectricTestRunner::class)
class ScanLockTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(DUNE_1984, "Dune", 1984)
        tmdb.movie(HEAT, "Heat", 1995)
    }

    @After
    fun close() = bench.close()

    /** Holds TMDB's searches until [Gate.open], on OkHttp's thread; identifications by id go through. */
    private fun holdSearches(): Gate = Gate().also { gate ->
        tmdb.onRequest = { path -> if (path.startsWith("search/")) gate.pass() }
    }

    private class Gate {
        private val reached = CountDownLatch(1)
        private val opened = CountDownLatch(1)

        fun pass() {
            reached.countDown()
            opened.await(WAIT_MS, TimeUnit.MILLISECONDS)
        }

        suspend fun reached() = withContext(Dispatchers.IO) { assertTrue("held", reached.await(WAIT_MS, TimeUnit.MILLISECONDS)) }

        fun open() = opened.countDown()
    }

    /** [block], which must end while the pass waits: in real time, as the pass runs on threads of its own. */
    private suspend fun <T> meanwhile(block: suspend () -> T): T = withContext(Dispatchers.IO) { withTimeout(WAIT_MS) { block() } }

    /** Twelve films, then eleven gone at once: held, asked about ([MissingFiles.isDrop]), the twelfth there. */
    private suspend fun aDrop(): ItemEntity {
        repeat(DROPPED + 1) { nas.put(film(it)) }
        bench.scan()
        repeat(DROPPED) { nas.remove(film(it)) }
        return bench.item(films, film(DROPPED))
    }

    private fun film(n: Int) = "Film $n (2020).mkv"

    @Test
    fun anIdentificationAndKeepingWhatIsHeldEndWhileTheScanWaitsForTmdb() = runTest {
        val kept = aDrop()
        nas.put("Dune (2021).mkv")
        val gate = holdSearches()
        bench.scanner.scanAll()
        try {
            gate.reached()
            meanwhile {
                bench.scanner.identify(kept.id, HEAT)
                bench.scanner.keepHeld(films.id, dao.heldIn(films.id))
            }
        } finally {
            gate.open()
        }
        bench.awaitScans()

        assertEquals(HEAT, bench.item(films, film(DROPPED)).tmdbId)
        assertTrue("kept", checkNotNull(dao.source(films.id)).heldKept)
        assertEquals("the scan ended as ever", DUNE, bench.item(films, "Dune (2021).mkv").tmdbId)
    }

    @Test
    fun anIdentificationAndRemovingATitleHeldEndWhileTheScanReadsAHeader() = runTest {
        val kept = aDrop()
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        val reading = CompletableDeferred<Unit>()
        val read = CompletableDeferred<Unit>()
        nas.onRead = { path, offset, _ ->
            if (path == "Dune (2021).mkv" && offset == 0L) {
                reading.complete(Unit)
                read.await()
            }
        }
        bench.scanner.scanAll()
        try {
            meanwhile {
                reading.await()
                bench.scanner.identify(kept.id, HEAT)
                assertNull("removed", bench.scanner.removeHeldTitle(dao.heldIn(films.id).first()))
            }
        } finally {
            read.complete(Unit)
        }
        bench.awaitScans()

        assertEquals(HEAT, bench.item(films, film(DROPPED)).tmdbId)
        assertEquals(DROPPED - 1, dao.heldIn(films.id).size)
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("the scan ended as ever", DUNE to MediaProbe.VERSION, dune.tmdbId to dune.probeVersion)
    }

    /** Identified while TMDB was asked for it by its name: the scan does not write its answer over the user's choice. */
    @Test
    fun aFilmIdentifiedWhileTheScanAsksForItKeepsTheUsersMatch() = runTest {
        nas.put("Dune (2021).mkv")
        val gate = holdSearches()
        bench.scanner.scanAll()
        try {
            gate.reached()
            // Its row is there from the start, as screens show it.
            val dune = bench.item(films, "Dune (2021).mkv")
            meanwhile { bench.scanner.identify(dune.id, DUNE_1984) }
        } finally {
            gate.open()
        }
        bench.awaitScans()

        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(DUNE_1984 to DUNE_1984, dune.userTmdbId to dune.tmdbId)
        assertEquals(1984, dune.year)
    }

    @Test
    fun aFolderRemovedWhileTheScanAsksTmdbGetsNoRow() = runTest {
        nas.put("Dune (2021).mkv")
        nas.put("Heat (1995).mkv")
        val gate = holdSearches()
        bench.scanner.scanAll()
        try {
            gate.reached()
            meanwhile { bench.scanner.removeSource(films) }
        } finally {
            gate.open()
        }
        bench.awaitScans()

        assertNull(dao.source(films.id))
        assertEquals(emptyList<KnownFile>(), dao.knownFiles(films.id))
    }

    /** A season's poster copied, the episode's NFO read next: a cleanup then spares the copy no row holds yet. */
    @Test
    fun aCleanupWhileAnEpisodeIsReadLeavesItsSeasonsPoster() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(SEVERANCE, "Severance", 2022)
        tmdb.season(SEVERANCE, 1, episodes = 9)
        bench.showsNas.put(EPISODE)
        bench.showsNas.putText(EPISODE.replace(".mkv", ".nfo"), "<episodedetails><title>Good News About Hell</title></episodedetails>")
        bench.showsNas.putImage("Severance/season01-poster.jpg")
        val reading = CompletableDeferred<Unit>()
        val read = CompletableDeferred<Unit>()
        bench.showsNas.onRead = { path, _, _ ->
            if (path.endsWith(".nfo")) {
                reading.complete(Unit)
                read.await()
            }
        }
        bench.scanner.scanAll()
        try {
            meanwhile {
                reading.await()
                bench.scanner.cleanup()
            }
        } finally {
            read.complete(Unit)
        }
        bench.awaitScans()

        val season = checkNotNull(dao.season(bench.showOf(shows, EPISODE).id, 1))
        val poster = checkNotNull(season.posterPath)
        assertTrue(poster, poster.isCachedArtwork() && File(poster.removePrefix("file://")).isFile)
        assertEquals("Good News About Hell", bench.item(shows, EPISODE).title)
    }

    /** A cleanup while a film's poster is copied: the copy, not referenced yet, is spared, written or being written. */
    @Test
    fun aCleanupWhileAPosterIsCopiedLeavesIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.putImage("Dune (2021)/poster.jpg")
        val copying = CompletableDeferred<Unit>()
        val copied = CompletableDeferred<Unit>()
        nas.onRead = { path, _, _ ->
            if (path.endsWith("poster.jpg")) {
                copying.complete(Unit)
                copied.await()
            }
        }
        bench.scanner.scanAll()
        try {
            meanwhile {
                copying.await()
                bench.scanner.cleanup()
            }
        } finally {
            copied.complete(Unit)
        }
        bench.awaitScans()

        val poster = checkNotNull(bench.item(films, "Dune (2021)/Dune (2021).mkv").posterPath)
        assertTrue(poster, poster.isCachedArtwork() && File(poster.removePrefix("file://")).isFile)
    }

    /** Left as it is and marked a favourite while TMDB was searched for it: the search's answer is not written. */
    @Test
    fun aFilmLeftAsItIsWhileTheScanSearchesForItStaysUnidentified() = runTest {
        nas.put("Dune (2021).mkv")
        val gate = holdSearches()
        bench.scanner.scanAll()
        try {
            gate.reached()
            val dune = bench.item(films, "Dune (2021).mkv")
            dao.setUserNoMatch(dune.id, true)
            dao.setFavorite(dune.id, true)
        } finally {
            gate.open()
        }
        bench.awaitScans()

        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(Triple(null, true, true), Triple(dune.tmdbId, dune.userNoMatch, dune.favorite))
    }

    /** A pass cut short while TMDB is asked leaves the lock free and the film's row as it was born; the next pass reads it. */
    @Test
    fun aPassCancelledWhileTmdbIsAskedWritesNothingHalfAndFreesTheLock() = runTest {
        nas.put("Heat (1995).mkv")
        bench.scan()
        nas.put("Dune (2021).mkv")
        tmdb.hold = true
        bench.scanner.scanAll()
        withContext(Dispatchers.IO) { assertTrue("held", tmdb.held.await(WAIT_MS, TimeUnit.MILLISECONDS)) }
        bench.cancelScans()
        tmdb.hold = false

        meanwhile { bench.scanner.identify(bench.item(films, "Heat (1995).mkv").id, HEAT) }
        val born = bench.item(films, "Dune (2021).mkv")
        assertEquals(null to null, born.tmdbId to born.matchedAt)
        bench.scan()
        assertEquals(DUNE, bench.item(films, "Dune (2021).mkv").tmdbId)
    }

    /**
     * A film asking the NAS and TMDB all a film can, its run under the lock replayed at each answer: each file read once,
     * the header as one probe reads it, and each request asked once.
     */
    @Test
    fun aFilmReadInRoundsReadsAndAsksEachThingOnce() = runTest {
        tmdb.editMovie(DUNE) { it.copy(belongsToCollection = TmdbCollectionRef(DUNE_SAGA, "Dune Collection")) }
        tmdb.collections[DUNE_SAGA] = TmdbCollection(DUNE_SAGA, "Dune Collection", overview = "Arrakis")
        val bytes = Matroska.sample()
        nas.put(DUNE_FILE, size = DUNE_SIZE, bytes = bytes)
        nas.putText("Dune (2021)/Dune (2021).nfo", "<movie><title>Dune</title><uniqueid type=\"tmdb\">$DUNE</uniqueid></movie>")
        nas.putImage("Dune (2021)/poster.jpg")
        nas.putImage("Dune (2021)/fanart.jpg")

        val pass = bench.pass()

        assertEquals(pass.requests.distinct(), pass.requests)
        assertTrue(pass.requests.toString(), "collection/$DUNE_SAGA" in pass.requests)
        val alone = FakeRemoteBrowser().apply { put(DUNE_FILE, size = DUNE_SIZE, bytes = bytes) }
        MediaProbe.probe(films, RemoteFile(DUNE_FILE, DUNE_SIZE), alone)
        val reads = pass.reads.groupingBy { it }.eachCount()
        assertEquals(
            mapOf("Dune (2021)/Dune (2021).nfo" to 1, "Dune (2021)/poster.jpg" to 1, "Dune (2021)/fanart.jpg" to 1),
            reads - DUNE_FILE,
        )
        assertEquals("one probe's reads", alone.reads.size, reads[DUNE_FILE])
        val dune = bench.item(films, DUNE_FILE)
        assertEquals("Dune Collection", dune.collectionId?.let { dao.collection(it) }?.name)
        assertTrue(dune.posterPath.isCachedArtwork() && dune.backdropPath.isCachedArtwork())
    }

    /** A show named at last with its forty seasons, each a request of its own and a run again: all asked once, it is read. */
    @Test
    fun aShowOfManySeasonsNamedAtLastIsReadWhole() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        val episodes = (1..SEASONS).map { "Doctor Who/Season $it/Doctor Who - S${"%02d".format(Locale.ROOT, it)}E01.mkv" }
        episodes.forEach { bench.showsNas.put(it) }
        bench.scan()
        assertNull(bench.showOf(shows, episodes.first()).tmdbId)
        tmdb.show(DOCTOR_WHO, "Doctor Who", 2005)
        (1..SEASONS).forEach { tmdb.season(DOCTOR_WHO, it, episodes = 10) }
        // Asked again an hour after the last time, on a scan the user asks for ([Reidentify]).
        bench.later(ASKED_AGAIN_MS)

        val pass = bench.pass()

        assertEquals(pass.requests.distinct(), pass.requests)
        val seasons = pass.requests.filter { it.startsWith("tv/$DOCTOR_WHO/season/") }.map { it.substringAfterLast('/').toInt() }
        assertEquals((1..SEASONS).toList(), seasons.sorted())
        assertTrue(episodes.map { bench.item(shows, it) }.all { it.matchedAt != null && it.title == "Episode ${it.seasonNumber}.1" })
        assertEquals(DOCTOR_WHO, bench.showOf(shows, episodes.last()).tmdbId)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val DROPPED = 11
        const val DUNE = 438631
        const val DUNE_1984 = 841
        const val HEAT = 949
        const val SEVERANCE = 95396
        const val DUNE_SAGA = 726871
        const val DUNE_FILE = "Dune (2021)/Dune (2021).mkv"
        const val DUNE_SIZE = 80_000_000_000
        const val DOCTOR_WHO = 57243
        const val SEASONS = 40
        const val ASKED_AGAIN_MS = Reidentify.ASKED_EVERY_MS + 1
        const val EPISODE = "Severance/Season 1/Severance - S01E01.mkv"
    }
}
