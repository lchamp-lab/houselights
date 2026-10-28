package app.houselights.ui.util

import app.houselights.R
import app.houselights.core.DiskAbsentException
import app.houselights.core.FailureKind
import app.houselights.core.SourceConfig
import app.houselights.core.TmdbCertificateException
import app.houselights.core.failureKind
import app.houselights.providers.local.Folder
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.SourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.security.cert.CertificateExpiredException
import javax.net.ssl.SSLHandshakeException

/** What each error offers to do: Retry only where trying again can help (core/FailuresTest says which error is which). */
class UiErrorTest {
    @Test
    fun eachErrorOffersWhatFixesIt() {
        assertEquals(ErrorAction.RETRY, UiError.OFFLINE.action)
        // The disk plugged back in, trying again plays it.
        assertEquals(ErrorAction.RETRY, UiError.DISK_ABSENT.action)
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
        // The player changed (#58): its sheet asks whether to trust it, the updated firmware's player or an app in its place.
        assertEquals(ErrorAction.TRUST_PLAYER, UiError.PLAYER_CHANGED.action)
        assertEquals(ErrorAction.TRUST_PLAYER, UiError.PLAYER_REPLACED.action)
        assertNull("only the Zidoo player may have it", UiError.CREDENTIALS.action)
    }

    /** A refused certificate is said as one, never as a server or TMDB out of reach. */
    @Test
    fun aRefusedCertificateIsSaidAsOne() {
        assertEquals(UiError.CERTIFICATE, FailureKind.CERTIFICATE.toUiError())
        val handshake = SSLHandshakeException("Chain validation failed").apply { initCause(CertificateExpiredException("expired")) }
        assertEquals(UiError.CERTIFICATE, handshake.toUiError())
        assertEquals(UiError.CERTIFICATE, TmdbCertificateException(handshake).toUiError())
    }

    /**
     * The library out of reach is titled by where it lies, « NAS injoignable » for folders on a NAS, « Serveur
     * injoignable » for a server, « Bibliothèque injoignable » where that is not known; every other error by its own.
     */
    @Test
    fun theLibraryOutOfReachIsTitledByWhereItLies() {
        assertEquals(R.string.error_offline_nas_title, UiError.OFFLINE.titleAt(LibraryPlace.NAS))
        assertEquals(R.string.error_offline_server_title, UiError.OFFLINE.titleAt(LibraryPlace.SERVER))
        assertEquals(R.string.error_offline_title, UiError.OFFLINE.titleAt(null))
        (UiError.entries - UiError.OFFLINE).forEach { error ->
            (LibraryPlace.entries + null).forEach { place -> assertEquals("$error at $place", error.title, error.titleAt(place)) }
        }
    }

    /** A server's library lies on the server, the folders' on a NAS when one of them is, never on the player's disks alone. */
    @Test
    fun theLibraryLiesWhereItsSourceIs() {
        val server = SourceConfig.Jellyfin("https://nas:8920", "Salon", "u1", null, "not a secret")
        assertEquals(LibraryPlace.SERVER, libraryPlaceOf(server, emptyList()))
        assertEquals(LibraryPlace.NAS, libraryPlaceOf(SourceConfig.Local, listOf(folder(Protocol.SMB))))
        assertEquals(LibraryPlace.NAS, libraryPlaceOf(SourceConfig.Local, listOf(folder(Protocol.NFS))))
        assertEquals(LibraryPlace.NAS, libraryPlaceOf(SourceConfig.Local, listOf(folder(Protocol.LOCAL), folder(Protocol.NFS))))
        assertNull("the player's disks alone are no NAS", libraryPlaceOf(SourceConfig.Local, listOf(folder(Protocol.LOCAL))))
        assertNull(libraryPlaceOf(SourceConfig.Local, emptyList()))
        assertNull(libraryPlaceOf(null, listOf(folder(Protocol.SMB))))
    }

    /**
     * The place the screens are told follows the source in use and its folders: none without one, the server, the NAS
     * once a folder is on one, and again none for folders on the player's own disks alone.
     */
    @Test
    fun thePlaceFollowsTheSourceAndItsFolders() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableStateFlow<SourceConfig?>(null)
        val folders = MutableStateFlow(listOf(folder(Protocol.LOCAL)))
        val places = mutableListOf<LibraryPlace?>()
        backgroundScope.launch { libraryPlaces(source, folders).collect { places += it } }
        fun now(): LibraryPlace? {
            runCurrent()
            return places.last()
        }
        assertNull("no source", now())
        source.value = SourceConfig.Jellyfin("https://nas:8920", "Salon", "u1", null, "not a secret")
        assertEquals(LibraryPlace.SERVER, now())
        source.value = SourceConfig.Local
        assertNull("the player's disks alone", now())
        folders.value = listOf(folder(Protocol.LOCAL), folder(Protocol.SMB))
        assertEquals(LibraryPlace.NAS, now())
        folders.value = listOf(folder(Protocol.LOCAL))
        assertNull("the NAS's folder removed", now())
        assertEquals("each place once", listOf(null, LibraryPlace.SERVER, null, LibraryPlace.NAS, null), places)
    }

    private fun folder(protocol: Protocol) = Folder(1, "Films", SourceKind.MOVIES, protocol, "nas", "nas/films", lastScanAt = 0)

    /** A disk of the player's own unplugged is said as such on a screen, « Disque introuvable », out of reach as a NAS for a scan. */
    @Test
    fun aDiskUnpluggedIsSaidAsItsDisk() {
        val absent = IOException("Reading Heat.mkv", DiskAbsentException("1234-ABCD", NoSuchFileException("/storage/1234-ABCD/Heat.mkv")))
        assertEquals(UiError.DISK_ABSENT, absent.toUiError())
        assertEquals(R.string.error_disk_absent_title, absent.toUiError().titleAt(LibraryPlace.NAS))
        assertEquals(FailureKind.UNREACHABLE, absent.failureKind())
    }
}
