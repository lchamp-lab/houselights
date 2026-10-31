package app.houselights.providers.local

import app.houselights.core.SecretUnreadException
import app.houselights.testing.smbStatus
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** SMB sessions on a NAS held in memory: which are kept, closed and reopened. On Robolectric for SystemClock. */
@RunWith(RobolectricTestRunner::class)
class SmbBrowserTest {
    /** Folders of the share by path, "\" separators; a missing folder is refused. */
    private var tree = mapOf("" to listOf(SmbEntry("Dune (2021).mkv", isFolder = false, size = 4)))

    /** Set, the next share signed in to says when it is first listed, then waits for [released] before answering. */
    private var holdNextListing = false
    private val listingHeld = CountDownLatch(1)
    private val released = CountDownLatch(1)

    /** A share signed in to, holding [tree], recording what closed it (broken or not), in order. */
    private inner class Share : SmbShare {
        var hungUp = false
        var failure: Throwable? = null
        val closes = mutableListOf<Boolean>()
        val read = mutableListOf<String>()
        private val held = holdNextListing.also { holdNextListing = false }

        override fun list(path: String): List<SmbEntry> {
            if (held) {
                listingHeld.countDown()
                released.await()
            }
            failure?.let { throw it }
            return tree[path] ?: throw smbStatus(NtStatus.STATUS_ACCESS_DENIED)
        }

        override fun read(path: String, offset: Long, length: Int): ByteArray {
            failure?.let { throw it }
            read += path
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

    private fun folder(share: String = "films", user: String = "kodi") =
        smbFolder(name = share, share = share, path = "", username = user, password = PASSWORD)

    /** The listing on a session of its own, signed off once listed; the reads that follow on one kept session. */
    @Test
    fun aSessionServesEveryFileOfAPass() = runTest {
        val films = folder()
        browser.list(films)
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        browser.readPrefix(films, "Dune (2021)/movie.nfo", 16)
        assertEquals(2, opened.size)
        assertEquals(listOf(listOf(false), emptyList()), opened.map { it.closes })
    }

    /** Measured on elapsedRealtime: a wall clock set back an hour would keep it an hour longer. */
    @Test
    fun aSessionLeftIdleIsClosedThenOpenedAgain() = runTest {
        val films = folder()
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        ShadowSystemClock.advanceBy(Duration.ofMillis(KeptConnections.IDLE_MS))
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        assertEquals(2, opened.size)
        assertEquals("signed off, not left to the NAS", listOf(false), opened[0].closes)
    }

    @Test
    fun twoSharesOfANasHaveASessionEachClosedWhenThePassEnds() = runTest {
        browser.readPrefix(folder("films"), "Dune (2021).mkv", 16)
        browser.readPrefix(folder("shows"), "Severance/S01E01.mkv", 16)
        assertEquals(2, opened.size)
        browser.closeIdle()
        assertEquals(listOf(listOf(false), listOf(false)), opened.map { it.closes })
    }

    /**
     * A session the NAS hung up, ended, or that stopped answering (as smbj 0.14 throws a request answered too late) is hung
     * up without signing off, and another one opened.
     */
    @Test
    fun aSessionThatBrokeIsHungUpAndOpenedAgain() = runTest {
        val breaks = mapOf<String, (Share) -> Unit>(
            "hung up" to { it.hungUp = true },
            "timed out" to { it.failure = SMBRuntimeException(TransportException(TimeoutException("Timeout expired"))) },
            "ended" to { it.failure = smbStatus(NtStatus.STATUS_NETWORK_SESSION_EXPIRED) },
        )
        breaks.entries.forEachIndexed { i, (how, broken) ->
            // A share of its own each, so that none reuses the session the one before opened.
            val films = folder(share = "films-$i")
            opened.clear()
            browser.folders(films)
            broken(opened.single())
            browser.folders(films)
            assertEquals(how, 2, opened.size)
            assertEquals("$how: hung up without signing off", listOf(true), opened[0].closes)
        }
    }

    /** The NAS refused a file, or no longer has it: the session is sound and serves the next file. */
    @Test
    fun aFileRefusedLeavesTheSessionOpen() = runTest {
        val films = folder()
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        val share = opened.single()
        share.failure = smbStatus(NtStatus.STATUS_ACCESS_DENIED)
        val refused = runCatching { browser.readPrefix(films, "movie.nfo", 16) }.exceptionOrNull()
        assertEquals(NtStatus.STATUS_ACCESS_DENIED, (refused as SMBApiException).status)
        share.failure = null
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        assertEquals(1, opened.size)
        assertEquals(emptyList<Boolean>(), share.closes)
    }

    /**
     * A password the Keystore did not open for now is never tried on the NAS as an empty one, which it would refuse as a
     * wrong password: signing in stops before connecting, here to a port nothing listens on.
     */
    @Test
    fun aPasswordNotOpenedIsNeverTriedOnTheNas() = runTest {
        val unopened = folder().copy(host = "127.0.0.1", password = Secret("", unopened = "sealed1:AAAA"))
        val refused = runCatching { SmbBrowser().test(unopened) }.exceptionOrNull()
        assertTrue("$refused", refused is SecretUnreadException)
    }

    /** The form tests what it was given: a scan's session, signed in with the stored password, is not reused. */
    @Test
    fun aTestSignsInOfItsOwn() = runTest {
        val films = folder()
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        val typed = films.copy(password = Secret("wrong"))
        val refused = runCatching { browser.test(typed) }.exceptionOrNull()
        assertEquals(ScanErrorKind.LOGIN_FAILED, refused?.let { ScanErrors.classify(it).kind })

        assertEquals(FolderLevel(emptyList(), videos = 1), browser.test(films))
        assertEquals(2, opened.size)
        assertEquals("signed off once tested", listOf(false), opened[1].closes)
        assertEquals("the scan's session stays", emptyList<Boolean>(), opened[0].closes)
        browser.readPrefix(films, "Dune (2021).mkv", 16)
        assertEquals(2, opened.size)
    }

    /** Another user's folder, or lost+found, in the share: skipped and named, and the session signed off, not hung up. */
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
        assertEquals(listOf(false), opened.single().closes)
    }

    @Test
    fun aFileIsReadByItsPathInTheShare() = runTest {
        val films = folder().copy(path = "/Films/4K/")
        browser.readPrefix(films, "Dune (2021)/movie.nfo", 16)
        browser.readPrefix(folder(), "Dune (2021).mkv", 16)
        assertEquals(listOf("Films\\4K\\Dune (2021)\\movie.nfo", "Dune (2021).mkv"), opened.flatMap { it.read })
    }

    /**
     * The listing gives each file's modification date: an NFO rewritten in place keeps its size, not its date. It counts
     * its videos as it finds them (RemoteWalkTest holds how).
     */
    @Test
    fun aListingGivesWhenEachFileWasWritten() = runTest {
        tree = mapOf(
            "" to listOf(SmbEntry("Bugonia", isFolder = true, size = 0, lastModified = 1L)),
            "Bugonia" to listOf(
                SmbEntry("Bugonia.mkv", isFolder = false, size = 4, lastModified = 1_760_000_000_000L),
                SmbEntry("movie.nfo", isFolder = false, size = 2, lastModified = 1_760_000_060_000L),
            ),
        )
        val counted = mutableListOf<Int>()
        val listing = browser.list(folder()) { counted += it }
        assertEquals(listOf(1), counted)
        assertEquals(listOf(1_760_000_000_000L), listing.videos.map { it.lastModified })
        assertEquals(listOf(1_760_000_060_000L), listing.sidecars.map { it.lastModified })
    }

    /**
     * The add-folder flow's levels, on the share's kept session (the share list tested the account): subfolders by name,
     * hidden and system ones left out, its videos counted, a level by its path from the folder however slashed. A scan's
     * listing signs in on a session of its own, and leaves the kept one open.
     */
    @Test
    fun aLevelIsListedOnTheSharesKeptSession() = runTest {
        tree = mapOf(
            "" to listOf(
                SmbEntry("Séries", isFolder = true, size = 0),
                SmbEntry("films", isFolder = true, size = 0),
                SmbEntry("#recycle", isFolder = true, size = 0),
                SmbEntry("@eaDir", isFolder = true, size = 0),
                SmbEntry("Arrival", isFolder = true, size = 0),
                SmbEntry("Dune (2021).mkv", isFolder = false, size = 4),
                SmbEntry("movie.nfo", isFolder = false, size = 1),
            ),
            "films\\4K" to listOf(SmbEntry("Dune (2021)", isFolder = true, size = 0)),
        )
        val films = folder()
        assertEquals(FolderLevel(listOf("Arrival", "films", "Séries"), videos = 1), browser.folders(films))
        assertEquals(FolderLevel(listOf("Dune (2021)"), videos = 0), browser.folders(films, "/films/4K/"))
        browser.list(films)
        assertEquals("one session for the levels, one for the listing", 2, opened.size)
        assertEquals(listOf(emptyList(), listOf(false)), opened.map { it.closes })
    }

    /**
     * A scan listing thousands of folders on the share, and the add-folder flow going down a level of it with the same
     * account, then a folder saved after its address changed: neither waits for the listing to end.
     */
    @Test
    fun aLevelOrASaveWhileAPassListsTheShareWaitsForNoListing() {
        val films = folder()
        holdNextListing = true
        val pass = thread { runBlocking { browser.list(films) } }
        assertTrue(listingHeld.await(5, TimeUnit.SECONDS))

        var level: FolderLevel? = null
        val asked = thread {
            runBlocking {
                level = browser.folders(films)
                browser.forget(films)
            }
        }
        asked.join(5_000)
        val answered = !asked.isAlive
        released.countDown()
        pass.join(5_000)
        asked.join(5_000)
        assertTrue("a level shown and a folder saved while the pass lists", answered)
        assertEquals(FolderLevel(emptyList(), videos = 1), level)
        assertEquals(
            "the listing signed off, then the level's session as forgotten",
            listOf(listOf(false), listOf(false)),
            opened.map {
                it.closes
            },
        )
    }

    /** A folder the share does not hold, or the account may not read, fails as the form's test does; the session stays. */
    @Test
    fun aLevelTheShareRefusesFails() = runTest {
        val refused = runCatching { browser.folders(folder(), "Private") }.exceptionOrNull()
        assertEquals(ScanErrorKind.ACCESS_DENIED, refused?.let { ScanErrors.classify(it).kind })
        assertEquals(FolderLevel(emptyList(), videos = 1), browser.folders(folder()))
        assertEquals(1, opened.size)
    }

    @Test
    fun forgettingAShareClosesItsSessionsWhoeverSignedIn() = runTest {
        browser.folders(folder("films", user = "kodi"))
        browser.folders(folder("films", user = ""))
        browser.folders(folder("shows", user = "kodi"))
        browser.forget(folder("films", user = "someone else"))
        assertEquals(listOf(listOf(false), listOf(false), emptyList()), opened.map { it.closes })
    }

    @Test
    fun theAppsBrowsersCloseWhatAPassKept() = runTest {
        val browsers = RemoteBrowsers(browser, NfsBrowser())
        browsers.of(folder()).folders(folder())
        browsers.closeIdle()
        assertEquals(listOf(false), opened.single().closes)
        browsers.of(folder()).folders(folder())
        browsers.forget(folder())
        assertEquals(listOf(false), opened[1].closes)
    }

    /** What closing a session asks of the NAS through smbj: [SmbBrowser.signOff]'s steps, then hang-up. */
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

    /** A NAS gone silent (powered off, or readdressed by DHCP): each step would wait out the 15 s timeout. */
    @Test
    fun aSignOffTheNasDoesNotAnswerHangsUpAtOnce() {
        val expired = SMBRuntimeException(TransportException(TimeoutException("Timeout expired")))
        assertEquals(listOf("share", "hang up, forced"), signOff(shareFails = expired))
        assertEquals(listOf("share", "session", "hang up, forced"), signOff(sessionFails = expired))
    }

    /** The NAS answered, if only to refuse: it is there, and frees the session when told to. */
    @Test
    fun aSignOffTheNasRefusesGoesOn() {
        assertEquals(listOf("share", "session", "hang up"), signOff(shareFails = smbStatus(NtStatus.STATUS_NETWORK_NAME_DELETED)))
        assertEquals(listOf("share", "session", "hang up"), signOff())
        assertEquals("nothing asked of a broken connection", listOf("hang up, forced"), signOff(broken = true))
    }

    private companion object {
        const val PASSWORD = "secret"
    }
}
