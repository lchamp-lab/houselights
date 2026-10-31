package app.houselights.ui.home

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.houselights.IMAGE_MEMORY_BYTES
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.core.UserState
import app.houselights.settings.TextSize
import app.houselights.ui.design.components.AwaitAhead
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.LoadingAhead
import app.houselights.ui.design.components.PicturesAhead
import app.houselights.ui.design.components.doorWidth
import app.houselights.ui.design.textScaleOf
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.IdentityHashMap
import kotlin.math.roundToInt

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

    private val state = HomeState(rows.libraries, HomeContent.Rows(rows.sections), rows.doors)

    /** The same home with sagas, their door showing their posters. */
    private val withSagas = rows.copy(
        libraries = rows.libraries + sagaLibrary,
        doors = checkNotNull(rows.doors).copy(sagas = Shelf(sagaLibrary, 9, (1..6).map { titled("saga$it") })),
    )

    /** The launch's preloads on this screen, its text at [step]. */
    private fun ahead(step: Float = 1f) = PicturesAhead(context, context.resources.displayMetrics.widthPixels, textScale(step))

    /** The text size home opens at, as the launch reads it: the system's times [step]. */
    private fun textScale(step: Float) = textScaleOf(context.resources.configuration.fontScale, step)

    /**
     * Loads ahead what the launch does ([loadFirstPictures]) for [rows], then lays home out, searching if [canSearch],
     * its text at [step]: what each asked, in that order.
     */
    private fun loadAheadThenShow(rows: HomeRows = this.rows, canSearch: Boolean = true, step: Float = 1f): Pair<List<Asked>, List<Asked>> {
        Coil.setImageLoader(loader)
        val width = context.resources.displayMetrics.widthPixels
        runBlocking { loadFirstPictures(context, width, { textScale(step) }, CutPosterCache()) { canSearch }(rows) }
        // A count's posters are asked twice, loaded ahead then cut ahead from memory.
        val loaded = asked.distinctBy { it.key }.also { asked.clear() }
        assertEquals("loaded ahead: home's first view", firstPictures(ahead(step), rows, canSearch).size, loaded.size)

        val home = HomeState(rows.libraries, HomeContent.Rows(rows.sections), rows.doors)
        compose.setTvContent(step) { HomeGrid(home, HomeActions(onOpenSearch = {}.takeIf { canSearch })) }
        compose.waitForIdle()
        return loaded to asked.toList()
    }

    /**
     * Waits for [condition], running what Coil posts to the main thread, unseen by the test looper: the launch loads
     * ahead on threads of its own, as it does in the app. Fails, saying [what] never happened, after [TIMEOUT_MS].
     */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + TIMEOUT_MS
        while (!synchronized(asked, condition)) {
            if (System.currentTimeMillis() > until) fail("Not after $TIMEOUT_MS ms: $what")
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

    /** With sagas, their door's posters are loaded ahead as it shows them, beside Rechercher's wall. */
    @Test
    fun theLaunchLoadsTheSagasDoor() {
        val (loaded, shown) = loadAheadThenShow(withSagas)
        assertTheSame(loaded, shown)
        val keys = loaded.mapNotNull { it.key?.key }
        assertTrue("the sagas' posters: $keys", keys.any { "poster/saga1?" in it })
    }

    /** Without search, Rechercher's wall is neither shown nor loaded ahead; the sagas take its place. */
    @Test
    fun withoutSearchTheLaunchLoadsNoWall() {
        val searching = firstPictures(ahead(), withSagas).size
        val (loaded, shown) = loadAheadThenShow(withSagas, canSearch = false)
        assertTheSame(loaded, shown)
        assertTrue("fewer pictures without Rechercher's wall: ${loaded.size} of $searching", loaded.size < searching)
        assertTrue("the sagas' posters", loaded.mapNotNull { it.key?.key }.any { "poster/saga1?" in it })
    }

    /**
     * Without Nouveautés, the next episode's door takes the whole row: its still is asked at that width, ahead as on
     * screen, never at half the row's to be blown up twice over.
     */
    @Test
    fun aStillAloneOnItsRowIsAskedAtTheRowsWidth() {
        val alone = rows.copy(sections = rows.sections.filter { it.type != SectionType.LATEST })
        val (loaded, shown) = loadAheadThenShow(alone)
        assertTheSame(loaded, shown)
        // On the TV canvas at mdpi, a dp is a pixel.
        val wide = doorWidth(4).value.roundToInt()
        val stills = loaded.mapNotNull { it.key?.key }.filter { "backdrop/mobland" in it }
        assertEquals(listOf("https://img.test/backdrop/mobland?w=$wide"), stills)
    }

    /** At the player's density, the case asks for TMDB's largest picture short of the original, ahead as on screen. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun theCaseAsksForAPictureOf1280AtMost() {
        val (loaded, shown) = loadAheadThenShow()
        assertTheSame(loaded, shown)
        assertEquals(listOf("https://img.test/backdrop/heat?w=1280"), loaded.mapNotNull { it.key?.key }.filter { "backdrop/heat" in it })
    }

    /**
     * At Compacte, where the doors' band is shorter, their pictures taller and their counts larger, home finds each
     * picture loaded ahead, its counts cut, its sagas' and its wall's posters at their size.
     */
    @Test
    fun atCompacteTheLaunchLoadsWhatTheGridShows() {
        val (loaded, shown) = loadAheadThenShow(withSagas, step = TextSize.COMPACT.step)
        assertTheSame(loaded, shown)
        val keys = loaded.mapNotNull { it.key?.key }
        assertEquals("cut", 5, loaded.count { it.key?.extras?.containsKey("houselights.cut") == true })
        listOf("logo/heat", "backdrop/mobland", "poster/film1?", "poster/saga1?").forEach { picture ->
            assertTrue("$picture loaded ahead: $keys", keys.any { picture in it })
        }
    }

    /** At 4K, where a logo may be enlarged past its file's pixels, home still finds each picture loaded ahead. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xxxhdpi")
    fun at4kTheLaunchLoadsWhatTheGridShows() {
        val (loaded, shown) = loadAheadThenShow()
        assertTheSame(loaded, shown)
        assertTrue("the case's logo", loaded.mapNotNull { it.key?.key }.any { "logo/heat" in it })
    }

    /** The launch loads what the rows show at once, the case once what it proposes is drawn, the doors' once they are read. */
    @Test
    fun theLaunchLoadsEachPictureOnceWhatShowsItIsRead() = runBlocking {
        Coil.setImageLoader(loader)
        val first = CompletableDeferred<HomeDoors>()
        val whole = CompletableDeferred<HomeDoors>()
        val proposed = titled("alien")
        val sections = rows.sections.filter { it.type != SectionType.CONTINUE }
        val launched = HomeRows(rows.libraries, sections, doorsAhead = DoorsRead(first, whole))
        val pictures = loadFirstPictures(context, context.resources.displayMetrics.widthPixels, { textScale(1f) }, CutPosterCache())
        val loading = launch(Dispatchers.Unconfined) { pictures(launched) }
        fun keys() = synchronized(asked) { asked.mapNotNull { it.key?.key } }
        fun cut() = synchronized(asked) { asked.mapNotNull { it.key }.filter { it.extras.containsKey("houselights.cut") }.distinct() }
        assertTrue("the next episode's still at once: ${keys()}", keys().any { "backdrop/mobland" in it })
        assertTrue("the latest titles' band at once", keys().any { "poster/film1?" in it })
        assertTrue("the case to come", keys().none { "alien" in it })

        first.complete(HomeDoors(proposed, shelvesRead = false))
        assertTrue("the case once drawn: ${keys()}", keys().any { "backdrop/alien" in it })
        assertTrue("the counts to come", cut().isEmpty())

        whole.complete(checkNotNull(rows.doors).copy(proposed = proposed))
        loading.join()
        assertEquals("a poster per digit of 412 and of 38, once the doors are read", 5, cut().size)
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
        val ahead = firstPictures(ahead(), rows)
        val launch = CoroutineScope(Dispatchers.Default)
        try {
            launch.launch { LoadingAhead.load(waiting, ahead) }
            waitFor("every picture ahead fetched") { synchronized(fetched) { fetched.size } >= ahead.size }
            compose.setTvContent { HomeGrid(state) }
            // Home laid out: its case and its doors have asked for their pictures, which are on their way.
            waitFor("home asking for each picture ahead") { sizes.size >= 2 * ahead.size }
            assertEquals("home waits for what is on its way", fetched.distinct(), synchronized(fetched) { fetched.toList() })

            sent.complete(Unit)
            waitFor("home finding each picture ahead in memory") { asked.count { it.from == DataSource.MEMORY_CACHE } >= ahead.size }
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
