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
import app.houselights.testing.certificateRefused
import app.houselights.testing.http
import app.houselights.testing.mountFailure
import app.houselights.testing.smbStatus
import app.houselights.ui.onboarding.LoginError
import app.houselights.ui.onboarding.loginError
import app.houselights.ui.util.UiError
import app.houselights.ui.util.toUiError
import app.houselights.ui.util.toWriteError
import com.emc.ecs.nfsclient.mount.MountStatus
import com.emc.ecs.nfsclient.nfs.NfsException
import com.emc.ecs.nfsclient.nfs.NfsStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.ProviderException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/** A failure reads the same on a screen, after a write, in a scan and at sign-in: all go through [failureKind]. */
@RunWith(RobolectricTestRunner::class)
class FailuresTest {
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
            SecretUnreadException() to Told(UiError.SECRETS_UNREAD, UiError.SECRETS_UNREAD, ScanErrorKind.OTHER),
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
            // A NAS's RPC answer cut short is no network failure.
            MalformedAnswerException("cut short at byte 40") to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.OTHER),
        )
    )

    /** No answer reads as the network on a screen, the storage after a write, and the NAS in a scan. */
    @Test
    fun noAnswerIsOfflineOnAScreenAndNotSavedAfterAWrite() = check(
        mapOf(
            SocketTimeoutException() to Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.UNREACHABLE),
            IOException("wrapped", ConnectException("Connection refused")) to
                Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.UNREACHABLE),
            IOException("NAS away") to Told(UiError.OFFLINE, UiError.NOT_SAVED, ScanErrorKind.OTHER),
        )
    )

    /** A certificate refused is said as one, the server's or TMDB's, never as no answer. */
    @Test
    fun aCertificateRefusedIsSaidAsOne() = check(
        mapOf(
            certificateRefused() to Told(UiError.CERTIFICATE, UiError.NOT_SAVED, ScanErrorKind.OTHER),
            TmdbCertificateException(certificateRefused()) to Told(UiError.CERTIFICATE, UiError.NOT_SAVED, ScanErrorKind.OTHER),
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
    fun aNasRefusalIsReportedPerFolder() = check(
        mapOf(
            RuntimeException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed for 'zidoo'") to
                Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.LOGIN_FAILED),
            smbStatus(NtStatus.STATUS_ACCESS_DENIED) to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.ACCESS_DENIED),
            mountFailure(MountStatus.MNT3ERR_ACCES) to Told(UiError.FAILED, UiError.FAILED, ScanErrorKind.ACCESS_DENIED),
            // On the device, a file that cannot be opened for writing is a storage fault.
            FileNotFoundException("/volume1/media/Heat.nfo") to Told(UiError.FAILED, UiError.NOT_SAVED, ScanErrorKind.NOT_FOUND),
        )
    )

    /**
     * Two NFS refusals, each with its remedy: the mount refused (the export not open to this player), or a read the
     * mount allowed refused (root squashed): only the first is the mount's.
     */
    @Test
    fun aRefusedMountIsToldFromARefusedRead() {
        val read = NfsException(NfsStatus.NFS3ERR_ACCES, "readdirplus error code: NfsStatus:13")
        assertEquals(true, mountFailure(MountStatus.MNT3ERR_ACCES).isMountRefused())
        assertEquals(true, IOException("Listing failed", mountFailure(MountStatus.MNT3ERR_PERM)).isMountRefused())
        assertEquals(false, read.isMountRefused())
        assertEquals(false, mountFailure(MountStatus.MNT3ERR_NOENT).isMountRefused())
        assertEquals(FailureKind.ACCESS_DENIED, read.failureKind())
    }

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

    /** A 401 or 403 refuses the account (403: disabled, or not allowed at the moment). */
    @Test
    fun aSignInFailureSaysWhatFailedAndAtWhichAddress() {
        val typed = "http://nas:8096"
        val reached = "https://nas:8920"
        fun auth(e: Throwable) = loginError(e, typed)
        fun reach(e: Exception) = loginError(ReachFailure(reached, e), typed)

        assertEquals(LoginError.Refused, auth(http(401)))
        assertEquals(LoginError.Refused, auth(http(403)))
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
        assertEquals(LoginError.Refused, reach(http(403)))
        assertEquals(LoginError.NotJellyfin(reached), reach(http(404)))
        assertEquals(LoginError.NotJellyfin(reached), reach(http(500)))
        assertEquals(LoginError.NotJellyfin(reached), reach(SerializationException("Unexpected JSON token")))
        assertEquals(LoginError.Certificate(reached), reach(certificateRefused()))
        assertEquals(LoginError.Unreachable(reached), reach(SocketTimeoutException()))
    }

    @Test
    fun aChainOfCausesThatLoopsIsReadOnce() {
        val outer = IOException("outer")
        val inner = IOException("inner", outer)
        outer.initCause(inner)
        assertEquals(FailureKind.IO, outer.failureKind())
    }
}
