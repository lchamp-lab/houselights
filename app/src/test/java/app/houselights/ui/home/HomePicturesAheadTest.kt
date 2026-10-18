package app.houselights.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.providers.local.ScanState
import app.houselights.ui.design.components.AwaitAhead
import app.houselights.ui.design.components.LoadingAhead
import app.houselights.ui.design.components.PicturesAhead
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import coil.size.pxOrElse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

/**
 * What the launch loads ahead is what home's first view asks Coil for once laid out: the same
 * pictures (keys) at the same sizes, so home finds each one in memory and shows it at once. The
 * hero's backdrop, its tiny copy and its logo, then the cards of the first row on the screen, and
 * no other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = TV)
class HomePicturesAheadTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Each picture asked of Coil: its key and its size, and where it came from. */
    private data class Asked(val key: MemoryCache.Key?, val size: Size, val from: DataSource?)

    private val asked = mutableListOf<Asked>()
    private val sizes = IdentityHashMap<ImageRequest, Size>()

    private val listener = object : EventListener {
        override fun resolveSizeEnd(request: ImageRequest, size: Size) = synchronized(asked) { sizes[request] = size }

        override fun onSuccess(request: ImageRequest, result: SuccessResult) = synchronized(asked) {
            asked +=Asked(result.memoryCacheKey, checkNotNull(sizes[request]), result.dataSource)
            Unit
        }
    }

    /** Every address answers at once with a picture of the size asked, as a server's decoded to it would be. */
    private val server = Fetcher.Factory<Uri> { _, options, _ ->
        Fetcher {
            val (width, height) = options.size.let { it.width.pxOrElse { PICTURE_PX } to it.height.pxOrElse { PICTURE_PX } }
            val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            DrawableResult(BitmapDrawable(options.context.resources, picture), isSampled = false, dataSource = DataSource.NETWORK)
        }
    }

    private val loader = ImageLoader.Builder(context).components { add(server) }.eventListener(listener).build()

    @After
    fun forgetTheLoader() = Coil.reset()

    private fun film(n: Int) = MediaItem(
        id = "film$n",
        kind = MediaKind.MOVIE,
        title = "Film $n",
        poster = ImageRef("poster$n") { "https://img.test/poster/$n?w=$it" },
        backdrop = ImageRef("backdrop$n") { "https://img.test/backdrop/$n?w=$it" },
        logo = ImageRef("logo$n") { "https://img.test/logo/$n?w=$it" },
        thumb = ImageRef("still$n") { "https://img.test/still/$n?w=$it" },
    )

    /** Loads ahead what the launch would, then lays home out: what each asked, in that order. */
    private fun loadAheadThenShow(sections: List<HomeSection>): Pair<List<Asked>, List<Asked>> {
        Coil.setImageLoader(loader)
        val ahead = firstPictures(PicturesAhead(context, context.resources.displayMetrics.widthPixels), sections)
        ahead.forEach(loader::enqueue)
        waitFor { asked.size >= ahead.size }
        val loaded = synchronized(asked) { asked.toList().also { asked.clear() } }
        assertEquals("loaded ahead", ahead.size, loaded.size)

        val home = HomeState(content = HomeContent.Rows(sections, firstScan = false))
        compose.setTvContent { HomeBands(home, MutableStateFlow(ScanState()), {}, { _, _ -> }, {}, {}, {}, {}) }
        waitFor { asked.map { it.key }.containsAll(loaded.map { it.key }) }
        compose.waitForIdle()
        return loaded to synchronized(asked) { asked.toList() }
    }

    /**
     * Waits a while at most for [condition], running what Coil hands back to the main thread from
     * its own: the pictures come from other threads, which the test's main looper does not wait for.
     */
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (!synchronized(asked, condition) && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun aRowOfPosters() {
        val sections = listOf(
            HomeSection(SectionType.LATEST, (1..12).map(::film), landscape = false, libraryId = "films"),
            HomeSection(SectionType.LATEST, (20..32).map(::film), landscape = false, libraryId = "shows"),
        )
        val (loaded, shown) = loadAheadThenShow(sections)
        assertTheSame(loaded, shown)
        // The hero's three, and the posters of the first row that start on the screen.
        assertEquals(3 + 8, loaded.size)
        assertEquals("the first row's posters home asked for", firstRow(shown, "poster/"), firstRow(loaded, "poster/"))
    }

    @Test
    fun aRowOfStills() {
        val sections = listOf(
            HomeSection(SectionType.CONTINUE, (1..9).map(::film), landscape = true),
            HomeSection(SectionType.LATEST, (20..32).map(::film), landscape = false, libraryId = "films"),
        )
        val (loaded, shown) = loadAheadThenShow(sections)
        assertTheSame(loaded, shown)
        assertEquals(3 + 4, loaded.size)
        assertEquals("the first row's stills home asked for", firstRow(shown, "still/"), firstRow(loaded, "still/"))
    }

    /**
     * Home composed under the launch screen while the launch still loads its pictures: each one home
     * asks for waits for the one on its way ([AwaitAhead]), then comes from memory; none is fetched twice.
     */
    @Test
    fun homeComposedWhileThePicturesLoadFetchesNoneTwice() {
        val sent = CompletableDeferred<Unit>()
        val fetched = mutableListOf<String>()
        // What is fetched, by address and size: a card's glow reads its own small copy of the poster's file.
        val slow = Fetcher.Factory<Uri> { data, options, _ ->
            Fetcher {
                synchronized(fetched) { fetched += "$data at ${options.size}" }
                sent.await()
                val (width, height) = options.size.let { it.width.pxOrElse { PICTURE_PX } to it.height.pxOrElse { PICTURE_PX } }
                val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                DrawableResult(BitmapDrawable(options.context.resources, picture), isSampled = false, dataSource = DataSource.NETWORK)
            }
        }
        val waiting = ImageLoader.Builder(context).components {
            add(slow)
            add(AwaitAhead)
        }.eventListener(listener).build()
        Coil.setImageLoader(waiting)
        val sections = listOf(HomeSection(SectionType.LATEST, (1..12).map(::film), landscape = false, libraryId = "films"))
        val ahead = firstPictures(PicturesAhead(context, context.resources.displayMetrics.widthPixels), sections)
        val launch = CoroutineScope(Dispatchers.Default)
        try {
            launch.launch { LoadingAhead.load(waiting, ahead) }
            waitFor { synchronized(fetched) { fetched.size } >= ahead.size }
            val home = HomeState(content = HomeContent.Rows(sections, firstScan = false))
            compose.setTvContent { HomeBands(home, MutableStateFlow(ScanState()), {}, { _, _ -> }, {}, {}, {}, {}) }
            // Home laid out: its cards and its hero have asked for their pictures, which are on their way.
            waitFor { sizes.size >= 2 * ahead.size }
            assertEquals("home waits for what is on its way", fetched.distinct(), synchronized(fetched) { fetched.toList() })

            sent.complete(Unit)
            waitFor { asked.count { it.from == DataSource.MEMORY_CACHE } >= ahead.size }
            compose.waitForIdle()
            assertEquals("each fetched once", fetched.distinct(), synchronized(fetched) { fetched.toList() })
            val fromMemory = synchronized(asked) { asked.filter { it.from == DataSource.MEMORY_CACHE }.map { it.key }.toSet() }
            val fromServer = synchronized(asked) { asked.filter { it.from == DataSource.NETWORK }.map { it.key }.toSet() }
            assertTrue("what was loaded ahead, home found in memory", fromMemory.size >= ahead.size)
            assertTrue("home asked for each one loaded ahead", fromServer.containsAll(fromMemory))
        } finally {
            launch.cancel()
        }
    }

    /** Each picture loaded ahead, home asked for under the same key at the same size, and found it in memory. */
    private fun assertTheSame(loaded: List<Asked>, shown: List<Asked>) {
        loaded.forEach { ahead ->
            val home = shown.filter { it.key == ahead.key }
            assertTrue("home never asked for $ahead, only for:\n${shown.joinToString("\n")}", home.isNotEmpty())
            home.forEach {
                assertEquals("${ahead.key}: the size home asks", ahead.size, it.size)
                assertEquals("${ahead.key}: found in memory", DataSource.MEMORY_CACHE, it.from)
            }
        }
    }

    /** The cards of the first row (films 1 to 12) among what was [asked], by their addresses. */
    private fun firstRow(asked: List<Asked>, kind: String): Set<String> = asked.mapNotNull { it.key?.key }
        .filter { it.contains(kind) && it.substringAfter(kind).substringBefore('?').toInt() < FIRST_OF_SECOND_ROW }.toSet()

    private companion object {
        const val PICTURE_PX = 4
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val FIRST_OF_SECOND_ROW = 20
    }
}
