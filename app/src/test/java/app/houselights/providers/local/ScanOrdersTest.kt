package app.houselights.providers.local

import app.houselights.core.TmdbNotFoundException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows whose files follow another order than TMDB's seasons (Firefly on DVD): named by their tvshow.nfo, or chosen.
 * The files keep their numbers; each shows the TMDB episode its order puts there, Trakt names it so, and what was
 * watched stays, row by row; back to automatic undoes it; an order TMDB cannot give changes nothing, and one it did not
 * give this pass is placed at the next; a group of another show is asked once a listing; kept by title, a chosen order
 * comes back with its show, TMDB closed meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
class ScanOrdersTest {
    /** The statements the library runs while [recording], with their values. */
    private val statements: MutableList<Pair<String, List<Any?>>> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var recording = false

    @get:Rule
    val scan = ScanBenchRule { sql, args -> if (recording) statements += sql to args.toList() }

    private val bench get() = scan.bench
    private lateinit var shows: SourceEntity

    private val tmdb get() = bench.tmdb
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        shows = bench.folder("Séries", SourceKind.SHOWS)
        tmdb.show(FIREFLY, "Firefly", 2002)
        tmdb.season(FIREFLY, 1, episodes = 14)
        // On DVD, Serenity (TMDB's 11th) first, then The Train Job and Bushwhacked.
        tmdb.group(FIREFLY, DVD, type = 3, name = "DVD Order", listOf(listOf(1 to 11, 1 to 1, 1 to 2)))
        (1..3).forEach { showsNas.put(file(it)) }
    }

    private fun file(n: Int) = "Firefly/Season 1/Firefly - S01E0$n.mkv"

    /** Firefly's tvshow.nfo as Jellyfin writes it, naming its [order]. */
    private fun nfo(order: String) =
        "<tvshow><title>Firefly</title><tmdbid>$FIREFLY</tmdbid><displayorder>$order</displayorder></tvshow>"

    private suspend fun titles() = (1..3).map { bench.item(shows, file(it)).title }

    private suspend fun show() = bench.showOf(shows, file(1))

    /** Trakt's names of the files, by the TMDB episodes they show. */
    private suspend fun traktNumbers() = bench.db.trakt().rows().sortedBy { it.id }.map { it.seasonNumber to it.episodeNumber }

    @Test
    fun fireflyInDvdOrderTakesItsTitlesFromTheGroupItsNfoNames() = runTest {
        showsNas.putText("Firefly/tvshow.nfo", nfo(order = "dvd"))
        val pass = bench.pass()

        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
        assertEquals(DVD, show().episodeOrder)
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), traktNumbers())
        assertEquals("the group asked once", 1, pass.requests.count { it == "tv/episode_group/$DVD" })
        assertEquals("its season named as the app names it", "", bench.dao.season(show().id, 1)?.title)
    }

    /** A Kodi tvshow.nfo links to the group itself; a show's own seasons keep their numbers whatever the group's. */
    @Test
    fun aKodiLinkToAGroupIsTheOrderTheFilesFollow() = runTest {
        val link = "https://www.themoviedb.org/tv/$FIREFLY-firefly/episode_group/$DVD"
        showsNas.putText("Firefly/tvshow.nfo", "<tvshow><title>Firefly</title><episodeguide>$link</episodeguide></tvshow>\n$link")
        bench.scan()

        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
        assertEquals((1..3).map { 1 to it }, (1..3).map { bench.item(shows, file(it)).let { e -> e.seasonNumber to e.episodeNumber } })
    }

    @Test
    fun choosingTheDvdOrderRetitlesEpisodesAndKeepsWhatWasWatched() = runTest {
        bench.scan()
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles())
        val ids = (1..3).map { bench.item(shows, file(it)).id }
        bench.dao.savePlayback(ids[0], positionMs = 0, durationMs = null, played = true, lastPlayedAt = 5)
        bench.dao.savePlayback(ids[1], positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 6)
        bench.dao.setFavorite(ids[2], true)

        bench.scanner.chooseOrder(show().id, DVD)

        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
        val rows = (1..3).map { bench.item(shows, file(it)) }
        assertEquals(ids, rows.map { it.id })
        assertEquals(listOf(true, false, false), rows.map { it.played })
        assertEquals(listOf(0L, 600_000L, 0L), rows.map { it.positionMs })
        assertEquals(listOf(false, false, true), rows.map { it.favorite })
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), traktNumbers())
        assertEquals("nothing sent to Trakt for it", emptyList<TraktPendingEntity>(), bench.db.trakt().pending())
        assertEquals(DVD to DVD, show().userEpisodeOrder to show().episodeOrder)
        assertNotNull("Trakt's marks from before left", show().reorderedAt)
    }

    @Test
    fun goingBackToAutomaticUndoesIt() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        bench.scanner.chooseOrder(show().id, null)

        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles())
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3), traktNumbers())
        assertEquals(null to null, show().userEpisodeOrder to show().episodeOrder)
        assertNull(bench.item(shows, file(1)).tmdbEpisode)
    }

    @Test
    fun anOrderTmdbCannotGiveChangesNothing() = runTest {
        tmdb.episodeGroups[FIREFLY] = tmdb.episodeGroups[FIREFLY].orEmpty() + TmdbEpisodeGroupRef(GONE, 3, "Gone", 14)
        bench.scan()
        val before = (1..3).map { bench.item(shows, file(it)) }

        val failure = try {
            bench.scanner.chooseOrder(show().id, GONE)
            null
        } catch (e: TmdbNotFoundException) {
            e
        }

        assertNotNull(failure)
        assertEquals(before, (1..3).map { bench.item(shows, file(it)) })
        assertNull(show().userEpisodeOrder)
    }

    /** The user's order outlives a refresh and a tvshow.nfo naming another; identified as another show, it goes. */
    @Test
    fun theUsersOrderOutlivesARefreshAndANewNfo() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        showsNas.putText("Firefly/tvshow.nfo", nfo(order = "aired"))
        bench.scan(refreshMetadata = true)

        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
        assertEquals(DVD, show().userEpisodeOrder)
    }

    @Test
    fun identifyingAnotherShowForgetsTheOrder() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        tmdb.show(OTHER, "Firefly", 2024)
        tmdb.season(OTHER, 1, episodes = 3)
        bench.scanner.identify(show().id, OTHER)

        assertEquals(null to null, show().userEpisodeOrder to show().episodeOrder)
        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles())
        assertTrue(bench.item(shows, file(1)).tmdbEpisode == null)
    }

    /** What [block] runs, recorded: each statement and its values. */
    private suspend fun recorded(block: suspend () -> Unit): List<Pair<String, List<Any?>>> {
        statements.clear()
        recording = true
        block()
        recording = false
        return statements.toList()
    }

    /** The ids of the rows written whole, every column, as a scan writes a row it read ([LibraryDao.saveScanned]). */
    private fun List<Pair<String, List<Any?>>>.writtenWhole(): List<Long> =
        filter { (sql, _) -> sql.startsWith(UPDATE_ITEMS) && "`kind` = ?" in sql }.mapNotNull { (_, args) -> args.first() as? Long }

    /**
     * Placed in another order, a show's episodes take their NFOs' titles first, their NFOs read in one query before the
     * writes, not one an episode in their transaction.
     */
    @Test
    fun aShowPlacedAnewReadsItsEpisodesNfosAtOnceBeforeItsWrites() = runTest {
        (1..3).forEach { showsNas.putText(file(it).replace(".mkv", ".nfo"), "<episodedetails><title>Firefly $it</title></episodedetails>") }
        bench.scan()

        val ran = recorded { bench.scanner.chooseOrder(show().id, DVD) }

        assertEquals(listOf("Firefly 1", "Firefly 2", "Firefly 3"), titles())
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), traktNumbers())
        val show = show().id
        val oneByOne = ran.filter { (sql, args) ->
            sql.startsWith("SELECT i.sourceId, i.nfoPath, n.nfo FROM items i") ||
                sql.startsWith("SELECT nfo FROM item_nfos WHERE itemId") && args.firstOrNull() != show
        }
        assertEquals("never one by one, but the show's own", emptyList<Pair<String, List<Any?>>>(), oneByOne)
        assertEquals("all at once", 1, ran.count { (sql, _) -> sql.startsWith("SELECT itemId AS id, nfo FROM item_nfos WHERE itemId IN") })
    }

    /**
     * Named anew, a show's episodes take the new show's details where they stand, each written alone with what it shows,
     * never read again nor written whole: what was watched stays.
     */
    @Test
    fun aShowNamedAnewWritesWhatItsEpisodesShowAlone() = runTest {
        bench.scan()
        val ids = (1..3).map { bench.item(shows, file(it)).id }
        bench.dao.savePlayback(ids[0], positionMs = 0, durationMs = null, played = true, lastPlayedAt = 5)
        tmdb.show(OTHER, "Firefly", 2024)
        tmdb.season(OTHER, 1, episodes = 3)

        val ran = recorded { bench.scanner.identify(show().id, OTHER) }

        assertEquals(listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles())
        assertEquals(OTHER, show().tmdbId)
        assertEquals(listOf(true, false, false), (1..3).map { bench.item(shows, file(it)).played })
        assertEquals("no episode written whole", emptyList<Long>(), ran.writtenWhole().filter { it in ids })
    }

    /** A group TMDB gives with another show's episodes, which a Kodi NFO links: refused, asked once, then after a refresh. */
    @Test
    fun aGroupOfAnotherShowIsAskedOnceThenOnlyOnceListedAgain() = runTest {
        tmdb.group(FIREFLY, ELSEWHERE, type = 3, name = "DVD", listOf(listOf(1 to 11, 1 to 1, 1 to 2)), of = OTHER)
        val link = "https://www.themoviedb.org/tv/$FIREFLY-firefly/episode_group/$ELSEWHERE"
        showsNas.putText("Firefly/tvshow.nfo", "<tvshow><title>Firefly</title><episodeguide>$link</episodeguide></tvshow>\n$link")
        bench.scan()
        showsNas.put(file(4))
        bench.scan()

        assertEquals("asked once", 1, tmdb.requests.count { it == "tv/episode_group/$ELSEWHERE" })
        assertEquals("never followed", listOf("Episode 1.1", "Episode 1.2", "Episode 1.3"), titles())
        assertNull(show().episodeOrder)
        bench.scan(refreshMetadata = true)
        assertEquals("asked again once listed again", 2, tmdb.requests.count { it == "tv/episode_group/$ELSEWHERE" })
    }

    /** TMDB failing once for the group a new tvshow.nfo names, the show is read again at the next pass and placed whole. */
    @Test
    fun anOrderTmdbDidNotGiveIsPlacedWholeAtTheNextPass() = runTest {
        bench.scan()
        showsNas.putText("Firefly/tvshow.nfo", nfo(order = "dvd"))
        val failed = AtomicBoolean(false)
        tmdb.onRequest = { path ->
            if (path == "tv/episode_group/$DVD" && failed.compareAndSet(false, true)) throw IOException("Stream reset")
        }
        bench.scan()
        bench.scan()

        assertTrue(failed.get())
        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
        assertEquals(DVD, show().episodeOrder)
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), traktNumbers())
    }

    /** Named anew by its tvshow.nfo, the show starts in TMDB's seasons: its old show's order goes, TMDB failing after. */
    @Test
    fun aShowNamedAnewStartsInTmdbsSeasonsThoughTmdbFailsAfter() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        tmdb.show(OTHER, "Firefly", 2024)
        tmdb.season(OTHER, 1, episodes = 3)
        tmdb.failingPath = "tv/$OTHER/episode_groups"
        showsNas.putText("Firefly/tvshow.nfo", "<tvshow><title>Firefly</title><tmdbid>$OTHER</tmdbid></tvshow>")
        bench.scan()

        assertEquals(OTHER, show().tmdbId)
        assertEquals(null to null, show().userEpisodeOrder to show().episodeOrder)
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3), traktNumbers())
    }

    /** A new file of a show in DVD order while TMDB cannot say where it goes shows no TMDB episode: Trakt is told none. */
    @Test
    fun aNewFileTmdbCannotPlaceInItsShowsOrderIsNotSentToTrakt() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        showsNas.put(file(4))
        tmdb.failingPath = "tv/$FIREFLY/episode_groups"
        bench.scan(refreshMetadata = true)

        assertNull(bench.item(shows, file(4)).tmdbEpisode)
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), traktNumbers())
    }

    /** Identified again as itself, a show in DVD order keeps its episodes where they are, TMDB failing to place them again. */
    @Test
    fun identifiedAgainAsItselfTheShowKeepsItsOrderShouldTmdbFailAfter() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        tmdb.failingPath = "tv/$FIREFLY/season"
        bench.scanner.identify(show().id, FIREFLY)

        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
        assertEquals(listOf(1 to 11, 1 to 1, 1 to 2), traktNumbers())
        assertEquals(DVD to DVD, show().userEpisodeOrder to show().episodeOrder)
    }

    /**
     * The show in DVD order removed, then back while TMDB is closed, takes its order back at once, kept by its title; TMDB
     * naming it at the next pass for the first time since, the order stays: a first match is no show named anew.
     */
    @Test
    fun aChosenOrderKeptByTitleOutlivesTheShowBackWhileTmdbIsClosed() = runTest {
        // Another show keeps the folder from emptying, which would leave the library as it is.
        showsNas.put(ANOTHER)
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        (1..3).forEach { showsNas.remove(file(it)) }
        bench.scan()

        (1..3).forEach { showsNas.put(file(it)) }
        bench.withTmdb = false
        bench.scan()
        assertEquals("born with it, TMDB not asked", DVD to null, show().userEpisodeOrder to show().tmdbId)
        bench.withTmdb = true
        bench.scan()

        assertEquals(FIREFLY, show().tmdbId)
        assertEquals(DVD to DVD, show().userEpisodeOrder to show().episodeOrder)
        assertEquals(listOf("Episode 1.11", "Episode 1.1", "Episode 1.2"), titles())
    }

    /** TMDB listing the group with one more episode a month later, its places are fetched again when next needed. */
    @Test
    fun aGroupListedWithMoreEpisodesIsFetchedAgain() = runTest {
        bench.scan()
        bench.scanner.chooseOrder(show().id, DVD)
        tmdb.group(FIREFLY, DVD, type = 3, name = "DVD Order", listOf(listOf(1 to 11, 1 to 1, 1 to 2, 1 to 3)))
        bench.later(MONTH_MS)
        bench.scan()
        showsNas.put(file(4))
        val pass = bench.pass()

        assertEquals("fetched again", 1, pass.requests.count { it == "tv/episode_group/$DVD" })
        assertEquals("Episode 1.3", bench.item(shows, file(4)).title)
    }

    private companion object {
        const val FIREFLY = 1437
        const val OTHER = 99999
        const val DVD = "5b11ba820e0a265847002c6e"
        const val GONE = "5b11ba820e0a265847002c6f"
        const val ELSEWHERE = "5b11ba820e0a265847002c70"

        /** An episode of a show TMDB does not know, beside Firefly. */
        const val ANOTHER = "Dollhouse/Season 1/Dollhouse - S01E01.mkv"

        /** How long a show's groups are listed before they are listed again. */
        const val MONTH_MS = 30 * 24 * 60 * 60_000L

        /** How Room begins an update of rows of items, whole or of some columns. */
        const val UPDATE_ITEMS = "UPDATE OR ABORT `items`"
    }
}
