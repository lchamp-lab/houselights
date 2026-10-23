package app.houselights.ui.design.components

import android.app.Application
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A count's posters made once ([makeCutPosters]), whoever asks and whoever goes away. */
@RunWith(RobolectricTestRunner::class)
class CutPostersTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val px: (Dp) -> Int = { it.value.toInt() }

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
}
