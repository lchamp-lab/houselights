package app.houselights.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import app.houselights.core.Library
import app.houselights.core.LibraryFilter
import app.houselights.core.LibraryKind
import app.houselights.core.LibraryNumbers
import app.houselights.core.ProviderCapabilities
import app.houselights.core.Tally
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.LoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The library in numbers: three figures, then rows that open what they count; Jellyfin shows its titles alone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class NumbersPaneTest {
    @get:Rule
    val compose = createComposeRule()

    private val films = Library("movies", LibraryKind.MOVIES, null)
    private val shows = Library("shows", LibraryKind.SHOWS, null)
    private val walls = mapOf(LibraryKind.MOVIES to films, LibraryKind.SHOWS to shows)

    private val local = ProviderCapabilities(
        markPlayed = true, favorites = true, similar = true, pathMapping = false, people = true,
        formatNumbers = true, formatFilters = true, personFilter = true,
    )
    private val jellyfin =
        ProviderCapabilities(markPlayed = true, favorites = true, similar = true, pathMapping = true, people = true, personFilter = true)

    private val numbers = LibraryNumbers(
        films = 412,
        shows = 38,
        episodes = 1204,
        sizeBytes = 38_400_000_000_000L,
        runtimeMs = 1214 * HOUR + 20 * MINUTE,
        picture = listOf(
            Tally("4K", 54, 3, LibraryFilter(picture = "4K")),
            Tally("Dolby Vision", 131, 12, LibraryFilter(picture = "Dolby Vision")),
        ),
        sound = listOf(Tally("TrueHD Atmos", 42, 0, LibraryFilter(sound = "TrueHD Atmos"))),
        files = listOf(Tally("mkv", 380, 30, LibraryFilter(fileType = "mkv")), Tally("bdmv", 0, 2, LibraryFilter(fileType = "bdmv"))),
        directors = listOf(Tally("Denis Villeneuve", 7, 0, LibraryFilter(person = "p7"), "p7")),
    )

    private val opened = mutableListOf<Pair<Library, LibraryFilter>>()
    private val pages = mutableListOf<String>()

    private fun show(state: LoadState<CountedLibrary>) {
        compose.setTvContent {
            val rows = PaneRows(rememberFocusMemory(), "Numbers")
            LazyColumn(Modifier.fillMaxSize()) {
                numbersPane(state, rows, { library, filter -> opened += library to filter }, { pages += it }, onRetry = {})
            }
        }
    }

    /** The pane scrolled to what says [text]: it is there. */
    private fun seen(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertExists()
    }

    /** OK on the row named [name], as the remote does it. */
    private fun choose(name: String) {
        seen(name)
        compose.onNodeWithText(name).requestFocus()
        compose.press(Key.DirectionCenter)
    }

    @Test
    fun itsHeadStatesTheTitlesTheRoomAndTheHoursEachOverWhatItCounts() {
        show(LoadState.Ready(CountedLibrary(numbers, local, walls)))
        compose.onNodeWithText("La collection en chiffres").assertExists()
        compose.onNodeWithText("412${NB}films").assertExists()
        compose.onNodeWithText("38${NB}séries et 1${NB}204${NB}épisodes").assertExists()
        compose.onNodeWithText("38,4${NB}To").assertExists()
        compose.onNodeWithText("de fichiers, toutes copies comprises").assertExists()
        compose.onNodeWithText("1${NB}214${NB}h").assertExists()
        compose.onNodeWithText("pour tout voir, bout${NB}à${NB}bout").assertExists()
    }

    @Test
    fun itsRowsNameEachValueAsACaseDoesAndCountItsFilms() {
        show(LoadState.Ready(CountedLibrary(numbers, local, walls)))
        listOf("Image", "Son", "Fichiers", "Réalisation").forEach(::seen)
        // A definition by its name, a sound by the case's, a kind of file as a case prints it.
        listOf("Ultra HD", "Dolby Vision", "Dolby TrueHD Atmos", "MKV", "Blu-ray", "Denis Villeneuve").forEach(::seen)
        seen("131${NB}films")
        // What no film holds counts its shows, and says so.
        seen("2${NB}séries")
    }

    @Test
    fun aFormatOpensItsFilmsOnTheirWallFilteredByIt() {
        show(LoadState.Ready(CountedLibrary(numbers, local, walls)))
        choose("Dolby Vision")
        assertEquals(listOf(films to LibraryFilter(picture = "Dolby Vision")), opened)
    }

    @Test
    fun aValueNoFilmHoldsOpensTheShowsThatDo() {
        show(LoadState.Ready(CountedLibrary(numbers, local, walls)))
        choose("Blu-ray")
        assertEquals(listOf(shows to LibraryFilter(fileType = "bdmv")), opened)
    }

    @Test
    fun aDirectorOpensTheirPage() {
        show(LoadState.Ready(CountedLibrary(numbers, local, walls)))
        seen("7${NB}films")
        choose("Denis Villeneuve")
        assertEquals(listOf("p7"), pages)
        assertEquals(emptyList<Pair<Library, LibraryFilter>>(), opened)
    }

    @Test
    fun aSourceThatTellsItsTitlesAloneShowsItsTitlesAlone() {
        // Its numbers carry a format all the same (Jellyfin counts its 4K films): the source does not tell the rest.
        show(LoadState.Ready(CountedLibrary(numbers.copy(sizeBytes = null, runtimeMs = null, directors = emptyList()), jellyfin, walls)))
        compose.onNodeWithText("412${NB}films").assertExists()
        compose.onNodeWithText("38${NB}séries et 1${NB}204${NB}épisodes").assertExists()
        val unknown = listOf("de fichiers, toutes copies comprises", "pour tout voir, bout${NB}à${NB}bout")
        (unknown + listOf("Image", "Son", "Fichiers", "Ultra HD")).forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun aRoomOrHoursTheSourceDoesNotTellAreLeftOutNotSaidAsNothing() {
        show(LoadState.Ready(CountedLibrary(numbers.copy(sizeBytes = 0, runtimeMs = null), local, walls)))
        compose.onNodeWithText("de fichiers, toutes copies comprises").assertDoesNotExist()
        compose.onNodeWithText("pour tout voir, bout${NB}à${NB}bout").assertDoesNotExist()
        compose.onNodeWithText("412${NB}films").assertExists()
    }

    @Test
    fun aLibraryOfShowsAloneCountsItsShowsOverTheirEpisodes() {
        show(LoadState.Ready(CountedLibrary(numbers.copy(films = 0), local, walls)))
        compose.onNodeWithText("38${NB}séries").assertExists()
        compose.onNodeWithText("1${NB}204${NB}épisodes").assertExists()
    }

    @Test
    fun aDirectorWhosePageTheSourceDoesNotOpenLeadsNowhere() {
        val page = numbersPage(CountedLibrary(numbers, local.copy(people = false), walls))
        assertNull(page.groups.single { it.kind == TallyKind.DIRECTORS }.rows.single().door)
    }

    private companion object {
        const val NB = Typography.nbsp
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
