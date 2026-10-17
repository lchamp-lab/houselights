package app.houselights.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.pxOrElse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Under the launch screen, home says it is whole only once a frame is drawn with its rows, its hero
 * on the first title and that title's picture prepared: the launch screen then leaves on a home that
 * comes up whole, its backdrop with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeWholeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The launch screen over home, and the backdrop's picture prepared when it was first told. */
    private inner class Cover : LaunchScreen {
        override val covers = true
        var toldWith: Boolean? = null

        override fun drawn() {
            if (toldWith == null) toldWith = synchronized(loaded) { HERO_PICTURE in loaded }
        }
    }

    /** What came back from the server, by address. */
    private val loaded = mutableListOf<String>()

    /** The hero's picture, held until the test sends it. */
    private val backdropSent = CompletableDeferred<Unit>()

    private val server = Fetcher.Factory<Uri> { data, options, _ ->
        Fetcher {
            if (data.toString() == HERO_PICTURE) backdropSent.await()
            val (width, height) = options.size.let { it.width.pxOrElse { PICTURE_PX } to it.height.pxOrElse { PICTURE_PX } }
            val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            DrawableResult(BitmapDrawable(options.context.resources, picture), isSampled = false, dataSource = DataSource.NETWORK)
        }
    }

    private val events = object : EventListener {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            synchronized(loaded) { loaded += request.data.toString() }
        }
    }

    @After
    fun forgetTheLoader() = Coil.reset()

    private fun film(n: Int) = MediaItem(
        id = "film$n",
        kind = MediaKind.MOVIE,
        title = "Film $n",
        poster = ImageRef("poster$n") { "https://img.test/poster/$n?w=$it" },
        backdrop = ImageRef("backdrop$n") { "https://img.test/backdrop/$n?w=$it" },
    )

    @Test
    fun homeIsWholeOnceItsHerosPictureIsPrepared() {
        val context = compose.activity.applicationContext
        Coil.setImageLoader(ImageLoader.Builder(context).components { add(server) }.eventListener(events).build())
        val cover = Cover()
        val sections = listOf(HomeSection(SectionType.LATEST, (1..8).map(::film), landscape = false, libraryId = "films"))
        val home = HomeState(content = HomeContent.Rows(sections, firstScan = false))
        compose.setContent {
            CompositionLocalProvider(LocalLaunchScreen provides cover) {
                HouselightsTheme { HomeBands(home, MutableStateFlow(ScanState()), {}, {}, {}, {}, {}, {}) }
            }
        }
        repeat(FRAMES) { drawAFrame() }
        assertEquals("the rows are in, the hero's picture is not: nothing said", null, cover.toldWith)

        backdropSent.complete(Unit)
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (cover.toldWith == null && System.currentTimeMillis() < until) drawAFrame()
        assertTrue("said once its picture is prepared, and not before", cover.toldWith == true)
    }

    /**
     * The rows come while home waits under the launch screen: the frame they first show in has its
     * hero still on no title (it settles on the first one a frame later), so it is not whole, even
     * with nothing to prepare for the picture it shows.
     */
    @Test
    fun theFrameTheRowsComeInIsNotWholeYet() {
        val context = compose.activity.applicationContext
        Coil.setImageLoader(ImageLoader.Builder(context).components { add(server) }.eventListener(events).build())
        val cover = Cover()
        val sections = listOf(HomeSection(SectionType.LATEST, (1..8).map(::film), landscape = false, libraryId = "films"))
        var home by mutableStateOf(HomeState(content = HomeContent.Loading(firstScan = false)))
        compose.setContent {
            CompositionLocalProvider(LocalLaunchScreen provides cover) {
                HouselightsTheme { HomeBands(home, MutableStateFlow(ScanState()), {}, {}, {}, {}, {}, {}) }
            }
        }
        repeat(FRAMES) { drawAFrame() }
        assertEquals("still loading: nothing said", null, cover.toldWith)

        compose.mainClock.autoAdvance = false
        home = HomeState(content = HomeContent.Rows(sections, firstScan = false))
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { draw() }
        assertEquals("the rows, the hero on no title yet: nothing said", null, cover.toldWith)
        compose.mainClock.autoAdvance = true
        backdropSent.complete(Unit)
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (cover.toldWith == null && System.currentTimeMillis() < until) drawAFrame()
        assertTrue("said once the hero's picture is prepared", cover.toldWith == true)
    }

    /** Lets what Coil hands back to the main thread run, then draws the window as Android would. */
    private fun drawAFrame() {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(POLL_MS)
        compose.waitForIdle()
        compose.runOnUiThread { draw() }
    }

    /** Draws the window now, as Android would, laying it out first. */
    private fun draw() {
        val window = compose.activity.window.decorView
        window.draw(Canvas(Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)))
    }

    private companion object {
        /** The first title's backdrop on a screen 960 px wide, the one the hero shows first. */
        const val HERO_PICTURE = "https://img.test/backdrop/1?w=960"
        const val PICTURE_PX = 4
        const val FRAMES = 10
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 10L
    }
}
