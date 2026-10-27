package app.houselights

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.UserState
import app.houselights.ui.detail.DetailPage
import app.houselights.ui.detail.DetailViewModel
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.press
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The remote's keys on a show's page while the lights go down for the player, or under the launch screen. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class UnseenKeysTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val provider = FakeProvider().apply {
        items["show"] = series("show").copy(user = UserState(unplayedCount = 2))
        seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2, user = UserState(unplayedCount = 2))))
        episodes = mapOf("s1" to (1..2).map { episode("s1e$it", "s1", it) })
    }

    private var covered = false
    private var dimming = false
    private var cancelled = 0

    @After
    fun stop() = work.cancel()

    /** The show's page, focus on its first episode. */
    private fun onAnEpisode() {
        val vm = DetailViewModel("show", { provider }, FakeEvents(), work, tracksOf = { null })
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
        compose.press(Key.DirectionDown, times = 2)
        compose.onNode(hasText("s1e1") and hasClickAction()).assertIsFocused()
    }

    /** A key of the remote as Android routes it: to an open sheet's window, else through the activity. */
    private fun remote(keyCode: Int) {
        compose.runOnIdle {
            val sheet = sheet()?.window
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(action, keyCode)
                if (sheet != null) {
                    sheet.decorView.dispatchKeyEvent(event)
                } else {
                    dispatchUnseen(event, covered, dimming, cancel = { cancelled++ }, pass = compose.activity::dispatchKeyEvent)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun sheet() = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    private fun markWatched() = compose.onNode(hasText(context.getString(R.string.mark_watched)) and hasClickAction())

    @Test
    fun menuOpensAnEpisodesOptionsWithTheLightsUp() {
        onAnEpisode()
        remote(KeyEvent.KEYCODE_MENU)
        markWatched().assertIsFocused()
    }

    /** Neither Menu nor Pop Menu opens the options of the episode about to play: Back still cancels the cue. */
    @Test
    fun whileTheLightsGoDownMenuOpensNothingAndBackCancels() {
        onAnEpisode()
        dimming = true

        remote(KeyEvent.KEYCODE_MENU)
        remote(KeyEvent.KEYCODE_F12)
        markWatched().assertDoesNotExist()
        assertNull(sheet())

        remote(KeyEvent.KEYCODE_DPAD_CENTER)
        remote(KeyEvent.KEYCODE_BACK)
        assertEquals(1, cancelled)
        assertTrue(provider.calls.none { it.startsWith("played") })
    }

    @Test
    fun underTheLaunchScreenMenuOpensNothing() {
        onAnEpisode()
        covered = true

        remote(KeyEvent.KEYCODE_MENU)
        remote(KeyEvent.KEYCODE_F12)
        markWatched().assertDoesNotExist()
        assertNull(sheet())
    }
}
