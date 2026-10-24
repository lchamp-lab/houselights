package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.testing.pictureServer
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A count's posters made once ([makeCutPosters]), whoever asks and whoever goes away. */
@RunWith(RobolectricTestRunner::class)
class CutPostersTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val px: (Dp) -> Int = { it.value.toInt() }

    @Before
    fun forgetTheCuts() = forgetCutPosters()

    @After
    fun forgetTheLoader() = Coil.reset()

    /** A making cancelled while another waits on it, its waiter resumed before its maker is done: the waiter makes them. */
    @Test
    fun theWaiterOfAMakingCancelledMakesThemItself() = runBlocking {
        val held = CompletableDeferred<Unit>()
        // Coil's work where it is resumed: the main thread, which this test holds, is never handed a step.
        val server = pictureServer { _, _ -> held.await() }
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(server) }.build())
        val items = listOf(MediaItem("cut-waiter", MediaKind.MOVIE, "Heat", poster = ImageRef("p-cut") { "https://img.test/cut?w=$it" }))
        val maker = launch { makeCutPosters(context, items, px) }
        // The maker in its load.
        yield()
        var made: CutPosters? = null
        // Resumed at once by the maker's cancelling, in the middle of it.
        val waiter = launch(Dispatchers.Unconfined) { made = makeCutPosters(context, items, px) }
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
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(server) }.build())
        fun poster(id: String) = MediaItem(id, MediaKind.MOVIE, id, poster = ImageRef("p-$id") { "https://img.test/$id?w=$it" })
        var items by mutableStateOf(listOf(poster("cut-first")))
        var shown: CutPosters? = null
        compose.setContent { shown = rememberCutPosters(items) }
        compose.waitUntil(TIMEOUT_MS) { shown?.posters?.singleOrNull() != null }
        val first = shown
        items = listOf(poster("cut-next"))
        compose.waitForIdle()
        assertSame("the last posters stay", first, shown)
        held.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { shown !== first && shown?.posters?.singleOrNull() != null }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
