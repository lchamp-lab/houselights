package app.houselights.ui.design.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.TV
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A title's words show once the backdrop brings its picture up (at most `HOLD`); until then the last stay. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class WithItsPictureTest {
    @get:Rule
    val compose = createComposeRule()

    private var settled by mutableStateOf<ImageRef?>(null)
    private var item by mutableStateOf(film("1", "Heat"))

    private fun film(id: String, title: String, picture: Boolean = true) = MediaItem(
        id = id,
        kind = MediaKind.MOVIE,
        title = title,
        backdrop = if (picture) ImageRef("backdrop$id") { "test://backdrop/$id?w=$it" } else null,
    )

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalBackdropSettled provides { it == settled }) {
                WithItsPicture(item) { Text(it.title) }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Asserts [title] is displayed; titles on their way are laid out but not displayed. */
    private fun said(title: String) = compose.onNodeWithText(title).assertIsDisplayed()

    private fun select(next: MediaItem) {
        item = next
        Snapshot.sendApplyNotifications()
    }

    private fun bringUp(picture: ImageRef?) {
        settled = picture
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun theWordsWaitForThePicture() {
        show()
        said("Heat")
        select(film("2", "Ran"))
        compose.mainClock.advanceTimeBy(HlMotion.HOLD / 2)
        said("Heat")
        compose.onNodeWithText("Ran").assertDoesNotExist()
        bringUp(item.backdrop)
        compose.mainClock.advanceTimeBy(HlMotion.STANDARD.toLong() * 2)
        said("Ran")
    }

    @Test
    fun aPictureSlowToComeWaitsNoLongerThanHold() {
        show()
        select(film("2", "Ran"))
        compose.mainClock.advanceTimeBy(HlMotion.HOLD + HlMotion.STANDARD * 2)
        said("Ran")
    }

    @Test
    fun aTitleWithoutAPictureIsSaidAtOnce() {
        show()
        select(film("2", "Ran", picture = false))
        // Well before HOLD: nothing to wait for.
        compose.mainClock.advanceTimeBy(HlMotion.HOLD / 2)
        said("Ran")
    }
}
