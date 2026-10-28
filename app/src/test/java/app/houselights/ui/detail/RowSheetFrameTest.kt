package app.houselights.ui.detail

import android.graphics.Color
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.ui.design.HlFocus
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import kotlin.math.roundToInt

/**
 * DESIGN.md, Focus: under a sheet a row's card opens, focus is out, the card keeping it without its row's frame until
 * the sheet goes. Read from a title's page as drawn on its own window, the sheet's apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RowSheetFrameTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Saves at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = work.cancel()

    /** Menu on an episode opens its options over the season's row. */
    @Test
    fun anEpisodesOptionsLeaveItsCardUnframed() {
        val provider = FakeProvider().apply {
            items["show"] = series("show")
            seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2)))
            episodes = mapOf("s1" to (1..2).map { episode("s1e$it", "s1", it) })
        }
        page("show", provider)
        val card = compose.onNode(hasText("s1e1") and hasClickAction())
        card.requestFocus()
        unframedUnderTheSheetOf(card) { compose.press(Key.Menu) }
    }

    /** OK on a film of its saga the library lacks opens its synopsis over the saga's row. */
    @Test
    fun aSagasAbsentFilmsSynopsisLeavesItsCardUnframed() {
        val heat = film("m1", "Heat")
        val saga = MediaItem("saga", MediaKind.COLLECTION, "Heat")
        val lacking = SagaPart(null, "Heat 2", 2027, overview = "Neil McCauley, avant.")
        val tmdb = FakeCatalog().apply { sagas = mapOf(saga.id to Saga(saga, listOf(SagaPart(heat, "Heat", 1995), lacking))) }
        val provider = FakeProvider().apply {
            items[heat.id] = heat
            catalog = tmdb
            capabilities = capabilities.copy(sagaParts = true)
        }
        val source = object : MediaProvider by provider {
            override suspend fun collectionOf(item: MediaItem) = saga

            override suspend fun collectionItems(collection: MediaItem) = listOf(heat, film("m2"))
        }
        page(heat.id, source)
        // Down from the actions to the saga's row, Heat first, then Right to the film it lacks, read as its title, its
        // year and that the library lacks it.
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionRight)
        val card = compose.onNode(hasContentDescription("Heat 2", substring = true) and hasClickAction())
        unframedUnderTheSheetOf(card) { compose.press(Key.DirectionCenter) }
    }

    private fun page(id: String, source: MediaProvider) {
        // The app's window is the room's black (its theme's windowBackground) under every screen.
        compose.runOnUiThread { compose.activity.window.setBackgroundDrawableResource(R.color.room) }
        val vm = DetailViewModel(id, { source }, FakeEvents(), work)
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
    }

    /** Framed with focus, then unframed under the sheet [open] opens, then framed again once Back closes it. */
    private fun unframedUnderTheSheetOf(card: SemanticsNodeInteraction, open: () -> Unit) {
        card.assertIsFocused()
        val lit = frameLeftOf(card)
        assertTrue("the focused card is framed: $lit", lit > FRAMED)

        open()
        assertTrue("a sheet shows", ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals("no frame under the sheet", 0, frameLeftOf(card))

        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        card.assertIsFocused()
        assertEquals("framed again as the sheet goes", lit, frameLeftOf(card))
    }

    /**
     * How much lighter the page is drawn, on its own window, on the line a frame takes left of [card] than just past the
     * frame's reach, at the card's middle: 0 unframed.
     */
    private fun frameLeftOf(card: SemanticsNodeInteraction): Int {
        val node = card.fetchSemanticsNode()
        val window = compose.drawWindow()
        val y = (node.positionInWindow.y + node.size.height / 2).roundToInt()
        val left = node.positionInWindow.x
        val line = window.getPixel((left - HlFocus.frameGap.value - HlFocus.frame.value / 2).roundToInt(), y)
        val past = window.getPixel((left - HlFocus.reach.value - PAST_REACH).roundToInt(), y)
        return Color.red(line) - Color.red(past)
    }

    private companion object {
        /** A frame's white over the room's black, a canvas dp a test pixel. */
        const val FRAMED = 192

        /** Pixels past a frame's outer edge, on the page beside it. */
        const val PAST_REACH = 2f
    }
}
