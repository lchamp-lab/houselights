package app.houselights

import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.Quality
import app.houselights.core.WatchStatus
import app.houselights.ui.home.WatchRow
import app.houselights.ui.onboarding.LoginFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A wall's route carries its library, sagas and starting filter; « À regarder »'s, its row; the sign-in's, how its form starts. */
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

    /** A wall's library is the route's; a blank title, as a server may send one, gives way to its kind's name. */
    @Test
    fun aWallIsNamedByItsLibraryElseByItsKind() {
        val named = Route.Library("4k", LibraryKind.MOVIES, title = "Films 4K")
        assertEquals(Library("4k", LibraryKind.MOVIES, "Films 4K"), named.library)
        assertEquals("Films 4K", named.wallTitle("Movies"))
        listOf(null, "", "  ").forEach { title ->
            val unnamed = Route.Library("movies", LibraryKind.MOVIES, title = title)
            assertEquals(films, unnamed.library)
            assertEquals("Movies", unnamed.wallTitle("Movies"))
        }
    }

    /** A films' wall carries the source's sagas by id; without them, it shows none. */
    @Test
    fun aFilmsWallCarriesTheSourcesSagas() {
        val sagas = Library("collections", LibraryKind.COLLECTIONS, null)
        assertEquals(sagas, Route.Library("movies", LibraryKind.MOVIES, sagas = "collections").sagasLibrary)
        assertNull(Route.Library("movies", LibraryKind.MOVIES).sagasLibrary)
    }

    /** « À regarder » opens on the row its door named, the first one for a name no row has. */
    @Test
    fun theWatchPageOpensOnTheRowItsDoorNamed() {
        WatchRow.entries.forEach { assertEquals(it, Route.Watch.of(it).watchRow) }
        assertEquals(WatchRow.CONTINUE, Route.Watch("GONE").watchRow)
    }

    /** The sign-in's route says how its form starts: the current server, nothing, or a server whose token was refused. */
    @Test
    fun theSignInsRouteCarriesHowItsFormStarts() {
        listOf(LoginFill.Current, LoginFill.Blank, LoginFill.Server("https://nas:8920", "lea"), LoginFill.Server("https://nas", null))
            .forEach { assertEquals(it, Route.JellyfinLogin.of(it).fill) }
    }
}
