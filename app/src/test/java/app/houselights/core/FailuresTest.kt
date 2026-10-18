package app.houselights.core

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import app.houselights.providers.jellyfin.ReachFailure
import app.houselights.providers.local.EmptyFolderException
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.ScanErrors
import app.houselights.providers.local.UnreadableFolderException
import app.houselights.ui.onboarding.LoginError
import app.houselights.ui.onboarding.loginError
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import app.houselights.ui.util.toWriteError
import com.emc.ecs.nfsclient.mount.MountException
import com.emc.ecs.nfsclient.mount.MountStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.ProviderException
import java.security.cert.CertificateException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

/** A failure reads the same on a screen, after a write, in a scan and at sign-in: all go through [failureKind]. */
@RunWith(RobolectricTestRunner::class)
class FailuresTest {
    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody()))

    /** What a failure reads as: on a screen, after a write to the device, and for a folder's scan. */
    private data class Told(val screen: UiError, val write: UiError, val scan: ScanErrorKind)

    private fun told(e: Throwable) = Told(e.toUiError(), e.toWriteError(), ScanErrors.classify(e).kind)

    private fun check(expected: Map<Throwable, Told>) =
        expected.forEach { (failure, told) -> assertEquals(failure.toString(), told, told(failure)) }

    @Test
    fun theAppsOwnFailuresSayWhatTheyAre() = check(
        mapOf(
            NoTmdbKeyException() to Told(UiError.NO_TMDB_KEY, UiError.NO_TMDB_KEY, ScanErrorKind.OTHER),
            TmdbKeyRefusedException() to Told(UiError.TMDB_KEY_REFUSED, UiError.TMDB_KEY_REFUSED, ScanErrorKind.OTHER),
            NotFoundException("Heat") to Told(UiError.GONE, UiError.GONE, ScanErrorKind.OTHER),
            TmdbNotFoundException() to Told(UiError.TMDB_NOT_FOUND, UiError.TMDB_NOT_FOUND, ScanErrorKind.OTHER),
            SecretsUnavailableException(ProviderException("Keystore")) to Told(UiError.KEYSTORE, UiError.KEYSTORE, ScanErrorKind.OTHER),
            EmptyFolderException() to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.EMPTY),
            UnreadableFolderException(3) to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.ACCESS_DENIED),
            IllegalStateException("This source cannot be edited") to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.OTHER),
        )
    )

    @Test
    fun tmdbOutOfReachIsNeverTheNas() {
        val e = TmdbUnreachableException(SocketTimeoutException())
        assertEquals(Told(UiError.TMDB_OFFLINE, UiError.TMDB_OFFLINE, ScanErrorKind.OTHER), told(e))
        assertEquals(false, ScanErrors.isUnreachable(e))
    }

    @Test
    fun aServersAnswerSaysWhatItRefused() = check(
        mapOf(
            http(401) to Told(UiError.DENIED, UiError.DENIED, ScanErrorKind.OTHER),
            http(403) to Told(UiError.DENIED, UiError.DENIED, ScanErrorKind.OTHER),
            http(404) to Told(UiError.GONE, UiError.GONE, ScanErrorKind.OTHER),
            http(500) to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.OTHER),
            SerializationException("Unexpected JSON token") to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.OTHER),
        )
    )

    /** No answer reads as the network on a screen, the storage after a write, and the NAS in a scan. */
    @Test
    fun noAnswer() = check(
        mapOf(
            SocketTimeoutException() to Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.UNREACHABLE),
            IOException("wrapped", ConnectException("Connection refused")) to
                Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.UNREACHABLE),
            IOException("NAS away") to Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.OTHER),
            certificateRefused() to Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.OTHER),
        )
    )

    /** smbj wraps failures in RuntimeExceptions: read through the causes, a NAS timeout stays a NAS timeout. */
    @Test
    fun anSmbTimeoutIsTheNasOnEveryScreen() = check(
        mapOf(
            SMBRuntimeException(TransportException(ExecutionException(SMBRuntimeException(TimeoutException("Timeout expired"))))) to
                Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.UNREACHABLE),
            RuntimeException(SocketTimeoutException()) to Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.UNREACHABLE),
        )
    )

    /** NAS refusals and missing paths: Settings reports them per folder; a screen shows a generic failure. */
    @Test
    fun theNasRefusals() = check(
        mapOf(
            RuntimeException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed for 'zidoo'") to
                Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.LOGIN_FAILED),
            smbStatus(NtStatus.STATUS_ACCESS_DENIED) to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.ACCESS_DENIED),
            mountFailure(MountStatus.MNT3ERR_ACCES) to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.ACCESS_DENIED),
            // On the device, a file that cannot be opened for writing is a storage fault.
            FileNotFoundException("/volume1/media/Heat.nfo") to Told(UiError.FAILED, UiError.NOT_SAVED, ScanErrorKind.NOT_FOUND),
        )
    )

    @Test
    fun aFullDeviceIsNeverTheNetwork() = check(
        mapOf(
            SQLiteFullException("database or disk is full") to Told(UiError.NOT_SAVED, UiError.NOT_SAVED, ScanErrorKind.STORAGE_FULL),
            IOException("write failed: ENOSPC (No space left on device)", ErrnoException("write", OsConstants.ENOSPC)) to
                Told(UiError.NOT_SAVED, UiError.NOT_SAVED, ScanErrorKind.STORAGE_FULL),
            // A disk failing may fail a read as well as a write: only a write says nothing was saved.
            SQLiteDiskIOException("disk I/O error") to Told(UiError.FAILED, UiError.NOT_SAVED, ScanErrorKind.OTHER),
        )
    )

    @Test
    fun signingIn() {
        val typed = "http://nas:8096"
        val reached = "https://nas:8920"
        fun auth(e: Throwable) = loginError(e, typed)
        fun reach(e: Exception) = loginError(ReachFailure(reached, e), typed)

        assertEquals(LoginError.Refused, auth(http(401)))
        assertEquals(LoginError.Failed, auth(http(404)))
        assertEquals(LoginError.Failed, auth(http(500)))
        assertEquals(LoginError.NotJellyfin(typed), auth(SerializationException("Unexpected JSON token")))
        // An unparsable answer whose body excerpt contains words that NAS failures also use.
        assertEquals(LoginError.NotJellyfin(typed), auth(SerializationException("Unexpected JSON token at: <h1>Request timed out</h1>")))
        assertEquals(LoginError.Certificate(typed), auth(certificateRefused()))
        assertEquals(LoginError.Unreachable(typed), auth(IOException("Connection refused")))
        assertEquals(LoginError.Unreachable(typed), auth(SocketTimeoutException()))
        assertEquals(LoginError.Failed, auth(IllegalStateException("x")))
        assertEquals(LoginError.Keystore, auth(SecretsUnavailableException(ProviderException("Keystore"))))

        assertEquals(LoginError.Refused, reach(http(401)))
        assertEquals(LoginError.NotJellyfin(reached), reach(http(404)))
        assertEquals(LoginError.NotJellyfin(reached), reach(http(500)))
        assertEquals(LoginError.NotJellyfin(reached), reach(SerializationException("Unexpected JSON token")))
        assertEquals(LoginError.Certificate(reached), reach(certificateRefused()))
        assertEquals(LoginError.Unreachable(reached), reach(SocketTimeoutException()))
    }

    /** A 403 at sign-in means the server refuses this account (disabled, or not allowed at the moment). */
    @Test
    fun aServerRefusingTheAccountSaysSo() {
        assertEquals(LoginError.Refused, loginError(http(403), "http://nas:8096"))
        assertEquals(LoginError.Refused, loginError(ReachFailure("https://nas:8920", http(403)), "http://nas:8096"))
    }

    @Test
    fun aChainOfCausesThatLoopsIsReadOnce() {
        val outer = IOException("outer")
        val inner = IOException("inner", outer)
        outer.initCause(inner)
        assertEquals(FailureKind.IO, outer.failureKind())
    }

    /** The exception OkHttp throws for a certificate it cannot trust. */
    private fun certificateRefused() = SSLHandshakeException("Chain validation failed").apply { initCause(CertificateException("expired")) }

    private fun mountFailure(status: MountStatus) = MountException(
        status,
        "mount failure, server: 192.168.1.20, export: /volume1/media, nfs version: 3, returned state: ${status.value}",
    )

    private fun smbStatus(status: NtStatus) =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_READ, "Read failed for \\\\nas\\media\\film.mkv", null)
}
