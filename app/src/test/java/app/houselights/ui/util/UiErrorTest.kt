package app.houselights.ui.util

import app.houselights.core.NoTmdbKeyException
import app.houselights.core.NotFoundException
import app.houselights.providers.local.TmdbKeyRefusedException
import app.houselights.providers.local.TmdbUnreachableException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class UiErrorTest {
    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody()))

    @Test
    fun eachFailureShowsAsWhatFixesIt() {
        val expected = mapOf(
            NoTmdbKeyException() to UiError.NO_TMDB_KEY,
            NotFoundException("Heat") to UiError.GONE,
            http(401) to UiError.DENIED,
            http(403) to UiError.DENIED,
            http(404) to UiError.GONE,
            http(500) to UiError.FAILED,
            SocketTimeoutException() to UiError.OFFLINE,
            IOException("NAS away") to UiError.OFFLINE,
            IllegalStateException("This source cannot be edited") to UiError.FAILED,
        )
        expected.forEach { (failure, error) -> assertEquals(failure.toString(), error, failure.toUiError()) }
    }

    /** TMDB's failures are IOExceptions: they are told apart before the library going offline. */
    @Test
    fun tmdbFailuresComeBeforeTheLibraryGoingOffline() {
        val unreachable = TmdbUnreachableException(SocketTimeoutException())
        val refused = TmdbKeyRefusedException()
        assertTrue(unreachable is IOException && refused is IOException)
        assertEquals(UiError.TMDB_OFFLINE, unreachable.toUiError())
        assertEquals(UiError.TMDB_KEY_REFUSED, refused.toUiError())
    }

    @Test
    fun retryIsOfferedOnlyWhereTryingAgainCanHelp() {
        assertEquals(ErrorAction.RETRY, UiError.OFFLINE.action)
        assertEquals(ErrorAction.RETRY, UiError.TMDB_OFFLINE.action)
        assertEquals(ErrorAction.RETRY, UiError.FAILED.action)
        assertEquals(ErrorAction.RECONNECT, UiError.DENIED.action)
        assertEquals(ErrorAction.ADD_TMDB_KEY, UiError.NO_TMDB_KEY.action)
        assertEquals(ErrorAction.EDIT_TMDB_KEY, UiError.TMDB_KEY_REFUSED.action)
        // Gone stays gone: Back is the way out.
        assertNull(UiError.GONE.action)
    }

    @Test
    fun aLoadStateSaysWhatItHolds() {
        val states: List<LoadState<String?>> = listOf(LoadState.Loading, LoadState.Ready(null), LoadState.Failed(UiError.GONE))
        val read = states.map {
            when (it) {
                LoadState.Loading -> "loading"
                is LoadState.Ready -> "ready ${it.value}"
                is LoadState.Failed -> "failed ${it.error}"
            }
        }
        assertEquals(listOf("loading", "ready null", "failed GONE"), read)
        assertEquals(LoadState.Ready("a"), LoadState.Ready("a"))
    }
}
