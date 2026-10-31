package app.houselights.ui.design.components

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.testing.pictureServer
import coil.request.CachePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A count's posters made once ([CutPosterCache.make]), whoever asks and whoever goes away; let go, their colours stay. */
@RunWith(RobolectricTestRunner::class)
class CutPostersTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val px: (Dp) -> Int = { it.value.toInt() }

    /** Each test's own, as a new process's. */
    private val cuts = CutPosterCache()

    private fun poster(id: String) = MediaItem(id, MediaKind.MOVIE, id, poster = ImageRef("p-$id") { "https://img.test/$id?w=$it" })

    @get:Rule
    val coil = CoilRule()

    /** A making cancelled while another waits on it, its waiter resumed before its maker is done: the waiter makes them. */
    @Test
    fun theWaiterOfAMakingCancelledMakesThemItself() = runBlocking {
        val held = CompletableDeferred<Unit>()
        // Coil's work where it is resumed: the main thread, which this test holds, is never handed a step.
        val server = pictureServer { _, _ -> held.await() }
        coil.serve(server)
        val items = listOf(MediaItem("cut-waiter", MediaKind.MOVIE, "Heat", poster = ImageRef("p-cut") { "https://img.test/cut?w=$it" }))
        val maker = launch { cuts.make(context, items, px) }
        // The maker in its load.
        yield()
        var made: CutPosters? = null
        // Resumed at once by the maker's cancelling, in the middle of it.
        val waiter = launch(Dispatchers.Unconfined) { made = cuts.make(context, items, px) }
        maker.cancelAndJoin()
        held.complete(Unit)
        waiter.join()
        assertNotNull(made?.posters?.single())
    }

    /** A count's posters changed with a reload: the last ones stay drawn until the new ones are made, never ink between. */
    @Test
    fun theLastPostersStayUntilTheNextAreMade() {
        val held = CompletableDeferred<Unit>()
        val server = pictureServer { data, _ -> if ("next" in data.toString()) held.await() }
        coil.serve(server)
        var items by mutableStateOf(listOf(poster("cut-first")))
        var shown: CutPosters? = null
        compose.setContent { CompositionLocalProvider(LocalCutPosters provides cuts) { shown = rememberCutPosters(items) } }
        compose.waitUntil(TIMEOUT_MS) { shown?.posters?.singleOrNull() != null }
        val first = shown
        items = listOf(poster("cut-next"))
        compose.waitForIdle()
        assertSame("the last posters stay", first, shown)
        held.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { shown !== first && shown?.posters?.singleOrNull() != null }
    }

    /** Let go before playback ([CutPosterCache.clear]): a door draws its tones and tint at once while its posters are cut again. */
    @Test
    fun aCutLetGoKeepsItsColoursWhileItsPostersAreCutAgain() {
        val held = CompletableDeferred<Unit>()
        var hold = false
        val server = pictureServer(fill = RED) { _, _ -> if (hold) held.await() }
        // Every poster fetched again, as after Coil's memory is cleared too.
        coil.serve(server) { memoryCachePolicy(CachePolicy.DISABLED) }
        val items = listOf(poster("cut-again"))
        val made = runBlocking { cuts.make(context, items, px) }
        cuts.clear()
        assertNull("its posters let go", cuts.of(cutKeys(items)))
        hold = true
        var shown: CutPosters? = null
        compose.setContent { CompositionLocalProvider(LocalCutPosters provides cuts) { shown = rememberCutPosters(items) } }
        compose.waitForIdle()
        assertEquals("its glow at once", made.tones, shown?.tones)
        assertSame("its tint at once", made.tint, shown?.tint)
        assertTrue("its digits ink until cut again", shown?.posters.orEmpty().none { it != null })
        held.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { shown?.posters?.singleOrNull() != null }
    }

    /** Let go, the cut a door on screen draws stays: the door holds its posters anyway, and would cut them again once back. */
    @Test
    fun theCutADoorOnScreenDrawsStaysWhenTheRestGo() {
        coil.serve(pictureServer())
        val drawn = listOf(poster("cut-drawn"))
        val away = listOf(poster("cut-away"))
        runBlocking { cuts.make(context, away, px) }
        var open by mutableStateOf(true)
        var shown: CutPosters? = null
        compose.setContent { CompositionLocalProvider(LocalCutPosters provides cuts) { if (open) shown = rememberCutPosters(drawn) } }
        compose.waitUntil(TIMEOUT_MS) { shown?.posters?.singleOrNull() != null }
        cuts.clear()
        assertSame("drawn: kept", shown, cuts.of(cutKeys(drawn)))
        assertNull("drawn by no door: let go", cuts.of(cutKeys(away)))
        open = false
        compose.waitForIdle()
        cuts.trim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull("its door gone: let go", cuts.of(cutKeys(drawn)))
    }

    /** Short of memory in the background, the cuts go as Coil's own pictures do; merely hidden, they stay. */
    @Test
    fun theSystemShortOfMemoryInTheBackgroundLetsTheCutsGo() {
        coil.serve(pictureServer())
        val items = listOf(poster("cut-trimmed"))
        val made = runBlocking { cuts.make(context, items, px) }
        cuts.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertSame("hidden: kept", made, cuts.of(cutKeys(items)))
        cuts.trim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull("in the background: let go", cuts.of(cutKeys(items)))
        assertEquals("its glow kept", made.tones, cuts.shown(cutKeys(items))?.tones)
    }

    private companion object {
        const val RED = 0xFFC0392B.toInt()
        const val TIMEOUT_MS = 5_000L
    }
}
