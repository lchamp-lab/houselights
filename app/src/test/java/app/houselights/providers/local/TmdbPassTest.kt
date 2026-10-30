package app.houselights.providers.local

import app.houselights.core.TmdbUnreachableException
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

/** When a pass stops and resumes asking TMDB, on the scans' client (paused a minute after a network failure). */
@RunWith(RobolectricTestRunner::class)
class TmdbPassTest {
    private val tmdb = FakeTmdb()
    private val client = tmdb.client(pauseWhenUnreachable = true)

    private suspend fun TmdbPass.film(n: Int) = ask { it.matchMovie(Wanted.of("Film $n", 2020)).found }

    /** The time a film takes to scan, its files read on the NAS. */
    private fun scanned() = ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

    /** A season TMDB did not answer is asked again for the next episode; once answered, never again that pass. */
    @Test
    fun aSeasonIsKeptOnceAnswered() = runTest {
        tmdb.show(95396, "Severance", 2022)
        tmdb.season(95396, 1, episodes = 3)
        tmdb.nextStatuses += 500
        val seasons = ShowIndexer.SeasonCache(TmdbPass(client))

        assertEquals(TmdbAnswer.Unanswered, seasons.get(95396, 1))
        assertEquals(3, seasons.get(95396, 1).value?.episodes?.size)
        assertEquals(3, seasons.get(95396, 1).value?.episodes?.size)
        assertEquals("asked twice, then kept", 2, tmdb.requests.size)
    }

    /**
     * A first scan of hundreds of shows keeps the seasons it read last, not all of them until it ends: the one read longest
     * ago is asked again, the one read last is not.
     */
    @Test
    fun aPassKeepsTheSeasonsItReadLast() = runTest {
        val count = ShowIndexer.SeasonCache.MAX_SEASONS + 1
        repeat(count) { tmdb.season(1_000 + it, 1, episodes = 2) }
        val seasons = ShowIndexer.SeasonCache(TmdbPass(client))
        repeat(count) { seasons.get(1_000 + it, 1) }

        assertEquals(2, seasons.get(1_000 + count - 1, 1).value?.episodes?.size)
        assertEquals("kept", count, tmdb.requests.size)
        assertEquals(2, seasons.get(1_000, 1).value?.episodes?.size)
        assertEquals("asked again", count + 1, tmdb.requests.size)
    }

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

    /** Scanned again at once after a pass that found TMDB unreachable: the pause counts as a failure already. */
    @Test
    fun aPassStartedDuringThePauseAsksAgainOnceItIsOver() = runTest {
        tmdb.movie(1, "Film 0", 2020)
        tmdb.failure = ConnectException("Network is unreachable")
        val failed = TmdbPass(client)
        repeat(3) {
            // Each a separate request, once the pause the previous one started is over.
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

    /** TMDB failing on its side, or answering in another shape: every next request would meet it too. */
    @Test
    fun aServerFailureOrAnAnswerOfAnotherShapeCountsAndIsSaid() = runTest {
        tmdb.movie(1, "Film 0", 2020)
        val failures = mapOf<String, FakeTmdb.() -> Unit>(
            "a server failure" to { status = 500 },
            "an answer of another shape" to { rawBody = "{\"results\":\"none\"}" },
        )
        failures.forEach { (case, failing) ->
            tmdb.failing()
            val pass = TmdbPass(client)
            pass.film(0)
            assertEquals("$case, said from the first", UiError.TMDB_OFFLINE, pass.issue?.toUiError())
            repeat(2) { pass.film(0) }
            assertFalse("$case, three times: the pass stops", pass.open)
            tmdb.status = null
            tmdb.rawBody = null
        }
    }

    /** TMDB's search still returns an entry it removed: no match, not a request left unanswered. */
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
