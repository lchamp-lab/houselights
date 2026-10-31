package app.houselights.providers.local

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

/** A film's header is read on the NAS while TMDB answers, and its row is then written once with both. */
@RunWith(RobolectricTestRunner::class)
class ScanOverlapTest {
    private val log = StatementLog()

    @get:Rule
    val scan = ScanBenchRule(onQuery = log)

    private val bench get() = scan.bench
    private val nas get() = bench.nas
    private val tmdb get() = bench.tmdb

    /** Each side waits for the other: read one after the other, the first would give up waiting. */
    @Test
    fun aFilmsHeaderIsReadWhileTmdbAnswers() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        tmdb.movie(438631, "Dune", 2021)
        val reading = CompletableDeferred<Unit>()
        val asked = CompletableDeferred<Unit>()
        val tmdbAskedDuringTheRead = AtomicBoolean()
        val readDuringTmdb = AtomicBoolean()
        nas.onRead = { path, offset, _ ->
            if (path.endsWith(".mkv") && offset == 0L) {
                reading.complete(Unit)
                tmdbAskedDuringTheRead.set(withTimeoutOrNull(WAIT_MS) { asked.await() } != null)
            }
        }
        tmdb.onRequest = {
            asked.complete(Unit)
            readDuringTmdb.set(runBlocking { withTimeoutOrNull(WAIT_MS) { reading.await() } } != null)
        }
        bench.scan()

        assertTrue("TMDB asked while the header is read", tmdbAskedDuringTheRead.get())
        assertTrue("the header read while TMDB is asked", readDuringTmdb.get())
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals(438631, dune.tmdbId)
        assertEquals(MediaProbe.VERSION, dune.probeVersion)
    }

    /** One write per film on the device's slow storage, the probe's and the match's together. */
    @Test
    fun aNewFilmIsWrittenOnceWithItsTracksAndItsMatch() = runTest {
        val films = bench.folder("Films")
        nas.put("Dune (2021).mkv", size = 80_000_000_000, bytes = Matroska.sample())
        nas.putText("Dune (2021).nfo", "<movie><title>Dune : Première partie</title><uniqueid type=\"tmdb\">438631</uniqueid></movie>")
        tmdb.movie(438631, "Dune", 2021)
        log.recording = true
        bench.scan()
        log.recording = false

        assertEquals(1, log.recorded.count { it.startsWith("UPDATE OR ABORT `items`") })
        val dune = bench.item(films, "Dune (2021).mkv")
        assertEquals("Dune : Première partie" to 438631, dune.title to dune.tmdbId)
        assertEquals("HEVC", MediaProbe.decode(dune.mediaInfo)?.video?.codec)
        assertEquals(MediaProbe.VERSION, dune.probeVersion)
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
