package app.houselights.ui.util

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen that cannot load (`HlErrorState`) and a part of one (`PartFailed`) title the library out of reach by where it
 * lies, as home's case does: « NAS injoignable », « Serveur injoignable », « Bibliothèque injoignable » where that is not
 * known; a disk of the player's own unplugged, « Disque introuvable », wherever the library lies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class OfflineTitleTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private var place by mutableStateOf<LibraryPlace?>(null)
    private var error by mutableStateOf(UiError.OFFLINE)

    private fun said(id: Int) = compose.onNodeWithText(context.getString(id), useUnmergedTree = true)

    /** At each place in turn, the library out of reach and then a disk unplugged are said, Réessayer beside them. */
    private fun assertEachPlaceSaid() {
        mapOf(
            LibraryPlace.NAS to R.string.error_offline_nas_title,
            LibraryPlace.SERVER to R.string.error_offline_server_title,
            null to R.string.error_offline_title,
        ).forEach { (at, title) ->
            place = at
            error = UiError.OFFLINE
            compose.waitForIdle()
            said(title).assertIsDisplayed()
            error = UiError.DISK_ABSENT
            compose.waitForIdle()
            said(R.string.error_disk_absent_title).assertIsDisplayed()
            said(R.string.disk_absent_usb).assertIsDisplayed()
            said(R.string.retry).assertIsDisplayed()
        }
    }

    @Test
    fun aScreenThatCannotLoadNamesWhereTheLibraryLies() {
        compose.setTvContent {
            CompositionLocalProvider(LocalLibraryPlace provides place) { HlErrorState(error, onRetry = {}) }
        }
        assertEachPlaceSaid()
    }

    @Test
    fun aPartThatCannotLoadNamesWhereTheLibraryLies() {
        compose.setTvContent {
            CompositionLocalProvider(LocalLibraryPlace provides place) { PartFailed(error, retrying = false, onRetry = {}) }
        }
        assertEachPlaceSaid()
    }
}
