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
    /** Folders of the share by path, "\" separators; a folder not there is refused. */
    private var tree = mapOf("" to listOf(SmbEntry("Dune (2021).mkv", isFolder = false, size = 4)))

    /** A share signed in to, holding [tree]; what closed it, broken or not, in order. */
    private inner class Share : SmbShare {
        var hungUp = false
        var failure: Throwable? = null
        val closes = mutableListOf<Boolean>()

        override fun list(path: String): List<SmbEntry> {
            failure?.let { throw it }
            return tree[path] ?: throw smbStatus(NtStatus.STATUS_ACCESS_DENIED)
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

    /** A folder of another user, or lost+found, in the share: passed over, named, and the session kept. */
    @Test
    fun aSubfolderRefusedIsPassedOver() = runTest {
        tree = mapOf(
            "" to listOf(SmbEntry("Films", isFolder = true, size = 0), SmbEntry("Private", isFolder = true, size = 0)),
            "Films" to listOf(SmbEntry("Dune (2021)", isFolder = true, size = 0)),
            "Films\\Dune (2021)" to listOf(SmbEntry("Dune (2021).mkv", isFolder = false, size = 4)),
        )
        val listing = browser.list(folder())
        assertEquals(listOf("Films/Dune (2021)/Dune (2021).mkv"), listing.videos.map { it.relativePath })
        assertEquals(listOf("Private"), listing.unreadable)
        assertEquals(emptyList<Boolean>(), opened.single().closes)
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

    /** What closing a session asks the NAS, in order, as smbj is asked it: [SmbBrowser.signOff]'s steps, then hanging up. */
    private fun signOff(broken: Boolean = false, shareFails: Throwable? = null, sessionFails: Throwable? = null): List<String> {
        val asked = mutableListOf<String>()
        fun step(what: String, failure: Throwable?): () -> Unit = {
            asked += what
            failure?.let { throw it }
        }
        SmbBrowser.signOff(broken, listOf("share" to step("share", shareFails), "session" to step("session", sessionFails))) { forced ->
            asked += if (forced) "hang up, forced" else "hang up"
        }
        return asked
    }

    /** A NAS gone silent (powered off, its address changed by DHCP): each step would wait out the 15 s timeout. */
    @Test
    fun aSignOffTheNasDoesNotAnswerHangsUpAtOnce() {
        val expired = SMBRuntimeException(TransportException(TimeoutException("Timeout expired")))
        assertEquals(listOf("share", "hang up, forced"), signOff(shareFails = expired))
        assertEquals(listOf("share", "session", "hang up, forced"), signOff(sessionFails = expired))
    }

    /** The NAS answered, if only to refuse: it is there, and frees the session when told. */
    @Test
    fun aSignOffTheNasRefusesGoesOn() {
        assertEquals(listOf("share", "session", "hang up"), signOff(shareFails = smbStatus(NtStatus.STATUS_NETWORK_NAME_DELETED)))
        assertEquals(listOf("share", "session", "hang up"), signOff())
        assertEquals("nothing asked of a broken connection", listOf("hang up, forced"), signOff(broken = true))
    }

    private companion object {
        const val PASSWORD = "secret"

        /** As Share.receive throws an answer that is not a success. */
        fun smbStatus(status: NtStatus) =
            SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, "Create failed for \\\\nas\\films", null)
    }
}
