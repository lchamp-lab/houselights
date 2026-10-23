package app.houselights

import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.core.WatchStatus
import app.houselights.ui.onboarding.LoginFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A wall's route carries its starting filter, a format or a person; the sign-in's, how its form starts. */
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
    }

    /** Status, genre and decade are chosen on the wall itself: a door's route sets only the filter it names. */
    @Test
    fun aDoorOpensOnWhatItNamesAlone() {
        val route = Route.Library.of(films, LibraryFilter(status = WatchStatus.UNWATCHED, genre = "Drame", decade = 1990, picture = "4K"))
        assertEquals(LibraryFilter(picture = "4K"), route.filter)
    }

    @Test
    fun aNameIsCarriedOnlyWithItsPerson() {
        assertNull(Route.Library.of(films, LibraryFilter(picture = "4K"), personName = "Denis Villeneuve").personName)
    }

    /** The sign-in's route says how its form starts: the current server, nothing, or a server whose token was refused. */
    @Test
    fun theSignInsRouteCarriesHowItsFormStarts() {
        listOf(LoginFill.Current, LoginFill.Blank, LoginFill.Server("https://nas:8920", "lea"), LoginFill.Server("https://nas", null))
            .forEach { assertEquals(it, Route.JellyfinLogin.of(it).fill) }
    }
}
