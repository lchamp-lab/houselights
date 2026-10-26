package app.houselights.servers

import app.houselights.providers.local.Protocol
import app.houselights.providers.local.ScanErrorKind
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.servers.TestServers.refusal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The SMB NAS of tools/test-servers (Samba 4.23, SMB2 to SMB3, signing offered and not required, guests mapped from
 * unknown names): its shares as the app signs in to them, with the account or as a guest, and what it refuses.
 */
@RunWith(RobolectricTestRunner::class)
class SmbServerTest : NasServerTest() {
    private fun folder(share: String, path: String = "", user: String = TestServers.USER, password: String = TestServers.PASSWORD) =
        SourceEntity(
            name = "Test", kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = TestServers.host, share = share, path = path,
            username = user, password = Secret(password),
        )

    override fun video(path: String) = folder("video", path)

    @Test
    fun aSecondShareOfTheNasPassesItsTest() = runBlocking {
        val media = folder("media")
        assertEquals("Films and Vidéos perso", 2 to 0, browsers.of(media).test(media).counted())
    }

    @Test
    fun aGuestReadsAShareOpenToGuests() = runBlocking {
        val public = folder("public", user = "", password = "")
        assertEquals("Films", 1 to 0, browsers.of(public).test(public).counted())
    }

    @Test
    fun aGuestIsRefusedAShareOfTheAccount() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("video", user = "", password = ""))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }

    @Test
    fun aWrongPasswordIsTheAccountRefused() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("video", password = "wrong"))
        assertEquals(failure, ScanErrorKind.LOGIN_FAILED, kind)
    }

    @Test
    fun aShareThatIsNotThereIsNotFound() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("videos"))
        assertEquals(failure, ScanErrorKind.NOT_FOUND, kind)
    }

    /** admin$, an administrative share the account may not open. */
    @Test
    fun aShareTheAccountMayNotOpenIsRefused() = runBlocking {
        val (kind, failure) = browsers.refusal(folder("admin$"))
        assertEquals(failure, ScanErrorKind.ACCESS_DENIED, kind)
    }
}
