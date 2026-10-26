package app.houselights.providers.local

import app.houselights.core.EpisodeOrderKind
import app.houselights.core.EpisodeOrderOption
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The orders a show's page offers ([LocalEditor.episodeOrders]): read from the library alone, as its scans listed them,
 * TMDB never asked, so the page opens TMDB out of reach; none for a show TMDB numbers one way; Automatique following its
 * NFO when it names one; a choice placing the episodes; a show matched before its groups were kept learning them at a
 * later pass, a few shows a pass, and again a month later.
 */
@RunWith(RobolectricTestRunner::class)
class LocalEpisodeOrdersTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var shows: SourceEntity

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        shows = bench.folder("Séries", SourceKind.SHOWS)
        bench.tmdb.show(FIREFLY, "Firefly", 2002)
        bench.tmdb.season(FIREFLY, 1, episodes = 14)
        bench.showsNas.put(PILOT)
    }

    @After
    fun close() = bench.close()

    private fun editor() = LocalEditor(bench.dao, bench.scanner) { bench.tmdb.client() }

    private suspend fun show(): MediaItem = MediaItem(bench.showOf(shows, PILOT).id.toString(), MediaKind.SERIES, "Firefly")

    private fun dvd() = bench.tmdb.group(FIREFLY, DVD, type = 3, name = "DVD Order", listOf(listOf(1 to 11, 1 to 1, 1 to 2)))

    @Test
    fun ordersAreReadFromTheLibraryAloneAutomaticFirst() = runTest {
        dvd()
        bench.scan()
        bench.tmdbOutOfReach()
        bench.tmdb.requests.clear()

        val orders = checkNotNull(editor().episodeOrders(show()))
        val aired = EpisodeOrderOption(EpisodeOrderEntity.AIRED, EpisodeOrderKind.AIRED, episodes = 14, seasons = 1)
        assertEquals(listOf(aired, EpisodeOrderOption(DVD, EpisodeOrderKind.DVD, "DVD Order", episodes = 3)), orders.options)
        assertEquals(null to aired, orders.chosen to orders.automatic)
        assertFalse(orders.fromNfo)
        assertEquals("TMDB never asked", emptyList<String>(), bench.tmdb.requests)
    }

    @Test
    fun aShowTmdbNumbersOneWayHasNoOrderToChoose() = runTest {
        bench.scan()
        assertNull(editor().episodeOrders(show()))
    }

    @Test
    fun anNfoNamingAnOrderIsAutomatics() = runTest {
        dvd()
        bench.showsNas.putText("Firefly/tvshow.nfo", "<tvshow><title>Firefly</title><displayorder>dvd</displayorder></tvshow>")
        bench.scan()

        val orders = checkNotNull(editor().episodeOrders(show()))
        assertEquals(DVD, orders.automatic.id)
        assertTrue(orders.fromNfo)
        assertNull("still automatic", orders.chosen)
    }

    @Test
    fun choosingAnOrderPlacesTheEpisodes() = runTest {
        dvd()
        bench.scan()
        editor().chooseEpisodeOrder(show(), DVD)

        assertEquals("Episode 1.11", bench.item(shows, PILOT).title)
        assertEquals(DVD, editor().episodeOrders(show())?.chosen)
    }

    /** A show matched before its groups were kept, as by 0.3, learns them at a later pass, its files unchanged. */
    @Test
    fun aShowMatchedBeforeItsGroupsWereKeptLearnsThemAtALaterPass() = runTest {
        dvd()
        bench.scan()
        bench.db.openHelper.writableDatabase.execSQL("DELETE FROM episode_orders")
        assertNull("nothing to offer meanwhile", editor().episodeOrders(show()))

        val pass = bench.pass()
        assertEquals(1, pass.requests.count { it == "tv/$FIREFLY/episode_groups" })
        assertEquals(listOf(EpisodeOrderEntity.AIRED, DVD), editor().episodeOrders(show())?.options?.map { it.id })
    }

    /** Groups to list are listed five shows a pass, those never listed first, and each again a month later. */
    @Test
    fun groupsAreListedAFewShowsAPassAndAgainAMonthLater() = runTest {
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf")
        val others = names.indices.map { OTHERS + it }
        names.forEachIndexed { i, name ->
            bench.tmdb.show(OTHERS + i, name, 2020)
            bench.tmdb.season(OTHERS + i, 1, episodes = 3)
            bench.showsNas.put("$name/Season 1/$name - S01E01.mkv")
        }
        bench.scan()
        bench.db.openHelper.writableDatabase.execSQL("DELETE FROM episode_orders")
        // The shows whose groups a pass lists.
        fun listed(pass: ScanBench.Pass) = pass.requests.filter { it.endsWith("/episode_groups") }.map { it.split('/')[1].toInt() }.sorted()

        assertEquals(others.take(5), listed(bench.pass()))
        assertEquals(others.drop(5) + FIREFLY, listed(bench.pass()))
        assertEquals("all listed", emptyList<Int>(), listed(bench.pass()))
        bench.later(MONTH_MS)
        assertEquals("a month later", others.take(5), listed(bench.pass()))
    }

    private companion object {
        const val FIREFLY = 1437

        /** The first of seven more shows, all before Firefly. */
        const val OTHERS = 101
        const val MONTH_MS = 30 * 24 * 60 * 60_000L
        const val DVD = "5b11ba820e0a265847002c6e"
        const val PILOT = "Firefly/Season 1/Firefly - S01E01.mkv"
    }
}
