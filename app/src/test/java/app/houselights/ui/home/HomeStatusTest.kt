package app.houselights.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import app.houselights.core.FailureKind
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home's settings door states what the app knows of its source, its Trakt account and its last scan, and nothing it does not. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts: what fits the door is measured.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val session = TraktSession("a", "r", expiresAt = 0)
    private val local = AppSettings(loaded = true, source = SourceConfig.Local, trakt = session)
    private val server = SourceConfig.Jellyfin("https://jf.test", "Salon", "u", "léa", "token")
    private fun folder(id: Long, host: String, at: Long) =
        nfsFolder(host = host, share = "volume1", path = "Films", id = id, lastScanAt = at).toFolder()

    @Test
    fun theFoldersSayTheirServerTraktAndTheirLastScan() {
        val status = homeStatus(local, listOf(folder(1, "nas", 10L), folder(2, "nas", 20L)), ScanState(), traktAvailable = true)
        assertEquals(HomeStatus(StatusSource.Folders(listOf("nas"), 2), trakt = true, scannedAt = 20L), status)
    }

    @Test
    fun traktIsSaidOnlyWhereItFollowsTheSource() {
        assertNull("this build has no Trakt keys", homeStatus(local, emptyList(), ScanState(), traktAvailable = false)?.trakt)
        assertEquals(false, homeStatus(local.copy(trakt = session.copy(ended = true)), emptyList(), ScanState(), true)?.trakt)
        val jellyfin = homeStatus(AppSettings(loaded = true, source = server, trakt = session), null, ScanState(), traktAvailable = true)
        assertEquals(HomeStatus(StatusSource.Server("Salon")), jellyfin)
    }

    @Test
    fun aRunningScanCounts() {
        val status = homeStatus(local, emptyList(), ScanState(running = true, processed = 3, total = 9), true)
        assertEquals("its progress read by the door alone", true, status?.scanning)
        assertEquals(status, homeStatus(local, emptyList(), ScanState(running = true, processed = 4, total = 9), true))
        assertEquals("never scanned", 0L, status?.scannedAt)
    }

    @Test
    fun theDoorStatesTheFolders() {
        compose.setTvContent {
            HomeGrid(fullHome(status = HomeStatus(StatusSource.Folders(listOf("nas"), 1), trakt = true, scannedAt = 0L)))
        }
        compose.onNodeWithText("Source").assertIsDisplayed()
        compose.onNodeWithText("My files").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Last scan").assertIsDisplayed()
    }

    @Test
    fun theDoorStatesTheServerAndNothingItCannotKnow() {
        compose.setTvContent { HomeGrid(fullHome(status = HomeStatus(StatusSource.Server("Salon")))) }
        compose.onNodeWithText("Salon").assertIsDisplayed()
        compose.onNodeWithText("Trakt").assertDoesNotExist()
        compose.onNodeWithText("Last scan").assertDoesNotExist()
    }

    @Test
    fun theLastScansTmdbProblemIsStated() {
        val scan = ScanState(tmdbIssue = FailureKind.TMDB_KEY_REFUSED)
        assertEquals(UiError.TMDB_KEY_REFUSED, homeStatus(local, emptyList(), scan, traktAvailable = true)?.tmdb)
        assertNull("a server is not scanned", homeStatus(local.copy(source = server), null, scan, true)?.tmdb)
    }

    /** A problem TMDB had takes the source's place, the least of the facts; the last scan stays under it. */
    @Test
    fun aTmdbProblemTakesTheSourcesPlace() {
        val status = HomeStatus(StatusSource.Folders(listOf("nas"), 1), trakt = true, scannedAt = 0L, tmdb = UiError.TMDB_KEY_REFUSED)
        compose.setTvContent { HomeGrid(fullHome(status = status)) }
        listOf("TMDB", "Key refused", "Last scan").forEach { assertTrue(it, drawn(it)) }
        assertFalse(drawn("Source"))
        assertFalse("TMDB said once", drawn("TMDB refuses this key"))
    }

    /** TMDB's certificate refused (the device's clock reset) is said as such, not as TMDB out of reach. */
    @Test
    fun aRefusedCertificateIsSaidAsSuch() {
        val scan = ScanState(tmdbIssue = FailureKind.CERTIFICATE)
        val status = checkNotNull(homeStatus(local, listOf(folder(1, "nas", 0L)), scan, traktAvailable = true))
        assertEquals(UiError.CERTIFICATE, status.tmdb)
        compose.setTvContent { HomeGrid(fullHome(status = status)) }
        listOf("TMDB", "Certificate refused").forEach { assertTrue(it, drawn(it)) }
        assertFalse(drawn("Unreachable"))
    }

    /** At the largest text, one line of facts fits: the last scan's, before Trakt's and the source's. */
    @Test
    fun atTheLargestTextTheLastScanStays() {
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGEST_TEXT)) { HomeGrid(fullHome()) }
        }
        assertTrue(drawn("Last scan"))
        assertFalse(drawn("Source"))
    }

    /** "My files · nas": the host is written as the source is, only the dot between them quiet. */
    @Test
    fun theHostIsWrittenAsTheSource() {
        compose.setTvContent { HomeGrid(fullHome()) }
        assertEquals(inkOf("My files"), inkOf("nas"))
        assertNotEquals(inkOf("My files"), inkOf(" · "))
    }

    /** The words [text] where they are drawn: a fact, or its copy without its detail. */
    private fun shown(text: String): List<SemanticsNodeInteraction> {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().indices.map { nodes[it] }.filter { it.isDisplayed() }
    }

    private fun drawn(text: String) = shown(text).isNotEmpty()

    /** The colour [text] is drawn in. */
    private fun inkOf(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        shown(text).first().fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.first().layoutInput.style.color
    }

    private companion object {
        const val LARGEST_TEXT = 1.3f
    }
}
