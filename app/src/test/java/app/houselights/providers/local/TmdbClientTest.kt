package app.houselights.providers.local

import app.houselights.core.Http
import app.houselights.core.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/** What TMDB answers, as the client tells it: on TMDB played by [FakeTmdb], on the clock of the device. */
@OptIn(ExperimentalCoroutinesApi::class) // currentTime: the waits are counted on the test's clock.
@RunWith(RobolectricTestRunner::class)
class TmdbClientTest {
    private val tmdb = FakeTmdb().apply { movie(438631, "Dune", 2021) }

    private suspend inline fun <reified T : Throwable> failure(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("Expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun findsAFilmByItsTitle() = runTest {
        assertEquals(438631, tmdb.client().findMovie("Dune", 2021)?.id)
        assertEquals(listOf("search/movie", "movie/438631"), tmdb.requests)
    }

    @Test
    fun aRequestGivenUpOnLeavesTmdbToTheNextOne() = runTest {
        val client = tmdb.client(pauseWhenUnreachable = true)
        tmdb.hold = true
        // A search replaced by a newer one, or a screen left, while TMDB answers.
        val search = launch(Dispatchers.IO) { client.findMovie("Dune", 2021) }
        assertTrue(tmdb.held.await(5, TimeUnit.SECONDS))
        search.cancel()
        search.join()
        // The call ends on OkHttp's threads, after the coroutine: wait until it has.
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Http.client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < until) Thread.sleep(5)

        tmdb.hold = false
        assertEquals(438631, client.findMovie("Dune", 2021)?.id)
    }

    @Test
    fun aScanPausesTmdbOnceOutOfReachThenAsksAgainAMinuteLater() = runTest {
        val client = tmdb.client(pauseWhenUnreachable = true)
        tmdb.failure = IOException("Network is unreachable")
        failure<TmdbUnreachableException> { client.findMovie("Dune", 2021) }

        tmdb.failure = null
        tmdb.requests.clear()
        failure<TmdbUnreachableException> { client.findMovie("Dune", 2021) }
        assertEquals("the next files don't each wait for a timeout", emptyList<String>(), tmdb.requests)

        // On the clock that only moves forward: the wall clock may have been set back meanwhile.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertEquals(438631, client.findMovie("Dune", 2021)?.id)
    }

    @Test
    fun someoneAskingIsAnsweredByTmdbItselfEveryTime() = runTest {
        val client = tmdb.client()
        tmdb.failure = IOException("Network is unreachable")
        failure<TmdbUnreachableException> { client.searchMovies("Dune", null) }

        tmdb.failure = null
        assertEquals(listOf(438631), client.searchMovies("Dune", null).map { it.id })
    }

    @Test
    fun aGatewayFailingIsAskedAgainASecondLater() = runTest {
        tmdb.nextStatuses += 503
        assertEquals(438631, tmdb.client().movieById(438631)?.id)
        assertEquals(listOf("movie/438631", "movie/438631"), tmdb.requests)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun tooManyRequestsWaitsWhatTmdbAsks() = runTest {
        tmdb.nextStatuses += 429
        tmdb.headers = mapOf("Retry-After" to "3")
        assertEquals(438631, tmdb.client().movieById(438631)?.id)
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun aWaitAskedIsFiveSecondsAtMost() {
        assertEquals(2_000L, TmdbClient.retryAfterMs("2"))
        assertEquals(5_000L, TmdbClient.retryAfterMs("120"))
        assertEquals(1_000L, TmdbClient.retryAfterMs(null))
        assertEquals(1_000L, TmdbClient.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun tmdbStillBusyIsOutOfReachAndAScanPausesIt() = runTest {
        val client = tmdb.client(pauseWhenUnreachable = true)
        tmdb.status = 502
        failure<TmdbUnreachableException> { client.movieById(438631) }
        assertEquals("asked twice", 2, tmdb.requests.size)

        tmdb.status = null
        failure<TmdbUnreachableException> { client.movieById(438631) }
        assertEquals(2, tmdb.requests.size)
    }

    @Test
    fun aKeyRefusedOrForbiddenIsTheKeys() = runTest {
        tmdb.status = 401
        failure<TmdbKeyRefusedException> { tmdb.client().searchMovies("Dune", null) }
        tmdb.status = 403
        failure<TmdbKeyRefusedException> { tmdb.client().searchMovies("Dune", null) }
        assertEquals(TmdbKeyCheck.REFUSED, tmdb.client().checkKey())
        tmdb.status = null
        assertEquals(TmdbKeyCheck.ACCEPTED, tmdb.client().checkKey())
    }

    @Test
    fun anEntryTmdbDoesNotHaveIsNotFound() = runTest {
        val client = tmdb.client()
        assertNull(client.movieById(1))
        assertNull(client.collection(1))
        failure<NotFoundException> { client.movie(1) }
    }

    @Test
    fun anAnswerThatStallsIsTmdbOutOfReachNotTheNas() = runTest {
        tmdb.stallBody = true
        val e = failure<TmdbUnreachableException> { tmdb.client().movieById(438631) }
        assertFalse(ScanErrors.isUnreachable(e))
    }

    @Test
    fun otherFailuresStayHttpFailures() {
        tmdb.status = 500
        assertThrows(retrofit2.HttpException::class.java) { kotlinx.coroutines.runBlocking { tmdb.client().movieById(438631) } }
    }
}
