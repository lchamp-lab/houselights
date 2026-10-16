package app.houselights.providers.local

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.TimeoutException

/** SMB sessions on a NAS held in memory: which are kept, closed and opened again. On Robolectric for SystemClock. */
@RunWith(RobolectricTestRunner::class)
class SmbBrowserTest {
    /** A share signed in to, with one film; what closed it, broken or not, in order. */
    private class Share : SmbShare {
        var hungUp = false
        var failure: Throwable? = null
        val closes = mutableListOf<Boolean>()

        override fun list(path: String): List<SmbEntry> {
            failure?.let { throw it }
            return listOf(SmbEntry("Dune (2021).mkv", isFolder = false, size = 4))
        }

        override fun read(path: String, offset: Long, length: Int): ByteArray {
            failure?.let { throw it }
            return ByteArray(length)
        }

        override val isOpen: Boolean get() = !hungUp && closes.isEmpty()

        override fun close(broken: Boolean) {
            closes += broken
        }
    }

    private val opened = mutableListOf<Share>()
    private val browser = SmbBrowser(
        connect = { folder ->
            if (folder.password.value != PASSWORD) throw smbStatus(NtStatus.STATUS_LOGON_FAILURE)
            Share().also(opened::add)
        },
    )

    private fun folder(share: String = "films", user: String = "kodi") = SourceEntity(
        name = share, kind = SourceKind.MOVIES, protocol = Protocol.SMB, host = "192.168.1.10", share = share, path = "",
        username = user, password = Secret(PASSWORD),
    )

    @Test
    fun aSessionServesEveryFileOfAPass() = runTest {
        val films = folder()
        browser.list(films)
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        assertEquals(1, opened.size)
    }

    /** Measured on elapsedRealtime: the wall clock set back an hour would keep it for an hour more. */
    @Test
    fun aSessionLeftIdleIsClosedThenOpenedAgain() = runTest {
        val films = folder()
        browser.list(films)
        ShadowSystemClock.advanceBy(Duration.ofMillis(KeptConnections.IDLE_MS))
        browser.list(films)
        assertEquals(2, opened.size)
        assertEquals("signed off, not left to the NAS", listOf(false), opened[0].closes)
    }

    @Test
    fun twoSharesOfANasHaveASessionEachClosedWhenThePassEnds() = runTest {
        browser.list(folder("films"))
        browser.list(folder("shows"))
        assertEquals(2, opened.size)
        browser.closeIdle()
        assertEquals(listOf(listOf(false), listOf(false)), opened.map { it.closes })
    }

    @Test
    fun aSessionTheNasHungUpIsOpenedAgain() = runTest {
        browser.list(folder())
        opened.single().hungUp = true
        browser.list(folder())
        assertEquals(2, opened.size)
    }

    /** As smbj 0.14 throws a request answered too late: a connection that stopped answering. */
    @Test
    fun aSessionThatTimesOutIsHungUpAndOpenedAgain() = runTest {
        browser.list(folder())
        opened.single().failure = SMBRuntimeException(TransportException(TimeoutException("Timeout expired")))
        browser.list(folder())
        assertEquals("hung up without signing off", listOf(true), opened[0].closes)
        assertEquals(2, opened.size)
    }

    /** The NAS refused a file, or no longer has it: the session is sound, and serves the next file. */
    @Test
    fun aFileRefusedLeavesTheSessionOpen() = runTest {
        val films = folder()
        browser.list(films)
        val share = opened.single()
        share.failure = smbStatus(NtStatus.STATUS_ACCESS_DENIED)
        val refused = runCatching { browser.readPrefix(films, "movie.nfo", 16) }.exceptionOrNull()
        assertEquals(NtStatus.STATUS_ACCESS_DENIED, (refused as SMBApiException).status)
        share.failure = null
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        assertEquals(1, opened.size)
        assertEquals(emptyList<Boolean>(), share.closes)
    }

    @Test
    fun aSessionTheNasEndedIsOpenedAgain() = runTest {
        browser.list(folder())
        opened.single().failure = smbStatus(NtStatus.STATUS_NETWORK_SESSION_EXPIRED)
        browser.list(folder())
        assertEquals(2, opened.size)
        assertEquals(listOf(true), opened[0].closes)
    }

    /** The form tests what it says: a session kept by a scan, signed in with the password stored, does not answer for it. */
    @Test
    fun aTestSignsInOfItsOwn() = runTest {
        val films = folder()
        browser.list(films)
        val typed = films.copy(password = Secret("wrong"))
        val refused = runCatching { browser.test(typed) }.exceptionOrNull()
        assertEquals(ScanErrorKind.LOGIN_FAILED, refused?.let { ScanErrors.classify(it).kind })

        assertEquals(1, browser.test(films))
        assertEquals(2, opened.size)
        assertEquals("signed off once tested", listOf(false), opened[1].closes)
        assertEquals("the scan's session stays", emptyList<Boolean>(), opened[0].closes)
        browser.list(films)
        assertEquals(2, opened.size)
    }

    @Test
    fun forgettingAShareClosesItsSessionsWhoeverSignedIn() = runTest {
        browser.list(folder("films", user = "kodi"))
        browser.list(folder("films", user = ""))
        browser.list(folder("shows", user = "kodi"))
        browser.forget(folder("films", user = "someone else"))
        assertEquals(listOf(listOf(false), listOf(false), emptyList()), opened.map { it.closes })
    }

    @Test
    fun theAppsBrowsersCloseWhatAPassKept() = runTest {
        val browsers = RemoteBrowsers(browser, NfsBrowser())
        browsers.of(folder()).list(folder())
        browsers.closeIdle()
        assertEquals(listOf(false), opened.single().closes)
        browsers.of(folder()).list(folder())
        browsers.forget(folder())
        assertEquals(listOf(false), opened[1].closes)
    }

    private companion object {
        const val PASSWORD = "secret"

        /** As Share.receive throws an answer that is not a success. */
        fun smbStatus(status: NtStatus) =
            SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, "Create failed for \\\\nas\\films", null)
    }
}
