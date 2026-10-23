package app.houselights.journal

import android.content.Context
import app.houselights.BuildConfig
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.providers.local.Protocol
import app.houselights.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * The journal as the sheet opens it, on the loopback for the test: the app's lines from logcat, masked, under the line
 * to paste; almost nothing below a few lines; no home network, or no page when it cannot listen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr")
class LocalJournalTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    /** [lines] in threadtime form, which logcat prints to the app with a blank uid column after the time. */
    private fun journal(
        lines: List<String> = lines(AppLog.FEW_LINES),
        settings: AppSettings = AppSettings(loaded = true, source = SourceConfig.Local, trakt = TRAKT),
        protocols: List<Protocol> = listOf(Protocol.NFS, Protocol.NFS),
        host: () -> InetAddress? = { InetAddress.getLoopbackAddress() },
    ) = LocalJournal(
        context,
        settings = { settings },
        protocols = { protocols },
        log = AppLog(run = { _ -> FakeProcess(lines.joinToString("") { "${it.replaceFirst(TIME, "$TIME$NO_UID")}\n" }) }),
        host = host,
    )

    /** What [journal] tells first, its page served meanwhile until the test ends. */
    private fun open(journal: LocalJournal): JournalAccess {
        val access = CompletableDeferred<JournalAccess>()
        scope.launch { journal.serve { access.complete(it) } }
        return runBlocking { withTimeout(WAIT_MS) { access.await() } }
    }

    private fun address(access: JournalAccess) = (access as JournalAccess.Open).address

    private fun read(address: String): String = (URL(address).openConnection() as HttpURLConnection).run {
        connectTimeout = WAIT_MS.toInt()
        readTimeout = WAIT_MS.toInt()
        inputStream.bufferedReader().use { it.readText() }
    }

    /** The line to paste, the downloaded text's first. */
    private fun head(access: JournalAccess) = read("${address(access)}.txt").lineSequence().first()

    @Test
    fun thePageShowsTheAppsLinesMaskedUnderTheLineToPaste() {
        val secrets = listOf("$LINE I Playback: Launch smb://zidoo:p4ss@nas/film.mkv", "$LINE D Settings: password: hunter 2")
        val access = open(journal(lines(AppLog.FEW_LINES) + secrets))
        val page = read(address(access))
        val text = read("${address(access)}.txt")
        listOf(page, text).forEach { shown -> listOf("p4ss", "hunter").forEach { assertFalse(it, shown.contains(it)) } }
        assertTrue(page, page.contains("smb://<span class=\"m\">•••</span>@nas/film.mkv"))
        assertTrue(text, text.contains("Launch smb://•••@nas/film.mkv\n"))
        assertTrue(text, text.contains("$LINE I Scan    : line 20\n"))
        // The app and its version, the device, the source with its folders' protocols, Trakt, then the time.
        val head = text.lineSequence().first()
        assertTrue(head, head.startsWith("Houselights ${BuildConfig.VERSION_NAME} · "))
        assertTrue(head, head.contains(" · Mes fichiers (NFS) · Trakt connecté · "))
    }

    @Test
    fun theLineToPasteNamesEachProtocolOnce() {
        val head = head(open(journal(protocols = listOf(Protocol.SMB, Protocol.NFS, Protocol.SMB))))
        assertTrue(head, head.contains(" · Mes fichiers (NFS, SMB) · "))
        assertTrue(head(open(journal(protocols = emptyList()))).contains(" · Mes fichiers · Trakt connecté · "))
    }

    @Test
    fun theLineToPasteSaysJellyfinAndTraktNotConnected() {
        val jellyfin = SourceConfig.Jellyfin("http://nas:8096", "NAS", "u1", "lea", "t0k3n")
        val head = head(open(journal(settings = AppSettings(loaded = true, source = jellyfin))))
        assertTrue(head, head.contains(" · Jellyfin · Trakt non connecté · "))
        assertFalse(head, head.contains("t0k3n"))
    }

    @Test
    fun fewerLinesThanAFewIsAlmostNothing() {
        assertTrue((open(journal(emptyList())) as JournalAccess.Open).few)
        assertTrue((open(journal(lines(AppLog.FEW_LINES - 1))) as JournalAccess.Open).few)
        assertFalse((open(journal(lines(AppLog.FEW_LINES))) as JournalAccess.Open).few)
    }

    @Test
    fun withoutAHomeNetworkThereIsNoPage() {
        assertEquals(JournalAccess.Offline, open(journal(host = { null })))
    }

    /** An address of no interface here (TEST-NET-1): no port can be had on it. */
    @Test
    fun aPageThatCannotListenSaysSo() {
        assertEquals(JournalAccess.Failed, open(journal(host = { InetAddress.getByName("192.0.2.1") })))
    }

    private fun lines(count: Int) = (1..count).map { "$LINE I Scan    : line $it" }

    private companion object {
        const val TIME = "10-23 10:42:11.123"
        const val LINE = "$TIME  4321  4350"
        const val NO_UID = "      "
        const val WAIT_MS = 5_000L
        val TRAKT = TraktSession("acc3ss", "r3fresh", expiresAt = 0)
    }
}
