package app.houselights.ui.home

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.houselights.IMAGE_MEMORY_BYTES
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.core.UserState
import app.houselights.ui.design.components.AwaitAhead
import app.houselights.ui.design.components.LoadingAhead
import app.houselights.ui.design.components.PicturesAhead
import app.houselights.ui.design.components.forgetCutPosters
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pictureServer
import app.houselights.ui.testing.setTvContent
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.IdentityHashMap

/** What the launch loads ahead is exactly what home's grid asks Coil for, by key and size. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
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
            asked += Asked(result.memoryCacheKey, checkNotNull(sizes[request]), result.dataSource)
            Unit
        }
    }

    /** The app's memory budget: Robolectric's default, a share of a 16 MB heap, holds less than home's first view. */
    private fun memory() = MemoryCache.Builder(context).maxSizeBytes(IMAGE_MEMORY_BYTES).build()

    /** Every address answers at once, on the thread that asks: each load is over when it returns. */
    private val loader = ImageLoader.Builder(context).dispatcher(Dispatchers.Unconfined).components { add(pictureServer()) }
        .memoryCache(::memory).eventListener(listener).build()

    /** A count cut by an earlier test is drawn at once, never asked of Coil: each test starts as the launch does. */
    @Before
    fun forgetTheCuts() = forgetCutPosters()

    @After
    fun forgetTheLoader() = Coil.reset()

    private fun art(kind: String, id: String) = ImageRef("$kind$id") { "https://img.test/$kind/$id?w=$it" }

    private fun titled(id: String, kind: MediaKind = MediaKind.MOVIE, positionMs: Long = 0) = MediaItem(
        id = id,
        kind = kind,
        title = id,
        poster = art("poster", id),
        backdrop = art("backdrop", id),
        logo = art("logo", id),
        user = UserState(positionMs = positionMs),
        seasonNumber = 1.takeIf { kind == MediaKind.EPISODE },
        episodeNumber = 4.takeIf { kind == MediaKind.EPISODE },
    )

    /** A home with a film in progress, a next episode, the latest films, and two libraries whose counts are cut. */
    private val rows = HomeRows(
        listOf(films, shows),
        listOf(
            HomeSection(SectionType.CONTINUE, listOf(titled("heat", positionMs = 1_000)), landscape = true),
            HomeSection(SectionType.NEXT_UP, listOf(titled("mobland", MediaKind.EPISODE)), landscape = true),
            HomeSection(SectionType.LATEST, (1..12).map { titled("film$it") }, landscape = false, libraryId = films.id),
        ),
        HomeDoors(
            shelves = listOf(Shelf(films, 412, (1..8).map { titled("best$it") }), Shelf(shows, 38, (1..4).map { titled("show$it") })),
        ),
    )

    private val state = HomeState(rows.libraries, HomeContent.Rows(rows.sections, firstScan = false), rows.doors)

    /** Loads ahead what the launch would, then lays home out: what each asked, in that order. */
    private fun loadAheadThenShow(): Pair<List<Asked>, List<Asked>> {
        Coil.setImageLoader(loader)
        val ahead = firstPictures(PicturesAhead(context, context.resources.displayMetrics.widthPixels), rows)
        ahead.forEach(loader::enqueue)
        val loaded = asked.toList().also { asked.clear() }
        assertEquals("loaded ahead", ahead.size, loaded.size)

        compose.setTvContent { HomeGrid(state) }
        compose.waitForIdle()
        return loaded to asked.toList()
    }

    /**
     * Waits a while for [condition], running what Coil posts to the main thread, unseen by the test looper:
     * the launch loads ahead on threads of its own, as it does in the app.
     */
    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (!synchronized(asked, condition) && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun theLaunchLoadsWhatTheGridShows() {
        val (loaded, shown) = loadAheadThenShow()
        assertTheSame(loaded, shown)
        val keys = loaded.mapNotNull { it.key?.key }
        listOf("backdrop/heat", "logo/heat", "backdrop/mobland", "logo/mobland").forEach { picture ->
            assertTrue("$picture loaded ahead: $keys", keys.any { picture in it })
        }
        val cut = loaded.filter { it.key?.extras?.containsKey("houselights.cut") == true }
        assertEquals("a poster per digit of 412 and of 38, read for its pixels", 5, cut.size)
        assertTrue("the latest titles' band", keys.any { "poster/film1?" in it })
    }

    /** Each picture home asks for while the launch is loading it waits for that load ([AwaitAhead]). */
    @Test
    fun homeComposedWhileThePicturesLoadFetchesNoneTwice() {
        val sent = CompletableDeferred<Unit>()
        val fetched = mutableListOf<String>()
        // What is fetched, by address and size: a card's glow reads its own small copy of the poster's file.
        val slow = pictureServer { data, size ->
            synchronized(fetched) { fetched += "$data at $size" }
            sent.await()
        }
        val waiting = ImageLoader.Builder(context).components {
            add(slow)
            add(AwaitAhead)
        }.memoryCache(::memory).eventListener(listener).build()
        Coil.setImageLoader(waiting)
        val ahead = firstPictures(PicturesAhead(context, context.resources.displayMetrics.widthPixels), rows)
        val launch = CoroutineScope(Dispatchers.Default)
        try {
            launch.launch { LoadingAhead.load(waiting, ahead) }
            waitFor { synchronized(fetched) { fetched.size } >= ahead.size }
            compose.setTvContent { HomeGrid(state) }
            // Home laid out: its case and its doors have asked for their pictures, which are on their way.
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

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
