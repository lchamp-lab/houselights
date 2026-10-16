package app.houselights.providers.local

import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration

/**
 * When a scan pass stops asking TMDB, and when it asks again, on the scans' client (paused a minute
 * after the network failed) and [FakeTmdb]. On Robolectric for SystemClock, which the pause runs on.
 */
@RunWith(RobolectricTestRunner::class)
class TmdbPassTest {
    private val tmdb = FakeTmdb()
    private val client = tmdb.client(pauseWhenUnreachable = true)

    private suspend fun TmdbPass.film(n: Int) = ask { it.findMovie("Film $n", 2020) }

    /** The time a film takes to scan, its files read on the NAS. */
    private fun scanned() = ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

    /** First scan of a large library: one timeout on the third film, and TMDB answers again. */
    @Test
    fun oneTimeoutEarlyInAPassLeavesTheLaterFilmsTheirDetails() = runTest {
        repeat(FILMS) { tmdb.movie(it + 1, "Film $it", 2020) }
        val told = mutableListOf<Throwable>()
        val pass = TmdbPass(client) { told += it }
        val answers = (0 until FILMS).map { n ->
            if (n == 2) tmdb.nextFailures += SocketTimeoutException("timeout")
            pass.film(n).also { scanned() }
        }

        assertEquals(TmdbAnswer.Unanswered, answers[2])
        assertTrue("asked again once the pause is over", pass.open)
        assertEquals((21..FILMS).toList(), answers.drop(20).map { it.value?.id })
        assertTrue("said from the first failure", pass.issue is TmdbUnreachableException)
        assertEquals(listOf(pass.issue), told)
    }

    /** Scanned again at once, after a pass that found TMDB out of reach: the pause is a failure already counted. */
    @Test
    fun aPassStartedDuringThePauseAsksAgainOnceItIsOver() = runTest {
        tmdb.movie(1, "Film 0", 2020)
        tmdb.failure = ConnectException("Network is unreachable")
        val failed = TmdbPass(client)
        repeat(3) {
            // Each a request of its own, once the pause the last one began is over.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            failed.film(0)
        }
        assertFalse(failed.open)

        tmdb.failure = null
        val next = TmdbPass(client)
        repeat(5) { assertEquals(TmdbAnswer.Unanswered, next.film(0)) }
        assertTrue(next.open)
        assertNull(next.issue)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertEquals(1, next.film(0).value?.id)
    }

    @Test
    fun threeFailuresInARowStopThePass() = runTest {
        tmdb.movie(1, "Film 0", 2020)
        val pass = TmdbPass(client)
        // A failure of one answer: the next request is made at once.
        tmdb.failure = IOException("stream was reset: PROTOCOL_ERROR")
        repeat(2) { pass.film(0) }
        val first = pass.issue
        tmdb.failure = null
        assertEquals("an answer starts the count again", 1, pass.film(0).value?.id)

        tmdb.failure = IOException("stream was reset: PROTOCOL_ERROR")
        repeat(2) { pass.film(0) }
        assertTrue(pass.open)
        pass.film(0)
        assertFalse(pass.open)
        assertSame("the first failure stays what is said", first, pass.issue)
    }

    /** TMDB failing on its side, or answering in another shape: every next question would meet it too. */
    @Test
    fun aServerFailureOrAnAnswerOfAnotherShapeCountsAndIsSaid() = runTest {
        tmdb.movie(1, "Film 0", 2020)
        listOf<FakeTmdb.() -> Unit>({ status = 500 }, { rawBody = "{\"results\":\"none\"}" }).forEach { failing ->
            tmdb.failing()
            val pass = TmdbPass(client)
            pass.film(0)
            assertEquals("said from the first", UiError.TMDB_OFFLINE, pass.issue?.toUiError())
            repeat(2) { pass.film(0) }
            assertFalse(pass.open)
            tmdb.status = null
            tmdb.rawBody = null
        }
    }

    /** TMDB's search lags behind an entry it removed: no match, not a question left unanswered. */
    @Test
    fun aHitTmdbNoLongerHasIsNoMatch() = runTest {
        tmdb.movie(1, "Film 0", 2020)
        tmdb.gonePath = "movie/1"
        val pass = TmdbPass(client)
        assertEquals(TmdbAnswer.Given(null), pass.film(0))
        assertNull(pass.issue)
    }

    private companion object {
        const val FILMS = 40
    }
}
