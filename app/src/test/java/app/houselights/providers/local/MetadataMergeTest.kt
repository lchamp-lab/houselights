package app.houselights.providers.local

import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which source wins for each detail of a film or a show. */
class MetadataMergeTest {
    private val film = ItemEntity(
        kind = ItemKind.MOVIE, sourceId = 1, title = "heat 1995", sortTitle = "heat 1995", year = 1995,
        overview = "Known before", genres = "Known", addedAt = 0,
    )
    private val tmdb = TmdbMovie(
        id = 949, title = "Heat", originalTitle = "Heat", overview = "", tagline = "A Los Angeles crime saga",
        releaseDate = "1995-12-15", runtime = 170, voteAverage = 7.9, genres = listOf(TmdbGenre("Action"), TmdbGenre("Crime")),
        posterPath = "/tmdb-poster.jpg", credits = TmdbCredits(
            cast = listOf(TmdbCastMember("Al Pacino", "Vincent Hanna", order = 0)),
            crew = listOf(TmdbCrewMember("Michael Mann", "Director"), TmdbCrewMember("Dante Spinotti", "Director of Photography")),
        ),
    ).details(certification = "12", logo = "/logo.png")

    @Test
    fun theNfoWinsThenTmdbThenWhatWasKnown() {
        val nfo = NfoInfo(kind = "movie", title = "Heat (version longue)", genres = listOf("Policier"))
        val merged = MetadataMerge.merge(film, nfo, tmdb, LocalArt())
        assertEquals("Heat (version longue)", merged.title)
        assertEquals("Policier", merged.genres)
        // TMDB's overview is empty: what was known stays.
        assertEquals("Known before", merged.overview)
        assertEquals("A Los Angeles crime saga", merged.tagline)
        assertEquals(170, merged.runtimeMin)
        assertEquals("12", merged.certification)
        assertEquals("Michael Mann", merged.directors)
        assertEquals(949, merged.tmdbId)
    }

    @Test
    fun artworkComesFromLocalFilesThenTheNfoThenTmdb() {
        val nfo = NfoInfo(kind = "movie", posterUrl = "https://nfo/poster.jpg", fanartUrl = "https://nfo/fanart.jpg")
        val local = LocalArt(poster = "file:///cache/poster.jpg")
        val merged = MetadataMerge.merge(film, nfo, tmdb, local)
        assertEquals("file:///cache/poster.jpg", merged.posterPath)
        assertEquals("https://nfo/fanart.jpg", merged.backdropPath)
        assertEquals("/logo.png", merged.logoPath)
    }

    @Test
    fun showsTakeTheirStatusAndKeepTheirDirectors() {
        val show = film.copy(kind = ItemKind.SERIES, directors = "Kept")
        val tv = TmdbTv(id = 1, name = "MobLand", firstAirDate = "2025-03-30", lastAirDate = "2025-06-01", status = "Returning Series")
            .details(certification = null, logo = null)
        val merged = MetadataMerge.merge(show, nfo = null, tv, LocalArt())
        assertEquals("Kept", merged.directors)
        assertTrue(merged.continuing)
        assertEquals(2025, merged.endYear)
        assertEquals("mobland", merged.sortTitle)
    }

    @Test
    fun aRematchForgetsThePreviousDetails() {
        val cleared = MetadataMerge.withoutDetails(film.copy(posterPath = "/old.jpg"), "Heat", 1995)
        assertNull(cleared.posterPath)
        assertNull(cleared.overview)
        assertEquals("", cleared.genres)
        assertEquals("heat", cleared.sortTitle)
    }

    @Test
    fun theNfosActorsWinWhenItListsAny() {
        val nfo = NfoInfo(kind = "movie", actors = listOf(NfoActor("Robert De Niro", "Neil McCauley", thumb = null, order = 0)))
        assertEquals(listOf("Robert De Niro"), MetadataMerge.cast(7, nfo, TmdbCredits())?.map { it.name })
        assertEquals(listOf("Al Pacino"), MetadataMerge.cast(7, NfoInfo(kind = "movie"), heatCredits)?.map { it.name })
        assertNull(MetadataMerge.cast(7, nfo = null, credits = null))
    }

    @Test
    fun articlesDoNotCountInTheSortOrder() {
        assertEquals("batman", MetadataMerge.sortKey("The Batman"))
        assertEquals("grand bleu", MetadataMerge.sortKey("Le Grand Bleu"))
        assertEquals("aliens", MetadataMerge.sortKey(" Aliens "))
    }

    private val heatCredits = TmdbCredits(cast = listOf(TmdbCastMember("Al Pacino", "Vincent Hanna", order = 0)))
}
