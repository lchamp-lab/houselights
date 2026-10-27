package app.houselights.ui.detail

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.components.SAMPLE_PX
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.press
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections

/**
 * DESIGN.md, Saga page: the room is the open panel's diffuse light once focus settled, the house lights down on a film
 * the library lacks; focus going down to Lire changes nothing in it, and Back brings the saga's light back. Read from
 * the pictures the room asks for, at its field's sample width, every picture served and none kept in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SagaRoomTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val settle = SettleMainLooper()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun serve() {
        val server = pictureServer(OPAQUE) { data, _ -> asked += "$data" }
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .dispatcher(Dispatchers.Unconfined)
                .memoryCachePolicy(CachePolicy.DISABLED)
                .components { add(server) }
                .build(),
        )
    }

    @After
    fun stop() {
        work.cancel()
        Coil.reset()
    }

    /** The pictures the room asked for since last read, by name: only it asks for a picture at its field's width. */
    private fun room(): List<String> = synchronized(asked) {
        val names = asked.filter { it.endsWith("?w=$SAMPLE_PX") }.map { it.removePrefix("test://").substringBefore('?') }
        asked.clear()
        names.distinct()
    }

    /** Focus moved, then left to settle, the room's picture asked. */
    private fun press(key: Key) {
        compose.press(key)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + HlMotion.HOLD)
        compose.waitForIdle()
    }

    @Test
    fun theRoomFollowsTheOpenPanelDarkOnAFilmTheLibraryLacks() {
        SagaPage(compose, duneSource(), work).open(duneSaga.id)
        assertEquals("on arrival, the saga's", listOf("dune-saga"), room())
        press(Key.DirectionUp)
        assertEquals("on the film Lire plays", listOf("dune2-backdrop"), room())
        press(Key.DirectionDown)
        assertEquals("focus gone down to Lire", emptyList<String>(), room())
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        assertEquals("a film the library lacks: none, the house lights down", emptyList<String>(), room())
        press(Key.DirectionDown)
        assertEquals("down to Lire, still none", emptyList<String>(), room())
    }

    @Test
    fun backBringsTheSagasLightBack() {
        SagaPage(compose, duneSource(), work).open(duneSaga.id)
        press(Key.DirectionUp)
        room()
        press(Key.DirectionRight)
        assertEquals(emptyList<String>(), room())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + HlMotion.HOLD)
        compose.waitForIdle()
        assertEquals("the saga's panel open again", listOf("dune-saga"), room())
    }

    private companion object {
        /** Every picture an opaque orange, so a room has a light to take. */
        const val OPAQUE = 0xFFE07020.toInt()
    }
}
