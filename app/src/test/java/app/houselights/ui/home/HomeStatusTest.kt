package app.houselights.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.houselights.core.SourceConfig
import app.houselights.core.TraktSession
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.nfsFolder
import app.houselights.settings.AppSettings
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's settings door states what the app knows of its source, its Trakt account and its last scan, and nothing it does not. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomeStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val session = TraktSession("a", "r", expiresAt = 0)
    private val local = AppSettings(loaded = true, source = SourceConfig.Local, trakt = session)
    private val server = SourceConfig.Jellyfin("https://jf.test", "Salon", "u", "léa", "token")
    private fun folder(id: Long, host: String, at: Long) =
        nfsFolder(host = host, share = "volume1", path = "Films", id = id, lastScanAt = at)

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
}
