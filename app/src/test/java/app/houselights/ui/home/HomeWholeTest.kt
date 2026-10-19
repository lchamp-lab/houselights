package app.houselights.ui.home

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
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.LaunchScreen
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.drawWindow
import app.houselights.ui.testing.pictureServer
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home is whole once a frame shows its rows, its hero on the first title, and that title's picture prepared. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeWholeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The launch screen over home, and the backdrop's picture prepared when it was first told. */
    private inner class Cover : LaunchScreen {
        override val covers = true
        var toldWith: Boolean? = null

        override fun drawn() {
            if (toldWith == null) toldWith = HERO_PICTURE in loaded
        }
    }

    private val cover = Cover()

    /** What came back from the server, by address. */
    private val loaded = mutableListOf<String>()

    /** The hero's picture, held until the test sends it. */
    private val backdropSent = CompletableDeferred<Unit>()

    /** Pictures load on the thread that asks: on the main thread, between frames. */
    private val loader by lazy {
        ImageLoader.Builder(compose.activity)
            .dispatcher(Dispatchers.Unconfined)
            .components { add(pictureServer { data, _ -> if (data.toString() == HERO_PICTURE) backdropSent.await() }) }
            .eventListener(
                object : EventListener {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        loaded += request.data.toString()
                    }
                },
            )
            .build()
    }

    private val rows = HomeContent.Rows(
        listOf(HomeSection(SectionType.LATEST, (1..8).map(::film), landscape = false, libraryId = "films")),
        firstScan = false,
    )

    @After
    fun forgetTheLoader() = Coil.reset()

    private fun film(n: Int) = MediaItem(
        id = "film$n",
        kind = MediaKind.MOVIE,
        title = "Film $n",
        poster = ImageRef("poster$n") { "https://img.test/poster/$n?w=$it" },
        backdrop = ImageRef("backdrop$n") { "https://img.test/backdrop/$n?w=$it" },
    )

    /** Home showing [home] under the launch screen, a few frames drawn. */
    private fun show(home: () -> HomeState) {
        Coil.setImageLoader(loader)
        compose.setContent {
            CompositionLocalProvider(LocalLaunchScreen provides cover) {
                HouselightsTheme { HomeBands(home()) }
            }
        }
        repeat(FRAMES) { drawAFrame() }
    }

    /** Sends the hero's picture, then draws until the launch screen is told. */
    private fun sendTheHerosPicture() {
        backdropSent.complete(Unit)
        repeat(FRAMES) { if (cover.toldWith == null) drawAFrame() }
    }

    @Test
    fun homeIsWholeOnceItsHerosPictureIsPrepared() {
        show { HomeState(content = rows) }
        assertEquals("the rows are in, the hero's picture is not: nothing said", null, cover.toldWith)

        sendTheHerosPicture()
        assertTrue("said once its picture is prepared, and not before", cover.toldWith == true)
    }

    /** The frame the rows first show in has no hero title yet (it settles a frame later), so it is not whole. */
    @Test
    fun theFrameTheRowsComeInIsNotWholeYet() {
        var home by mutableStateOf(HomeState(content = HomeContent.Loading(firstScan = false)))
        show { home }
        assertEquals("still loading: nothing said", null, cover.toldWith)

        compose.mainClock.autoAdvance = false
        home = HomeState(content = rows)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.drawWindow()
        assertEquals("the rows, the hero on no title yet: nothing said", null, cover.toldWith)
        compose.mainClock.autoAdvance = true
        sendTheHerosPicture()
        assertTrue("said once the hero's picture is prepared", cover.toldWith == true)
    }

    private fun drawAFrame() {
        compose.waitForIdle()
        compose.drawWindow()
    }

    private companion object {
        /** The first title's backdrop on a screen 960 px wide, the one the hero shows first. */
        const val HERO_PICTURE = "https://img.test/backdrop/1?w=960"
        const val FRAMES = 10
    }
}
