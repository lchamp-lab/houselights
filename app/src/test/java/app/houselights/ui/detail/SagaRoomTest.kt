package app.houselights.ui.detail

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.houselights.R
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressBack
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Saga page: the room is the open panel's diffuse light once focus settled, the house lights down on a film
 * the library lacks; focus going down to Lire changes nothing in it, and Back brings the saga's light back; back from a
 * film's page, its panel shows its picture whole at once. Read from the screen as drawn, each picture in its own colour,
 * served at once and none kept in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SagaRoomTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val settle = SettleMainLooper()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Before
    fun serve() {
        // The app's window is the room's black (its theme's windowBackground) under every screen.
        compose.runOnUiThread { compose.activity.window.setBackgroundDrawableResource(R.color.room) }
        Coil.setImageLoader(
            ImageLoader.Builder(compose.activity)
                .dispatcher(Dispatchers.Unconfined)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .components { add(pictureServer(fillOf = ::colourOf)) }
                .build(),
        )
    }

    @After
    fun stop() {
        work.cancel()
        Coil.reset()
    }

    /** The saga's picture blue, Deuxième partie's backdrop orange, every other picture green. */
    private fun colourOf(data: Any): Int = when ("$data".removePrefix("test://").substringBefore('?')) {
        "dune-saga" -> BLUE
        "dune2-backdrop" -> ORANGE
        else -> GREEN
    }

    /** The colour drawn at [x], [y] on the canvas, the screen drawn whole. */
    private fun at(x: Dp, y: Dp): Int = compose.runOnUiThread {
        val window = compose.activity.window.decorView
        val screen = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888).also { window.draw(Canvas(it)) }
        val density = compose.activity.resources.displayMetrics.density
        screen.getPixel((x.value * density).toInt(), (y.value * density).toInt())
    }

    /** The room in the safe area's side margin, halfway down, beside the panels. */
    private fun room(): Int = at(HlSpace.safeH / 2, ROOM_Y)

    private fun Int.isBlack() = Color.red(this) <= DARK && Color.green(this) <= DARK && Color.blue(this) <= DARK

    private fun Int.isBlue() = Color.blue(this) > Color.red(this) + DARK && Color.blue(this) > Color.green(this)

    private fun Int.isOrange() = Color.red(this) > Color.blue(this) + DARK && Color.red(this) > Color.green(this)

    private fun Int.hex() = "#%08X".format(this)

    /** Focus moved, then left to settle, the room's fade over. */
    private fun press(key: Key) {
        compose.press(key)
        rest()
    }

    private fun rest() {
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + HlMotion.HOLD + HlMotion.TINT * 2L)
        compose.waitForIdle()
    }

    /** The room in [light] once it has come, a picture's being read away from the main thread first. */
    private fun assertLit(what: String, light: Int.() -> Boolean) {
        val lit = try {
            compose.waitUntil(LIGHT_MS) { room().light() }
            true
        } catch (_: ComposeTimeoutException) {
            false
        }
        assertTrue("$what: ${room().hex()}", lit)
    }

    @Test
    fun theRoomFollowsTheOpenPanelDarkOnAFilmTheLibraryLacks() {
        SagaPage(compose, duneSource(), work).open(duneSaga.id)
        rest()
        assertLit("on arrival, the saga's") { isBlue() }
        press(Key.DirectionUp)
        assertLit("on the film Lire plays") { isOrange() }
        press(Key.DirectionDown)
        assertTrue("focus gone down to Lire, its light kept: ${room().hex()}", room().isOrange())
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertTrue("a film the library lacks: the house lights down, ${room().hex()}", room().isBlack())
        press(Key.DirectionDown)
        assertTrue("down to Lire, still down: ${room().hex()}", room().isBlack())
    }

    @Test
    fun backBringsTheSagasLightBack() {
        SagaPage(compose, duneSource(), work).open(duneSaga.id)
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertTrue(room().hex(), room().isBlack())
        compose.pressBack()
        // Back is taken, then the room left to settle.
        compose.waitForIdle()
        rest()
        assertLit("the saga's panel open again") { isBlue() }
    }

    /** Back from a film's page, its panel shows its backdrop whole from its first frames, nothing rising. */
    @Test
    fun backFromAFilmsPageItsPictureIsWholeAtOnce() {
        val page = SagaPage(compose, duneSource(), work).also { it.open(duneSaga.id) }
        press(Key.DirectionUp)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf(duneTwo.id), page.opened)
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page.shown = true }
        val second = hasContentDescription(compose.activity.getString(R.string.saga_card_name, duneTwo.title, 2) + ", 2024")
        var frames = 0
        while (compose.onAllNodes(second).fetchSemanticsNodes().isEmpty()) {
            check(frames++ < FRAMES) { "Not drawn after $FRAMES frames" }
            compose.mainClock.advanceTimeByFrame()
        }
        repeat(CUT_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        val panel = compose.onNode(second).getUnclippedBoundsInRoot()
        val picture = at((panel.left + panel.right) / 2, panel.top + PICTURE_Y)
        assertTrue("its backdrop whole, never its poster under it: ${picture.hex()}", picture.isOrange())
    }

    private companion object {
        const val BLUE = 0xFF2050E0.toInt()
        const val ORANGE = 0xFFE07020.toInt()
        const val GREEN = 0xFF20C040.toInt()

        /** A channel at most this bright is the black room; a colour at least this much brighter in it leads. */
        const val DARK = 4

        /** Halfway down the canvas, under the top veil and over the bottom one. */
        val ROOM_Y = 270.dp

        /** In an open panel's picture, under its top melt and over its words'. */
        val PICTURE_Y = 140.dp

        /** How long a room's light may take to come, its picture read on another thread. */
        const val LIGHT_MS = 5_000L

        /** Frames watched coming back, and those its picture may take once it is drawn: well under its rise's. */
        const val FRAMES = 20
        const val CUT_FRAMES = 3
    }
}
