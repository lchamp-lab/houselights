package app.houselights.providers.local

import app.houselights.core.Job
import app.houselights.core.NfoActor
import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which source wins for each detail of a film or a show. */
class MetadataMergeTest {
    private val film = ItemEntity(
        kind = ItemKind.MOVIE, sourceId = 1, title = "heat 1995", sortTitle = "heat 1995", year = 1995,
        overview = "Known before", genres = listOf("Known"), addedAt = 0,
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
        val nfo = NfoInfo(kind = NfoKind.MOVIE, title = "Heat (version longue)", genres = listOf("Policier"))
        val merged = MetadataMerge.merge(film, nfo, tmdb, LocalArt())
        assertEquals("Heat (version longue)", merged.title)
        assertEquals(listOf("Policier"), merged.genres)
        // TMDB's overview is empty: what was known stays.
        assertEquals("Known before", merged.overview)
        assertEquals("A Los Angeles crime saga", merged.tagline)
        assertEquals(170, merged.runtimeMin)
        assertEquals("12", merged.certification)
        assertEquals(listOf("Michael Mann"), merged.directors)
        assertEquals(949, merged.tmdbId)
    }

    @Test
    fun artworkComesFromLocalFilesThenTheNfoThenTmdb() {
        val nfo = NfoInfo(kind = NfoKind.MOVIE, posterUrl = "https://nfo/poster.jpg", fanartUrl = "https://nfo/fanart.jpg")
        val local = LocalArt(poster = "file:///cache/poster.jpg")
        val merged = MetadataMerge.merge(film, nfo, tmdb, local)
        assertEquals("file:///cache/poster.jpg", merged.posterPath)
        assertEquals("https://nfo/fanart.jpg", merged.backdropPath)
        assertEquals("/logo.png", merged.logoPath)
    }

    @Test
    fun showsTakeTheirStatusAndKeepTheirDirectors() {
        val show = film.copy(kind = ItemKind.SERIES, directors = listOf("Kept"))
        val tv = TmdbTv(id = 1, name = "MobLand", firstAirDate = "2025-03-30", lastAirDate = "2025-06-01", status = "Returning Series")
            .details(certification = null, logo = null)
        val merged = MetadataMerge.merge(show, nfo = null, tv, LocalArt())
        assertEquals(listOf("Kept"), merged.directors)
        assertTrue(merged.continuing)
        assertEquals(2025, merged.endYear)
        assertEquals("mobland", merged.sortTitle)
    }

    @Test
    fun aRematchForgetsThePreviousDetails() {
        val cleared = MetadataMerge.withoutDetails(film.copy(posterPath = "/old.jpg"), "Heat", 1995)
        assertNull(cleared.posterPath)
        assertNull(cleared.overview)
        assertEquals(emptyList<String>(), cleared.genres)
        assertEquals("heat", cleared.sortTitle)
    }

    @Test
    fun theNfosActorsWinWhenItListsAny() {
        val nfo = NfoInfo(kind = NfoKind.MOVIE, actors = listOf(NfoActor("Robert De Niro", "Neil McCauley", thumb = null, order = 0)))
        fun cast(nfo: NfoInfo?, credits: TmdbCredits?) =
            CreditRules.credits(7, show = false, nfo, credits?.let(::TmdbPeople))?.filter { it.job == Job.CAST }?.map { it.name }
        assertEquals(listOf("Robert De Niro"), cast(nfo, TmdbCredits()))
        assertEquals(listOf("Al Pacino"), cast(NfoInfo(kind = NfoKind.MOVIE), heatCredits))
        assertNull(cast(nfo = null, credits = null))
    }

    @Test
    fun articlesDoNotCountInTheSortOrder() {
        assertEquals("batman", MetadataMerge.sortKey("The Batman"))
        assertEquals("grand bleu", MetadataMerge.sortKey("Le Grand Bleu"))
        assertEquals("aliens", MetadataMerge.sortKey(" Aliens "))
    }

    /** Sorted as bytes, "Ça", "À couteaux tirés", "Été 85" and "Œdipe" came after "Zodiac"; "L'Arnaque" sorted with L. */
    @Test
    fun accentsLigaturesAndAGluedArticleSortWithTheirLetter() {
        val titles = listOf("Zodiac", "Œdipe", "L'Arnaque", "Été 85", "Ça", "À couteaux tirés")
        assertEquals(
            listOf("À couteaux tirés", "L'Arnaque", "Ça", "Été 85", "Œdipe", "Zodiac"),
            titles.sortedBy(MetadataMerge::sortKey),
        )
        assertEquals("arnaque", MetadataMerge.sortKey("L’Arnaque"))
        assertEquals("exorciste", MetadataMerge.sortKey("L' Exorciste"))
        assertEquals("aeon flux", MetadataMerge.sortKey("Æon Flux"))
        assertEquals("strasse", MetadataMerge.sortKey("Straße"))
        assertEquals("la land", MetadataMerge.sortKey("La La Land"))
    }

    private val heatCredits =TmdbCredits(cast = listOf(TmdbCastMember("Al Pacino", "Vincent Hanna", order = 0)))
}
