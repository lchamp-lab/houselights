package app.houselights.providers.local

import app.houselights.core.Http
import app.houselights.core.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.Interceptor
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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    @Suppress("SleepInsteadOfDelay") // OkHttp's own threads end the call: virtual time cannot wait for them.
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
        tmdb.failure = ConnectException("Network is unreachable")
        failure<TmdbUnreachableException> { client.findMovie("Dune", 2021) }

        tmdb.failure = null
        tmdb.requests.clear()
        failure<TmdbPausedException> { client.findMovie("Dune", 2021) }
        assertEquals("the next files don't each wait for a timeout", emptyList<String>(), tmdb.requests)

        // On the clock that only moves forward: the wall clock may have been set back meanwhile.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertEquals(438631, client.findMovie("Dune", 2021)?.id)
    }

    /** No route, no name, no connection, no answer in time: the next request would wait as long. */
    @Test
    fun everyNetworkFailurePausesAScan() = runTest {
        listOf(UnknownHostException("api.themoviedb.org"), NoRouteToHostException(), SocketTimeoutException("timeout")).forEach {
            val client = tmdb.client(pauseWhenUnreachable = true)
            tmdb.failure = it
            failure<TmdbUnreachableException> { client.movieById(438631) }
            tmdb.failure = null
            failure<TmdbPausedException> { client.movieById(438631) }
        }
    }

    /** A stream reset or an answer cut short: the network is there, and the next request may well be answered. */
    @Test
    fun aFailureOfOneAnswerDoesNotPauseAScan() = runTest {
        val client = tmdb.client(pauseWhenUnreachable = true)
        tmdb.failure = IOException("stream was reset: PROTOCOL_ERROR")
        failure<TmdbUnreachableException> { client.movieById(438631) }
        tmdb.failure = null
        assertEquals(438631, client.movieById(438631)?.id)
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
        failure<TmdbPausedException> { client.movieById(438631) }
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

    /** TMDB's search lags behind an entry it removed. */
    @Test
    fun aHitTmdbNoLongerHasIsNoMatch() = runTest {
        tmdb.show(95396, "Severance", 2022)
        tmdb.gonePath = "movie/438631"
        assertNull(tmdb.client().findMovie("Dune", 2021))
        tmdb.gonePath = "tv/95396"
        assertNull(tmdb.client().findTv("Severance", 2022))
    }

    @Test
    fun anAnswerThatStallsIsTmdbOutOfReachNotTheNas() = runTest {
        tmdb.bodyFailure = SocketTimeoutException("timeout")
        val e = failure<TmdbUnreachableException> { tmdb.client().movieById(438631) }
        assertFalse(ScanErrors.isUnreachable(e))
    }

    /** Retrofit reads a failure's body after the interceptors: read there, a reset would reach the scan as the NAS's. */
    @Test
    fun aFailureWhoseBodyBreaksOffIsTmdbOutOfReachNotTheNas() = runTest {
        tmdb.status = 500
        tmdb.bodyFailure = SocketException("Connection reset")
        val e = failure<TmdbUnreachableException> { tmdb.client().movieById(438631) }
        assertFalse(ScanErrors.isUnreachable(e))
    }

    @Test
    fun anAnswerTooLargeToHoldIsNotRead() = runTest {
        tmdb.movies[438631] = tmdb.movies.getValue(438631).copy(overview = "x".repeat(9 * 1024 * 1024))
        failure<TmdbUnreachableException> { tmdb.client().movieById(438631) }
    }

    /** A show comes with its IMDb and TVDB ids, which tell whether an NFO describes it. */
    @Test
    fun aShowComesWithItsIdsElsewhere() = runTest {
        val asked = mutableListOf<HttpUrl>()
        val client = TmdbClient("key", transport = Interceptor { chain -> asked += chain.request().url; tmdb.intercept(chain) })
        tmdb.show(2316, "The Office", 2005)
        tmdb.rawBody = """{"id":2316,"name":"The Office","external_ids":{"imdb_id":"tt0386676","tvdb_id":73244,"wikidata_id":"Q23831"}}"""
        assertEquals(TmdbExternalIds("tt0386676", 73244), client.tv(2316).externalIds)
        assertTrue("external_ids" in asked.single().queryParameter("append_to_response").orEmpty().split(','))
    }

    @Test
    fun otherFailuresStayHttpFailures() {
        tmdb.status = 500
        assertThrows(retrofit2.HttpException::class.java) { kotlinx.coroutines.runBlocking { tmdb.client().movieById(438631) } }
    }
}
