package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anime numbered from their show's start, as a scan places them: in TMDB's seasons, by the show's absolute group when it
 * has one, else by its seasons' counts; one TMDB does not list yet waits in season 1 and moves once it does, a day later
 * at most, its row and what was watched of it kept, ten shows a pass the longest waiting first, a number TMDB never
 * counts holding back none it does; all move when TMDB counts the seasons otherwise, those waiting alone when only the
 * last season grew; copies of one episode move together; TMDB asked once for what it needs.
 */
@RunWith(RobolectricTestRunner::class)
class ScanAbsoluteTest {
    @get:Rule
    val scan = ScanBenchRule()

    private val bench get() = scan.bench
    private lateinit var shows: SourceEntity
    private val tmdb get() = bench.tmdb
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        shows = bench.folder("Séries", SourceKind.SHOWS)
    }

    /** One Piece as TMDB numbers it: 20 seasons of 891 episodes, then season 21 of [last] episodes, specials apart. */
    private fun onePiece(last: Int = SEASON_21) {
        tmdb.show(ONE_PIECE, "One Piece", 1999)
        tmdb.season(ONE_PIECE, 0, episodes = 30)
        (1..19).forEach { tmdb.season(ONE_PIECE, it, episodes = 45) }
        tmdb.season(ONE_PIECE, 20, episodes = 36)
        tmdb.season(ONE_PIECE, 21, episodes = last)
    }

    private fun anime(n: Int) = "One Piece/[SubsPlease] One Piece - $n (1080p) [ABCD1234].mkv"

    private suspend fun row(path: String) = bench.item(shows, path)

    private fun ItemEntity.numbers() = listOf(seasonNumber, episodeNumber, absoluteNumber, tmdbSeason, tmdbEpisode)

    @Test
    fun onePieceByItsAbsoluteNumbersLandsInTmdbsSeasons() = runTest {
        onePiece()
        showsNas.put(anime(1071))
        showsNas.put(anime(1072))
        val pass = bench.pass()

        val peak = row(anime(1071))
        assertEquals(listOf(21, 180, 1071, 21, 180), peak.numbers())
        assertEquals("Episode 21.180", peak.title)
        assertEquals(listOf(21, 181, 1072, 21, 181), row(anime(1072)).numbers())
        assertEquals("its groups listed once", 1, pass.requests.count { it == "tv/$ONE_PIECE/episode_groups" })
        assertEquals("its season asked once", 1, pass.requests.count { it == "tv/$ONE_PIECE/season/21" })
        assertEquals("never its season 1", 0, pass.requests.count { it == "tv/$ONE_PIECE/season/1" })
        bench.assertQuiet()
    }

    @Test
    fun anAbsoluteGroupPlacesEpisodesItsWay() = runTest {
        tmdb.show(NARUTO, "Naruto", 2002)
        tmdb.season(NARUTO, 1, episodes = 3)
        tmdb.season(NARUTO, 2, episodes = 3)
        tmdb.group(NARUTO, ABSOLUTE_GROUP, type = 2, name = "Absolute", listOf(listOf(1 to 1, 1 to 2, 2 to 1, 1 to 3, 2 to 2, 2 to 3)))
        val path = "Naruto/Naruto - 004.mkv"
        showsNas.put(path)
        bench.scan()

        assertEquals("the group's fourth, not the counts'", listOf(1, 3, 4, 1, 3), row(path).numbers())
        assertEquals("Episode 1.3", row(path).title)
    }

    @Test
    fun filesNamedS01E1071AreReadFromTheShowsStart() = runTest {
        onePiece()
        val path = "One Piece/Season 1/One Piece - S01E1071 - Le sommet.mkv"
        showsNas.put(path)
        bench.scan()

        assertEquals(listOf(21, 180, 1071, 21, 180), row(path).numbers())
        assertEquals("Episode 21.180", row(path).title)
    }

    /** TMDB out of reach: the file gets a row in season 1, watched there; once TMDB answers, the same row is placed. */
    @Test
    fun anAnimeFileTmdbCannotPlaceYetGetsARowThenItsPlace() = runTest {
        onePiece()
        showsNas.put(anime(1071))
        bench.tmdbOutOfReach()
        bench.scan()
        val waiting = row(anime(1071))
        assertEquals(listOf(1, 1071, 1071, null, null), waiting.numbers())
        assertNull("asked again at the next pass", waiting.matchedAt)
        bench.dao.savePlayback(waiting.id, positionMs = 600_000, durationMs = null, played = false, lastPlayedAt = 5)

        bench.tmdbBack()
        bench.scan()
        val placed = row(anime(1071))
        assertEquals(waiting.id, placed.id)
        assertEquals(listOf(21, 180, 1071, 21, 180), placed.numbers())
        assertEquals(600_000L to 5L, placed.positionMs to placed.lastPlayedAt)
        assertEquals("Episode 21.180", placed.title)
    }

    /** Listed by TMDB a day after the file came, the episode moves from season 1 to its place, its row and state kept. */
    @Test
    fun anEpisodeTmdbListsADayLaterMovesThere() = runTest {
        onePiece(last = TO_1070)
        showsNas.put(anime(1071))
        bench.scan()
        val waiting = row(anime(1071))
        assertEquals("matched, waiting for TMDB to list it", listOf(1, 1071, 1071, null, null), waiting.numbers())
        bench.dao.savePlayback(waiting.id, positionMs = 0, durationMs = null, played = true, lastPlayedAt = 5)

        tmdb.season(ONE_PIECE, 21, episodes = TO_1070 + 1)
        bench.later(DAY_MS - 1)
        assertTrue("asked once a day", bench.pass().requests.none { it == "tv/$ONE_PIECE" })
        assertEquals(listOf(1, 1071, 1071, null, null), row(anime(1071)).numbers())
        bench.later(1)
        bench.scan()
        val placed = row(anime(1071))
        assertEquals(listOf(waiting.id, true), listOf(placed.id, placed.played))
        assertEquals(listOf(21, 180, 1071, 21, 180), placed.numbers())
        assertEquals("Episode 21.180", placed.title)
        assertNull("its season 1 gone with it", bench.dao.season(checkNotNull(placed.seriesId), 1))
    }

    /** A clock set back never holds an episode waiting: TMDB is asked again. */
    @Test
    fun anEpisodeWaitingIsAskedAgainWithTheClockSetBack() = runTest {
        onePiece(last = TO_1070)
        showsNas.put(anime(1071))
        bench.scan()
        tmdb.season(ONE_PIECE, 21, episodes = TO_1070 + 1)
        bench.now -= DAY_MS
        bench.scan()

        assertEquals(listOf(21, 180, 1071, 21, 180), row(anime(1071)).numbers())
    }

    /** A show matched before its counts were kept asks TMDB for them once, for all its episodes. */
    @Test
    fun aShowMatchedBeforeItsCountsWereKeptAsksThemOnce() = runTest {
        onePiece()
        showsNas.put(anime(1070))
        bench.scan()
        bench.db.openHelper.writableDatabase.execSQL("DELETE FROM episode_orders")
        showsNas.put(anime(1071))
        showsNas.put(anime(1072))
        val pass = bench.pass()

        assertEquals(1, pass.requests.count { it == "tv/$ONE_PIECE" })
        assertEquals(listOf(21, 181), row(anime(1072)).numbers().take(2))
    }

    @Test
    fun aRefusedKeyLeavesAbsoluteEpisodesWaitingInSeasonOne() = runTest {
        onePiece()
        showsNas.put(anime(1071))
        tmdb.status = 401
        bench.scan()

        val waiting = row(anime(1071))
        assertEquals(listOf(1, 1071, 1071, null, null), waiting.numbers())
        assertNull(waiting.matchedAt)
    }

    /** Removed while placed, back while TMDB is out of reach: kept by its file's number, it takes back what was watched. */
    @Test
    fun anAbsoluteEpisodeRemovedAndBackTakesItsStateWhereverItIsPlaced() = runTest {
        onePiece()
        showsNas.put(anime(1070))
        showsNas.put(anime(1071))
        bench.scan()
        val first = row(anime(1071))
        bench.dao.savePlayback(first.id, positionMs = 0, durationMs = null, played = true, lastPlayedAt = 5)
        showsNas.remove(anime(1071))
        bench.scan()
        assertTrue(bench.keptTitles().toString(), "ep:tmdb:$ONE_PIECE:a1071-1071" in bench.keptTitles())

        showsNas.put(anime(1071))
        bench.tmdbOutOfReach()
        bench.scan()
        val back = row(anime(1071))
        assertEquals(true to 1071, back.played to back.absoluteNumber)
    }

    /** TMDB's absolute group growing with its seasons, an episode past its end moves once both list it. */
    @Test
    fun anEpisodePastItsAbsoluteGroupMovesOnceTheGroupListsIt() = runTest {
        tmdb.show(NARUTO, "Naruto", 2002)
        tmdb.season(NARUTO, 1, episodes = 3)
        tmdb.season(NARUTO, 2, episodes = 3)
        val six = listOf(1 to 1, 1 to 2, 2 to 1, 1 to 3, 2 to 2, 2 to 3)
        tmdb.group(NARUTO, ABSOLUTE_GROUP, type = 2, name = "Absolute", listOf(six))
        val path = "Naruto/Naruto - 007.mkv"
        showsNas.put(path)
        bench.scan()
        assertEquals("waiting", listOf(1, 7, 7, null, null), row(path).numbers())

        tmdb.season(NARUTO, 2, episodes = 4)
        tmdb.group(NARUTO, ABSOLUTE_GROUP, type = 2, name = "Absolute", listOf(six + (2 to 4)))
        bench.later(DAY_MS)
        bench.scan()
        assertEquals(listOf(2, 4, 7, 2, 4), row(path).numbers())
        assertEquals("Episode 2.4", row(path).title)
    }

    /** A stray number past what TMDB counts ("Naruto - 012") holds back none it counts: the group is fetched again for them. */
    @Test
    fun aStrayNumberPastTmdbsCountsHoldsBackNoneOfTheEpisodesItCounts() = runTest {
        tmdb.show(NARUTO, "Naruto", 2002)
        tmdb.season(NARUTO, 1, episodes = 3)
        tmdb.season(NARUTO, 2, episodes = 3)
        val six = listOf(1 to 1, 1 to 2, 2 to 1, 1 to 3, 2 to 2, 2 to 3)
        tmdb.group(NARUTO, ABSOLUTE_GROUP, type = 2, name = "Absolute", listOf(six))
        val path = "Naruto/Naruto - 007.mkv"
        val stray = "Naruto/Naruto - 012.mkv"
        showsNas.put(path)
        showsNas.put(stray)
        bench.scan()
        assertEquals("waiting", listOf(1, 7, 7, null, null), row(path).numbers())

        tmdb.season(NARUTO, 2, episodes = 4)
        tmdb.group(NARUTO, ABSOLUTE_GROUP, type = 2, name = "Absolute", listOf(six + (2 to 4)))
        bench.later(DAY_MS)
        val pass = bench.pass()
        assertEquals("the group fetched again", 1, pass.requests.count { it == "tv/episode_group/$ABSOLUTE_GROUP" })
        assertEquals(listOf(2, 4, 7, 2, 4), row(path).numbers())
        assertEquals("Episode 2.4", row(path).title)
        assertEquals("still waiting", listOf(1, 12, 12, null, null), row(stray).numbers())
    }

    /**
     * Season 21 only growing as an episode waits, as an ongoing anime's does each week, that episode moves alone: TMDB is
     * asked for its season only, none of those the episodes placed before stand in.
     */
    @Test
    fun theLastSeasonOnlyGrowingMovesTheWaitingEpisodeAloneAskingTmdbForItsSeasonOnly() = runTest {
        onePiece(last = TO_1070)
        showsNas.put(anime(10))
        showsNas.put(anime(100))
        showsNas.put(anime(1070))
        showsNas.put(anime(1071))
        bench.scan()
        assertEquals(listOf(1, 10, 10, 1, 10), row(anime(10)).numbers())
        assertEquals(listOf(3, 10, 100, 3, 10), row(anime(100)).numbers())
        assertEquals("waiting", listOf(1, 1071, 1071, null, null), row(anime(1071)).numbers())

        tmdb.season(ONE_PIECE, 21, episodes = TO_1070 + 1)
        bench.later(DAY_MS)
        val pass = bench.pass()
        assertEquals(listOf(21, 180, 1071, 21, 180), row(anime(1071)).numbers())
        assertEquals("Episode 21.180", row(anime(1071)).title)
        assertEquals(listOf("tv/$ONE_PIECE/season/21"), pass.requests.filter { it.startsWith("tv/$ONE_PIECE/season/") })
        assertEquals(listOf(1, 10, 10, 1, 10), row(anime(10)).numbers())
        assertEquals(listOf(21, 179, 1070, 21, 179), row(anime(1070)).numbers())
    }

    /** TMDB splitting season 21 as an episode waits, the episodes placed by its counts move with the one it lists. */
    @Test
    fun episodesPlacedByCountsMoveWhenTmdbCountsTheSeasonsOtherwise() = runTest {
        onePiece()
        showsNas.put(anime(1090))
        showsNas.put(anime(1100))
        bench.scan()
        assertEquals(listOf(21, 199, 1090, 21, 199), row(anime(1090)).numbers())
        assertEquals("waiting", listOf(1, 1100, 1100, null, null), row(anime(1100)).numbers())

        tmdb.season(ONE_PIECE, 21, episodes = 194)
        tmdb.season(ONE_PIECE, 22, episodes = 20)
        bench.later(DAY_MS)
        bench.scan()
        assertEquals(listOf(22, 5, 1090, 22, 5), row(anime(1090)).numbers())
        assertEquals("Episode 22.5", row(anime(1090)).title)
        assertEquals(listOf(22, 15, 1100, 22, 15), row(anime(1100)).numbers())
    }

    /** A refresh finding TMDB counts the show's seasons otherwise places its episodes again, their rows and state kept. */
    @Test
    fun aRefreshFindingTheSeasonsCountedOtherwisePlacesTheEpisodesAgain() = runTest {
        onePiece()
        showsNas.put(anime(1071))
        bench.scan()
        val before = row(anime(1071))
        bench.dao.savePlayback(before.id, positionMs = 0, durationMs = null, played = true, lastPlayedAt = 5)

        tmdb.season(ONE_PIECE, 21, episodes = 100)
        tmdb.season(ONE_PIECE, 22, episodes = 100)
        bench.scan(refreshMetadata = true)
        val after = row(anime(1071))
        assertEquals(listOf(22, 80, 1071, 22, 80), after.numbers())
        assertEquals(listOf(before.id, true), listOf(after.id, after.played))
    }

    /** A show TMDB no longer has is asked about its waiting episodes a day later, as any other. */
    @Test
    fun aShowTmdbNoLongerHasIsAskedAboutADayLater() = runTest {
        onePiece(last = TO_1070)
        showsNas.put(anime(1071))
        bench.scan()
        tmdb.shows.remove(ONE_PIECE)

        bench.later(DAY_MS)
        assertEquals(1, bench.pass().requests.count { it == "tv/$ONE_PIECE" })
        bench.later(HOUR_MS)
        assertEquals("not again the same day", 0, bench.pass().requests.count { it == "tv/$ONE_PIECE" })
    }

    /** Twelve shows waiting: ten are asked about a day, the two left out first the next. */
    @Test
    fun tenShowsWaitingAreAskedAboutADayTheLongestWaitingFirst() = runTest {
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliett", "Kilo", "Lima")
        names.forEachIndexed { i, name ->
            tmdb.show(ANIME + i, name, 2020)
            tmdb.season(ANIME + i, 1, episodes = 3)
            showsNas.put("$name/$name - 010.mkv")
        }
        bench.scan()
        val waiting = names.indices.map { ANIME + it }
        assertEquals("all waiting", names.map { listOf(1, 10, 10, null, null) }, names.map { row("$it/$it - 010.mkv").numbers() })
        // The shows TMDB is asked about, in turn.
        fun asked(pass: ScanBench.Pass) = pass.requests.mapNotNull { it.removePrefix("tv/").toIntOrNull() }

        bench.later(DAY_MS)
        val first = asked(bench.pass())
        assertEquals(10, first.size)
        bench.later(DAY_MS)
        val second = asked(bench.pass())
        assertEquals(10, second.size)
        assertEquals("the two left out first", (waiting - first.toSet()).toSet(), second.take(2).toSet())
    }

    /** Two copies of one episode take its place together, wherever they are placed from. */
    @Test
    fun twoCopiesOfOneEpisodeTakeItsPlaceTogether() = runTest {
        onePiece()
        showsNas.put(anime(1071))
        showsNas.put(COPY)
        bench.scan()

        assertEquals(listOf(21, 180, 1071, 21, 180), row(anime(1071)).numbers())
        assertEquals(listOf(21, 180, 1071, 21, 180), row(COPY).numbers())
        assertNull("no season 1", bench.dao.season(checkNotNull(row(COPY).seriesId), 1))
    }

    @Test
    fun twoCopiesWaitingMoveTogether() = runTest {
        onePiece(last = TO_1070)
        showsNas.put(anime(1071))
        showsNas.put(COPY)
        bench.scan()
        tmdb.season(ONE_PIECE, 21, episodes = TO_1070 + 1)
        bench.later(DAY_MS)
        bench.scan()

        assertEquals(listOf(21, 180, 1071, 21, 180), row(anime(1071)).numbers())
        assertEquals(listOf(21, 180, 1071, 21, 180), row(COPY).numbers())
        assertNull("no season 1", bench.dao.season(checkNotNull(row(COPY).seriesId), 1))
    }

    /** "S01E1071" in DVD order, then Automatique: it is read from the show's start again, at its place in season 21. */
    @Test
    fun dvdThenAutomaticGivesBackThePlaceFromTheShowsStart() = runTest {
        onePiece()
        tmdb.group(ONE_PIECE, DVD_GROUP, type = 3, name = "DVD", listOf(listOf(1 to 1, 1 to 2)))
        val path = "One Piece/Season 1/One Piece - S01E1071 - Le sommet.mkv"
        showsNas.put(path)
        bench.scan()
        assertEquals(listOf(21, 180, 1071, 21, 180), row(path).numbers())
        val show = checkNotNull(row(path).seriesId)

        bench.scanner.chooseOrder(show, DVD_GROUP)
        assertEquals("as the DVD has it, which has no such episode", listOf(1, 1071, null, null, null), row(path).numbers())
        bench.scanner.chooseOrder(show, null)
        assertEquals(listOf(21, 180, 1071, 21, 180), row(path).numbers())
        assertEquals("Episode 21.180", row(path).title)
    }

    private companion object {
        const val ONE_PIECE = 37854
        const val NARUTO = 46260
        const val ABSOLUTE_GROUP = "5a0b8c4dc3a368623100dee8"
        const val DVD_GROUP = "5a0b8c4dc3a368623100dee9"
        const val SEASON_21 = 200

        /** The first of twelve anime shows. */
        const val ANIME = 70_000

        /** Another release of One Piece's 1071st episode, beside the first. */
        const val COPY = "One Piece/[SubsPlease] One Piece - 1071 (720p) [EFGH5678].mkv"

        /** Season 21's episodes as TMDB lists them up to the 1070th of the show, the day before it lists the 1071st. */
        const val TO_1070 = 179
        const val DAY_MS = 24 * 60 * 60_000L
        const val HOUR_MS = 60 * 60_000L
    }
}
