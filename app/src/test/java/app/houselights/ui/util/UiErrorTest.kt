package app.houselights.ui.util

import app.houselights.core.FailureKind
import app.houselights.core.TmdbCertificateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

/** What each error offers to do: Retry only where trying again can help (core/FailuresTest says which error is which). */
class UiErrorTest {
    @Test
    fun eachErrorOffersWhatFixesIt() {
        assertEquals(ErrorAction.RETRY, UiError.OFFLINE.action)
        assertEquals(ErrorAction.RETRY, UiError.TMDB_OFFLINE.action)
        assertEquals(ErrorAction.RETRY, UiError.FAILED.action)
        assertEquals(ErrorAction.RECONNECT, UiError.DENIED.action)
        assertEquals(ErrorAction.ADD_TMDB_KEY, UiError.NO_TMDB_KEY.action)
        assertEquals(ErrorAction.EDIT_TMDB_KEY, UiError.TMDB_KEY_REFUSED.action)
        // The device's clock set, or the server's certificate renewed, trying again helps.
        assertEquals(ErrorAction.RETRY, UiError.CERTIFICATE.action)
        // Gone stays gone: Back is the way out.
        assertNull(UiError.GONE.action)
        // TMDB will not know the id the next time either: another result, or another search.
        assertNull(UiError.TMDB_NOT_FOUND.action)
    }

    /** A refused certificate is said as one, never as a server or TMDB out of reach. */
    @Test
    fun aRefusedCertificateIsSaidAsOne() {
        assertEquals(UiError.CERTIFICATE, FailureKind.CERTIFICATE.toUiError())
        val handshake = SSLHandshakeException("Chain validation failed").apply { initCause(CertificateExpiredException("expired")) }
        assertEquals(UiError.CERTIFICATE, handshake.toUiError())
        assertEquals(UiError.CERTIFICATE, TmdbCertificateException(handshake).toUiError())
    }
}
