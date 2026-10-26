package app.houselights.providers.local

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anime numbered from their show's start, as a scan places them: in TMDB's seasons, by the show's absolute group when it
 * has one, else by its seasons' counts; one TMDB does not list yet waits in season 1 and moves once it does, a day later
 * at most, its row and what was watched of it kept; TMDB asked once for what it needs.
 */
@RunWith(RobolectricTestRunner::class)
class ScanAbsoluteTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var bench: ScanBench
    private lateinit var shows: SourceEntity
    private val tmdb get() = bench.tmdb
    private val showsNas get() = bench.showsNas

    @Before
    fun open() = runTest {
        bench = ScanBench(temp.newFolder("artwork"))
        shows = bench.folder("Séries", SourceKind.SHOWS)
    }

    @After
    fun close() = bench.close()

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

    private companion object {
        const val ONE_PIECE = 37854
        const val NARUTO = 46260
        const val ABSOLUTE_GROUP = "5a0b8c4dc3a368623100dee8"
        const val SEASON_21 = 200

        /** Season 21's episodes as TMDB lists them up to the 1070th of the show, the day before it lists the 1071st. */
        const val TO_1070 = 179
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
