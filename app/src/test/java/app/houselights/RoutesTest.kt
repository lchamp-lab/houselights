package app.houselights

import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.core.WatchStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A wall's route carries what it opens on: a format or a person, the filter its screen starts from. */
class RoutesTest {
    private val films = Library("movies", LibraryKind.MOVIES, null)

    @Test
    fun aWallOpenedWholeStartsUnfiltered() {
        assertNull(Route.Library("movies", LibraryKind.MOVIES).filter)
    }

    @Test
    fun aWallOpenedOnPartOfTheLibraryCarriesWhatItNames() {
        val filter =
            LibraryFilter(quality = Quality.HDR, picture = "Dolby Vision", sound = "TrueHD Atmos", fileType = "bdmv", person = "p7")
        val route = Route.Library.of(films, filter, personName = "Denis Villeneuve")
        assertEquals(filter, route.filter)
        assertEquals("Denis Villeneuve", route.personName)
        // As navigation keeps it, across a stopped process.
        val kept = Json.decodeFromString(Route.Library.serializer(), Json.encodeToString(Route.Library.serializer(), route))
        assertEquals(route, kept)
        assertEquals(filter, kept.filter)
    }

    /** A wall's status, genre and decade are its own, chosen there: a door opens on what it names alone. */
    @Test
    fun aDoorOpensOnWhatItNamesAlone() {
        val route = Route.Library.of(films, LibraryFilter(status = WatchStatus.UNWATCHED, genre = "Drame", decade = 1990, picture = "4K"))
        assertEquals(LibraryFilter(picture = "4K"), route.filter)
    }

    @Test
    fun aNameIsCarriedOnlyWithItsPerson() {
        assertNull(Route.Library.of(films, LibraryFilter(picture = "4K"), personName = "Denis Villeneuve").personName)
    }
}
