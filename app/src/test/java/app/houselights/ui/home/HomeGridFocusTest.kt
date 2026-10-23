package app.houselights.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.core.MediaItem
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.holdOk
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import android.view.KeyEvent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.houselights.ui.testing.setTvContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DESIGN.md, Focus: home's grid read with the remote. Right from the case to the first library, columns kept up and
 * down, Down from a door over two to the one last visited, Left back to the case; focus back on the door left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class HomeGridFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(home: HomeState = fullHome(), actions: HomeActions = HomeActions(onOpenSearch = {})) {
        compose.setTvContent { HomeGrid(home, actions) }
        compose.waitForIdle()
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    @Test
    fun aFirstVisitStartsOnTheCase() {
        open()
        focusIsOn("Resume")
    }

    @Test
    fun rightFromTheCaseGoesToTheFirstLibrary() {
        open()
        compose.press(Key.DirectionRight)
        focusIsOn("Movies")
    }

    @Test
    fun upAndDownKeepTheColumns() {
        open()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionUp)
        focusIsOn("Next up")
        compose.press(Key.DirectionRight)
        focusIsOn("New")
        compose.press(Key.DirectionDown)
        focusIsOn("TV shows")
        compose.press(Key.DirectionDown)
        focusIsOn("Settings")
        compose.press(Key.DirectionLeft)
        focusIsOn("Search")
    }

    @Test
    fun downFromMoviesGoesToSagasThenToTheDoorLastVisited() {
        open()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        focusIsOn("Sagas")
        compose.press(Key.DirectionRight)
        focusIsOn("Search")
        compose.press(Key.DirectionUp)
        focusIsOn("Movies")
        compose.press(Key.DirectionDown)
        focusIsOn("Search")
    }

    @Test
    fun leftFromARowsFirstDoorGoesToTheCase() {
        open()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        focusIsOn("Sagas")
        compose.press(Key.DirectionLeft)
        focusIsOn("Resume")
    }

    @Test
    fun withoutSagasSearchTakesTheirPlace() {
        open(fullHome(doors = fullHome().doors!!.copy(sagas = null)))
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        focusIsOn("Search")
        compose.press(Key.DirectionRight)
        focusIsOn("Settings")
    }

    @Test
    fun focusComesBackToTheDoorLeft() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HouselightsTheme { HomeGrid(fullHome()) } }
        compose.waitForIdle()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        focusIsOn("Settings")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        focusIsOn("Settings")
    }

    /** Each door opens what it shows: the case plays, the others open their page, wall or screen. */
    @Test
    fun eachDoorOpensWhatItShows() {
        val opened = mutableListOf<String>()
        val actions = HomeActions(
            onPlay = { item: MediaItem, resume -> opened += "play ${item.id} $resume" },
            onOpenWatch = { opened += "watch $it" },
            onOpenLibrary = { library: Library, sagas: Library? -> opened += "library ${library.id} ${sagas?.id}" },
            onOpenSagas = { films: Library?, sagas -> opened += "sagas ${films?.id} ${sagas.id}" },
            onOpenSearch = { opened += "search" },
            onOpenSettings = { opened += "settings" },
        )
        open(actions = actions)
        val walk = listOf(
            emptyList(),
            listOf(Key.DirectionRight, Key.DirectionUp),
            listOf(Key.DirectionRight),
            listOf(Key.DirectionDown),
            listOf(Key.DirectionDown),
            listOf(Key.DirectionLeft),
            listOf(Key.DirectionLeft),
            listOf(Key.DirectionUp),
        )
        walk.forEach { keys ->
            keys.forEach { compose.press(it) }
            compose.press(Key.DirectionCenter)
        }
        assertEquals(
            listOf(
                "play heat true",
                "watch NEXT_UP",
                "watch LATEST",
                "library shows null",
                "settings",
                "search",
                "sagas movies collections",
                "library movies collections",
            ),
            opened,
        )
    }

    @Test
    fun aFilmNotSeenIsPlayedFromItsStart() {
        val played = mutableListOf<String>()
        val home = fullHome(sections = fullHome().content.let { (it as HomeContent.Rows).sections.drop(1) })
        val actions = HomeActions(onPlay = { item, resume -> played += "${item.id} $resume" })
        open(home.copy(doors = home.doors!!.copy(unseen = title("fallguy"))), actions)
        focusIsOn("Not seen yet")
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("fallguy false"), played)
    }

    @Test
    fun fiveLibrariesGatherTheirLastTwoBehindOneDoor() {
        val libraries = (1..5).map { Library("l$it", LibraryKind.MIXED, "Library $it") }
        val shelves = libraries.mapIndexed { i, library -> Shelf(library, 10 * (i + 1), emptyList()) }
        val home = fullHome(doors = HomeDoors(shelves = shelves)).copy(libraries = libraries)
        open(home)
        compose.press(Key.DirectionRight)
        focusIsOn("Library 1")
        compose.press(Key.DirectionRight, times = 3)
        focusIsOn("Libraries")
    }
    /** Menu, or OK held, on the case opens its options: its page first, then from its start once started. */
    @Test
    fun theCasesOptionsOpenItsPage() {
        val opened = mutableListOf<String>()
        open(actions = HomeActions(onOpenItem = { opened += it.id }, onPlay = { item, resume -> opened += "play ${item.id} $resume" }))
        compose.holdOk()
        compose.onNodeWithText("Open its page").assertExists()
        compose.onNodeWithText("Play from the beginning").assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(listOf("heat"), opened)
    }
    /** The case stays one element as what it shows changes: a film proposed, played, then resumed. */
    @Test
    fun focusStaysOnTheCaseAsItChangesKind() {
        val proposed = fullHome(sections = (fullHome().content as HomeContent.Rows).sections.drop(1))
        var home by mutableStateOf(proposed.copy(doors = proposed.doors!!.copy(unseen = title("fallguy"))))
        compose.setTvContent { HomeGrid(home) }
        compose.waitForIdle()
        focusIsOn("Not seen yet")
        home = fullHome()
        compose.waitForIdle()
        focusIsOn("Resume")
    }

    /** A door with focus that goes after a reload leaves focus on the door last visited still there. */
    @Test
    fun aDoorThatGoesLeavesFocusOnOneThatStays() {
        var home by mutableStateOf(fullHome())
        compose.setTvContent { HomeGrid(home) }
        compose.waitForIdle()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionUp)
        focusIsOn("Next up")
        // The next episode watched and nothing else in progress: À suivre goes, Nouveautés takes its row.
        val sections = (fullHome().content as HomeContent.Rows).sections
        home = fullHome(sections = listOf(sections[0].copy(items = sections[0].items.take(1))) + sections.drop(2))
        compose.waitForIdle()
        focusIsOn("Movies")
    }

    /** With nothing in progress, the first focus waits for the doors, which bring the film the case proposes. */
    @Test
    fun theFirstFocusWaitsForTheCase() {
        val proposed = fullHome(sections = (fullHome().content as HomeContent.Rows).sections.drop(1))
        var home by mutableStateOf(proposed.copy(doors = null))
        compose.setTvContent { HomeGrid(home) }
        compose.waitForIdle()
        compose.onNode(isFocused()).assertDoesNotExist()
        home = proposed.copy(doors = proposed.doors!!.copy(unseen = title("fallguy")))
        compose.waitForIdle()
        focusIsOn("Not seen yet")
    }

    /** Without a grid (a message), the settings stay one tile away. */
    @Test
    fun aMessageKeepsTheSettingsATileAway() {
        val opened = mutableListOf<String>()
        open(HomeState(content = HomeContent.ServerEmpty), HomeActions(onOpenSettings = { opened += "settings" }))
        compose.onNodeWithContentDescription("Settings").assertExists()
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("settings"), opened)
    }
}
