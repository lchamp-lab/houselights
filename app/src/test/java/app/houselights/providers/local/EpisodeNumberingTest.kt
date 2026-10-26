package app.houselights.providers.local

import app.houselights.providers.local.Numbering.ABSOLUTE
import app.houselights.providers.local.Numbering.FOLDER
import app.houselights.providers.local.Numbering.SEQUEL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an episode goes in its show ([EpisodeNumbering]): a number from the show's start in TMDB's seasons, by its
 * absolute group first; a number past its season read so from 100, only automatically; in a group, by the group's
 * seasons; one TMDB lists none for yet, or whose place another file holds, waits in season 1 under its number.
 */
class EpisodeNumberingTest {
    /** TMDB's seasons from the first, of these [counts], [specials] in season 0 first. */
    private fun aired(vararg counts: Int, specials: Int = 0) = EpisodeOrder.Aired(
        listOf(AiredSeason(0, specials)).filter { specials > 0 } + counts.mapIndexed { i, c -> AiredSeason(i + 1, c) },
    )

    private fun numbering(
        aired: EpisodeOrder.Aired,
        order: EpisodeOrder = aired,
        automatic: Boolean = true,
        absolute: List<TmdbNumber>? = aired.absolute(),
    ) = ShowNumbering(order, automatic, absolute, aired)

    private fun place(parsed: ParsedEpisode, numbering: ShowNumbering, held: Set<TmdbNumber> = emptySet()): Placement =
        runBlocking { EpisodeNumbering.place(parsed, numbering) { it in held } }

    private fun fromStart(n: Int, last: Int? = null) = ParsedEpisode("One Piece", 1, n, last, ABSOLUTE)

    private fun named(season: Int, episode: Int, numbering: Numbering = Numbering.SEASON) =
        ParsedEpisode("Show", season, episode, null, numbering)

    @Test
    fun anAbsoluteNumberFallsInTheSeasonTmdbsCountsReach() {
        val show = numbering(aired(8, 22, 17, specials = 5))
        assertEquals(Placement(2, 2, null, TmdbNumber(2, 2), null, absolute = 10), place(fromStart(10), show))
        assertEquals("specials never count", Placement(1, 1, null, TmdbNumber(1, 1), null, absolute = 1), place(fromStart(1), show))
    }

    @Test
    fun aDoubleEpisodeSpansOnlyWithinOneSeason() {
        val show = numbering(aired(8, 22))
        assertEquals(Placement(1, 3, 4, TmdbNumber(1, 3), 4, absolute = 3), place(fromStart(3, last = 4), show))
        assertEquals(Placement(1, 8, null, TmdbNumber(1, 8), null, absolute = 8), place(fromStart(8, last = 9), show))
    }

    @Test
    fun tmdbsAbsoluteGroupOutranksTheCounts() {
        val group = listOf(TmdbNumber(1, 1), TmdbNumber(1, 2), TmdbNumber(2, 1), TmdbNumber(1, 3))
        val show = numbering(aired(3, 3), absolute = group)
        assertEquals(TmdbNumber(2, 1), place(fromStart(3), show).tmdb)
    }

    @Test
    fun aNumberTmdbDoesNotReachWaitsInSeasonOne() {
        val waiting = Placement(1, 100, null, tmdb = null, tmdbLast = null, absolute = 100)
        assertEquals(waiting, place(fromStart(100), numbering(aired(8, 22))))
        assertEquals("its counts not known yet", waiting, place(fromStart(100), ShowNumbering.UNKNOWN))
    }

    /** A place another file holds is refused: the number waits in season 1, and S01E1071 stays where its name puts it. */
    @Test
    fun anAbsoluteNumberWhosePlaceIsHeldWaits() {
        val show = numbering(aired(8, 22))
        assertEquals(Placement(1, 10, null, null, null, absolute = 10), place(fromStart(10), show, held = setOf(TmdbNumber(2, 2))))
        val long = numbering(aired(500, 600))
        assertEquals(TmdbNumber(1, 1071), place(named(1, 1071), long, held = setOf(TmdbNumber(2, 571))).tmdb)
    }

