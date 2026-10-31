package app.houselights.providers.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A pass holds the library's lock only to read and write it: while it waits for TMDB or the NAS, what the user does
 * goes through at once, and the pass then writes what the library has become, never an answer collected for another.
 */
@RunWith(RobolectricTestRunner::class)
class ScanLockTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private lateinit var films: SourceEntity
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb
    private val dao get() = bench.dao

    @Before
    fun open() = runTest {
        films = bench.folder("Films")
        tmdb.movie(DUNE, "Dune", 2021)
        tmdb.movie(DUNE_1984, "Dune", 1984)
        tmdb.movie(HEAT, "Heat", 1995)
    }

    /** Holds TMDB's searches until [FakeTmdb.Gate.open]; identifications by id go through. */
    private fun holdSearches(): FakeTmdb.Gate = tmdb.gate { it.startsWith("search/") }

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
        bench.showsNas.putImage("Severance/season01-poster.jpg", size = POSTER_BYTES.toInt())
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
        assertTrue(poster, poster.isCachedArtwork())
        assertEquals("the image whole", POSTER_BYTES, File(poster.removePrefix("file://")).length())
        assertEquals("Good News About Hell", bench.item(shows, EPISODE).title)
    }

    /** A cleanup while a film's poster is copied: the copy, not referenced yet, is spared, written or being written. */
    @Test
    fun aCleanupWhileAPosterIsCopiedLeavesIt() = runTest {
        nas.put("Dune (2021)/Dune (2021).mkv")
        nas.putImage("Dune (2021)/poster.jpg", size = POSTER_BYTES.toInt())
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
        assertTrue(poster, poster.isCachedArtwork())
        assertEquals("the image whole", POSTER_BYTES, File(poster.removePrefix("file://")).length())
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
        val reads = pass.reads.groupingBy { it }.eachCount()
        assertEquals(
            mapOf("Dune (2021)/Dune (2021).nfo" to 1, "Dune (2021)/poster.jpg" to 1, "Dune (2021)/fanart.jpg" to 1),
            reads - DUNE_FILE,
        )
        assertEquals("one probe's reads", oneProbesReads(DUNE_FILE, DUNE_SIZE, bytes).size, reads[DUNE_FILE])
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

    /**
     * A film known before, read again, its folder removed while TMDB is asked for it: its run then writes nothing, a row
     * born again under the folder gone included, and asks nothing more.
     */
    @Test
    fun aKnownFilmReadAgainWhileItsFolderIsRemovedGetsNothingWritten() = runTest {
        // Its header read once: read again, the film asks TMDB alone.
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        bench.scan()
        val gate = tmdb.gate { it == "movie/$DUNE" }
        bench.scanner.scanAll(refreshMetadata = true)
        try {
            gate.reached()
            meanwhile { bench.scanner.removeSource(films) }
            tmdb.requests.clear()
            nas.reads.clear()
        } finally {
            gate.open()
        }
        bench.awaitScans()

        assertEquals("nothing asked once it went", emptyList<String>(), tmdb.requests.toList())
        assertEquals("nothing read once it went", emptyList<String>(), nas.reads.toList())
        assertEquals(emptyList<KnownFile>(), dao.knownFiles(films.id))
    }

    /**
     * An order chosen for a show while the scan asks TMDB about a new episode of it: the episode is placed in the order
     * chosen, beside those the choice placed, never in the one the scan began with.
     */
    @Test
    fun anOrderChosenWhileTheScanReadsAnEpisodeOfTheShowPlacesItThere() = runTest {
        val shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(FIREFLY, "Firefly", 2002)
        tmdb.season(FIREFLY, 1, episodes = 14)
        // On DVD, Serenity (TMDB's 11th) first, then The Train Job and Bushwhacked.
        tmdb.group(FIREFLY, DVD, type = 3, name = "DVD Order", listOf(listOf(1 to 11, 1 to 1, 1 to 2)))
        fun firefly(n: Int) = "Firefly/Season 1/Firefly - S01E0$n.mkv"
        (1..2).forEach { bench.showsNas.put(firefly(it)) }
        bench.scan()
        bench.showsNas.put(firefly(3))
        val gate = tmdb.gate { it == "tv/$FIREFLY/season/1" }
        bench.scanner.scanAll()
        try {
            gate.reached()
            meanwhile { bench.scanner.chooseOrder(bench.showOf(shows, firefly(1)).id, DVD) }
        } finally {
            gate.open()
        }
        bench.awaitScans()

        val episodes = (1..3).map { bench.item(shows, firefly(it)) }
        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), episodes.map { it.title })
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), episodes.map { it.tmdbNumber()?.let { n -> n.season to n.episode } })
        val show = bench.showOf(shows, firefly(3))
        assertEquals(DVD to DVD, show.userEpisodeOrder to show.episodeOrder)
    }

    /**
     * A known film read again while the user marks it watched, Trakt's sync marks it so too, a poster is chosen and it
     * becomes a favourite: the pass writes TMDB's answer beside what was done meanwhile, never over it.
     */
    @Test
    fun whatTheUserDoesWhileAKnownFilmIsReadAgainStands() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        tmdb.editMovie(DUNE) { it.copy(overview = "Arrakis") }
        val gate = tmdb.gate { it == "movie/$DUNE" }
        bench.scanner.scanAll(refreshMetadata = true)
        try {
            gate.reached()
            meanwhile {
                dao.savePlayback(dune.id, positionMs = 600_000, durationMs = 9_000_000, played = false, lastPlayedAt = PLAYED_AT)
                bench.db.trakt().apply(mapOf(PLAYED_AT to listOf(dune.id)), emptyList(), emptyMap())
                dao.setUserPoster(dune.id, "file:/mine.jpg")
                dao.setFavorite(dune.id, true)
            }
        } finally {
            gate.open()
        }
        bench.awaitScans()

        val after = bench.item(films, "Dune (2021).mkv")
        assertEquals("Arrakis", after.overview)
        assertEquals(Triple(true, true, "file:/mine.jpg"), Triple(after.played, after.favorite, after.userPosterPath))
        assertEquals(PLAYED_AT, after.lastPlayedAt)
    }

    /** TMDB answering the search, then failing on the film's details: nothing of the search is written, and the next pass asks again. */
    @Test
    fun aFilmWhoseDetailsFailAfterItsSearchWaitsUnmatchedForTheNextPass() = runTest {
        nas.put("Dune (2021).mkv")
        tmdb.failingPath = "movie/"
        bench.scan()

        assertEquals("each once", listOf("search/movie", "movie/$DUNE"), tmdb.requests.toList())
        val waiting = bench.item(films, "Dune (2021).mkv")
        assertEquals(null to null, waiting.tmdbId to waiting.matchedAt)
        assertEquals(null, waiting.hintKey)

        bench.tmdbBack()
        bench.scan()
        assertEquals(DUNE, bench.item(films, "Dune (2021).mkv").tmdbId)
    }

    /** An identification cut short while it asks TMDB, the user having left the screen: the film is as it was, the lock free. */
    @Test
    fun anIdentificationCancelledWhileTmdbIsAskedChangesNothing() = runTest {
        nas.put("Dune (2021).mkv")
        bench.scan()
        val dune = bench.item(films, "Dune (2021).mkv")
        tmdb.hold = true
        val identifying = launch(Dispatchers.IO) { bench.scanner.identify(dune.id, HEAT) }
        withContext(Dispatchers.IO) { assertTrue("held", tmdb.held.await(WAIT_MS, TimeUnit.MILLISECONDS)) }
        identifying.cancelAndJoin()
        tmdb.hold = false

        assertEquals(dune, bench.item(films, "Dune (2021).mkv"))
        meanwhile { bench.scanner.identify(dune.id, DUNE_1984) }
        assertEquals(DUNE_1984, bench.item(films, "Dune (2021).mkv").userTmdbId)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val PLAYED_AT = 1_700_000_000_000L
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
        const val POSTER_BYTES = 100L
        const val FIREFLY = 1437
        const val DVD = "5b5a9ef3c3a3685a5c000c5c"
        const val ASKED_AGAIN_MS = Reidentify.ASKED_EVERY_MS + 1
        const val EPISODE = "Severance/Season 1/Severance - S01E01.mkv"
    }
}
