package app.houselights.ui.design.components

import android.app.Application
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ImageRef
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.formatCount
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Layout: home's first scan's wall fills first found first, a batch a step; full, it renews in place, one
 * frame a step, the latest title found into the oldest frame, the poster it showed kept whole until the one coming is
 * decoded; it reads as how many titles were found.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class FirstWallTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun forgetTheLoader() = Coil.reset()

    private fun title(n: Int, poster: Boolean = true) =
        FirstTitle(n.toLong(), "Film $n", YEAR + n, ImageRef("first-$n") { "https://img.test/first-$n?w=$it" }.takeIf { poster })

    /** The titles home reads once [count] are found, as `LocalLibrary.firstTitles` gives them: the first found, then the latest. */
    private fun found(count: Int, latest: FirstTitle = title(count)): List<FirstTitle> =
        (1..minOf(count, FRAMES)).map { title(it) } + listOf(latest).filter { count > FRAMES }

    private fun FirstWallState.ids() = shown.map { it?.id }

    /** The frames' titles coming into them, decoded: each takes its frame, the animation run through at once. */
    private suspend fun FirstWallState.decoded() = withContext(Frames) {
        frames.forEach { frame -> frame.coming?.let { frame.ready(it) } }
    }

    /** A frame clock that runs an animation through at once, frame after frame, outside a composition. */
    private object Frames : MonotonicFrameClock {
        private var now = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            now += FRAME_NANOS
            return onFrame(now)
        }
    }

    @Test
    fun itFillsFirstFoundFirstABatchAStep() {
        val wall = FirstWallState()
        wall.step(found(3))
        assertEquals(listOf(1L, 2L, 3L) + List(5) { null }, wall.ids())
        wall.step(found(5))
        assertEquals((1L..5L).toList() + List(3) { null }, wall.ids())
    }

    @Test
    fun theStepThatFillsTheLastFramesRenewsNone() {
        val wall = FirstWallState()
        wall.step(found(6))
        wall.step(found(11))
        assertEquals((1L..8L).toList(), wall.ids())
        assertEquals(List(FRAMES) { null }, wall.coming)
        assertTrue("the next step takes the latest", wall.due(found(11)))
    }

    /** Full, each title it takes comes into the frame of the oldest: the ninth into the first, the eleventh into the third. */
    @Test
    fun fullItRenewsTheOldestFrameRoundAfterRound() = runTest {
        val wall = FirstWallState()
        wall.step(found(FRAMES))
        (9..11).forEach { n ->
            wall.step(found(n))
            wall.decoded()
        }
        assertEquals(listOf(9L, 10L, 11L, 4L, 5L, 6L, 7L, 8L), wall.ids())
        (12..16).forEach { n ->
            wall.step(found(n))
            wall.decoded()
        }
        assertEquals("a whole round, then the first frame again", (9L..16L).toList(), wall.ids())
        wall.step(found(17))
        assertEquals(17L, wall.coming.first()?.id)
    }

    /** A step takes the latest title found: those found between two steps do not pass on the wall. */
    @Test
    fun aStepTakesTheLatestTitleFound() {
        val wall = FirstWallState()
        wall.step(found(FRAMES))
        wall.step(found(12))
        assertEquals(listOf(12L) + List(FRAMES - 1) { null }, wall.coming.map { it?.id })
    }

    /** No step begins while the title coming waits for its picture, nor takes a title already on the wall. */
    @Test
    fun noStepBeginsWhileATitleWaitsForItsPicture() = runTest {
        val wall = FirstWallState()
        wall.step(found(FRAMES))
        wall.step(found(9))
        assertFalse("waiting for its picture", wall.due(found(10)))
        wall.step(found(10))
        assertEquals(listOf(9L) + List(FRAMES - 1) { null }, wall.coming.map { it?.id })
        wall.decoded()
        assertFalse("on the wall already", wall.due(found(9)))
        assertTrue(wall.due(found(10)))
    }

    /** A frame keeps its poster whole until the one coming is decoded at its size, then the ground lifts off the new one. */
    @Test
    fun aFrameKeepsItsPosterUntilTheOneComingIsDecoded() {
        val held = CompletableDeferred<Unit>()
        val server = pictureServer { data, _ -> if ("first-9?" in data.toString()) held.await() }
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(server) }.build())
        val wall = FirstWallState()
        var titles by mutableStateOf(found(FRAMES))
        compose.setTvContent { FirstWall(titles, titles.size, state = wall) }
        compose.waitUntil(TIMEOUT_MS) { wall.frames.all { it.lift.value == 1f } }

        titles = found(9)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FirstScanDefaults.STEP_MS + FRAME_MS)
        compose.waitForIdle()
        assertEquals("coming, unseen", 9L, wall.coming.first()?.id)
        assertEquals("the oldest stays", 1L, wall.shown.first()?.id)
        assertEquals("whole", 1f, wall.frames.first().lift.value)

        held.complete(Unit)
        compose.waitUntil(TIMEOUT_MS) { wall.shown.first()?.id == 9L }
        assertNull(wall.coming.first())
        compose.waitUntil(TIMEOUT_MS) { wall.frames.first().lift.value == 1f }
    }

    /** A title without a poster comes in as its one-sheet, never leaving its frame empty. */
    @Test
    fun aTitleWithoutPosterComesAsItsOneSheet() {
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(pictureServer()) }.build())
        val wall = FirstWallState()
        var titles by mutableStateOf(found(FRAMES))
        compose.setTvContent { FirstWall(titles, titles.size, state = wall) }
        compose.waitUntil(TIMEOUT_MS) { wall.frames.all { it.lift.value == 1f } }
        titles = found(9, latest = title(9, poster = false))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FirstScanDefaults.STEP_MS + FRAME_MS)
        compose.waitUntil(TIMEOUT_MS) { wall.shown.first()?.id == 9L }
    }

    /** The wall reads as how many titles were found, its thousands grouped, never as its posters. */
    @Test
    fun itReadsAsHowManyTitlesWereFound() {
        Coil.setImageLoader(ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(pictureServer()) }.build())
        compose.setTvContent { FirstWall(found(FRAMES), found = THOUSANDS) }
        compose.onNodeWithContentDescription("${formatCount(THOUSANDS.toLong())} titles").assertExists()
        compose.onAllNodesWithContentDescription("Film 1", useUnmergedTree = true).assertCountEquals(0)
    }

    private companion object {
        const val FRAMES = FirstScanDefaults.FRAMES
        const val YEAR = 2000
        const val THOUSANDS = 1_240
        const val FRAME_MS = 16L
        const val FRAME_NANOS = 16_000_000L
        const val TIMEOUT_MS = 5_000L
    }
}