    @Test
    fun anS01NumberPastItsSeasonIsReadFromTheStartFrom100() {
        val onePiece = numbering(aired(*IntArray(19) { 45 }, 36, 200))
        assertEquals(Placement(21, 180, null, TmdbNumber(21, 180), null, absolute = 1071), place(named(1, 1071), onePiece))
        assertEquals("in its own season too", TmdbNumber(21, 180), place(named(21, 1071), onePiece).tmdb)
        val short = numbering(aired(10, 10))
        assertEquals("under 100, as named", Placement(1, 12, null, TmdbNumber(1, 12), null, null), place(named(1, 12), short))
        val twenties = numbering(aired(*IntArray(10) { 20 }))
        assertEquals("landing in another season, as named", TmdbNumber(5, 150), place(named(5, 150), twenties).tmdb)
        assertEquals("past what TMDB lists, it waits", null, place(named(1, 1071), numbering(aired(500, 500))).tmdb)
    }

    @Test
    fun choosingTmdbsSeasonsReadsLongSeasonsAsNamed() {
        val chosen = numbering(aired(500, 600), automatic = false)
        assertEquals(Placement(1, 1071, null, TmdbNumber(1, 1071), null, null), place(named(1, 1071), chosen))
    }

    /** "Season 1/101 - Pilot.mkv" is S01E01 even in a show of 200 episodes: its first digit is its folder's season. */
    @Test
    fun aThreeDigitNumberInASeasonsFolderIsItsSeasonThenItsEpisode() {
        val show = numbering(aired(*IntArray(10) { 20 }))
        assertEquals(Placement(1, 1, null, TmdbNumber(1, 1), null, null), place(named(1, 101, FOLDER), show))
        assertEquals(TmdbNumber(2, 5), place(named(2, 205, FOLDER), show).tmdb)
    }

    @Test
    fun anAnimeSequelPastItsSeasonIsNumberedFromTheStart() {
        val show = numbering(aired(12, 12))
        assertEquals(Placement(2, 2, null, TmdbNumber(2, 2), null, absolute = 14), place(named(2, 14, SEQUEL), show))
        assertEquals(Placement(2, 1, null, TmdbNumber(2, 1), null, null), place(named(2, 1, SEQUEL), show))
    }

    /** Numbered as Kodi and Jellyfin number a group: season by its order, episode by its place in it. */
    @Test
    fun aGroupNumbersSeasonsByItsOrderAndEpisodesFromOne() {
        val disc = OrderSeason(1, "Disc 1", listOf(TmdbNumber(1, 11), TmdbNumber(1, 1), TmdbNumber(1, 2)))
        val dvd = EpisodeOrder.Group("dvd", 3, listOf(disc))
        val show = numbering(aired(14), order = dvd, automatic = false)
        assertEquals(Placement(1, 1, null, TmdbNumber(1, 11), null, null), place(named(1, 1), show))
        assertEquals(TmdbNumber(1, 1), place(named(1, 2), show).tmdb)
        assertEquals("one the group leaves out shows none", null, place(named(1, 9), show).tmdb)
        assertTrue("its TMDB episode kept apart", place(named(1, 2), show).keptTmdb(dvd))
        assertEquals(1, dvd.seasons.single().tmdbSeason)
    }

    @Test
    fun aYearsSeasonShowsTheSeasonOfItsYear() {
        val aired = EpisodeOrder.Aired(listOf(AiredSeason(1, 10, "2022-01-05"), AiredSeason(2, 10, "2023-02-01")))
        assertEquals(Placement(2023, 4, null, TmdbNumber(2, 4), null, null), place(named(2023, 4), numbering(aired)))
    }

    @Test
    fun aPlaceInTmdbsSeasonsKeepsNoTmdbEpisodeOfItsOwn() {
        val aired = aired(10)
        assertFalse(Placement(1, 2, null, TmdbNumber(1, 2), null, null).keptTmdb(aired))
        assertTrue(Placement(1, 2, null, TmdbNumber(1, 2), null, absolute = 2).keptTmdb(aired))
    }

    @Test
    fun ordersAreKeptAsTheyWereRead() {
        val seasons = listOf(TmdbSeasonSummary(0, 3), TmdbSeasonSummary(1, 61, "1999-10-20"))
        assertEquals(listOf(AiredSeason(0, 3), AiredSeason(1, 61, "1999-10-20")), OrderPlaces.aired(OrderPlaces.ofAired(seasons)))
        val group = TmdbEpisodeGroup(
            "g", 3, "DVD",
            listOf(TmdbGroupSeason("Disc 1", 1, listOf(TmdbGroupEpisode(1, 2, order = 1), TmdbGroupEpisode(1, 11, order = 0)))),
        )
        val read = OrderPlaces.group(OrderPlaces.ofGroup(group))?.single()
        assertEquals(listOf(TmdbNumber(1, 11), TmdbNumber(1, 2)), read?.episodes)
        assertNull("refused", OrderPlaces.group(EpisodeOrders.REFUSED))
    }
}
