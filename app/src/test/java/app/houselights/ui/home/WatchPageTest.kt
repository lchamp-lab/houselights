package app.houselights.ui.home

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.HomeSection
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.SectionType
import app.houselights.ui.library.NotSaved
import app.houselights.ui.library.PosterActions
import app.houselights.ui.library.markedAs
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.pressOnRemote
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.UiError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** « À regarder »: home's rows under their title, opened on the row its door shows. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class WatchPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun open(row: WatchRow, onOpen: (MediaItem) -> Unit = {}) {
        compose.setTvContent { WatchBands(fullHome(), row, onOpen) }
        compose.waitForIdle()
    }

    private fun focusIsOn(text: String) = compose.onNode(isFocused()).assert(hasText(text, substring = true))

    @Test
    fun itIsTitledAsAPlace() {
        open(WatchRow.CONTINUE)
        compose.onNodeWithText("To watch").assertIsDisplayed()
    }

    @Test
    fun theNextEpisodesDoorOpensItOnItsRow() {
        open(WatchRow.NEXT_UP)
        focusIsOn("MobLand")
    }

    @Test
    fun theNewsDoorOpensItOnTheLatestTitles() {
        open(WatchRow.LATEST)
        focusIsOn("Film1")
    }

    @Test
    fun theSecondTitleInProgressOpensItOnReprendre() {
        open(WatchRow.CONTINUE)
        focusIsOn("Heat")
    }

    @Test
    fun itsRowsAreHomesWithTheirCards() {
        val opened = mutableListOf<String>()
        open(WatchRow.LATEST) { opened += it.id }
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("film2"), opened)
        compose.press(Key.DirectionDown)
        focusIsOn("Show")
    }

    /** « À regarder » with its cards' options, a mark shown as home's view model shows it ([showing]), and what it said. */
    private var home by mutableStateOf(fullHome())
    private var notSaved by mutableStateOf<NotSaved?>(null)
    private val marked = mutableListOf<String>()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun withOptions(row: WatchRow) {
        val actions = PosterActions(
            onMark = { item ->
                marked += item.id
                home = home.copy(content = HomeContent.Rows(rows().showing(item.markedAs(!item.user.played))))
            },
            onFavorite = null,
            canEdit = { false },
            onEditArtwork = { _, _ -> },
            onIdentify = {},
        )
        compose.setTvContent { WatchBands(home, row, {}, WatchCards(actions), notSaved) }
        compose.waitForIdle()
    }

    private fun rows() = (home.content as HomeContent.Rows).sections

    /** Menu then OK on the card with focus: its options open on its mark. */
    private fun markIt() {
        compose.pressOnRemote(Key.Menu)
        compose.onNode(hasText(context.getString(R.string.mark_watched)) and hasClickAction()).assertIsFocused()
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** A film of Reprendre marked watched from its still leaves the row at once, the still after it taking focus. */
    @Test
    fun aStillOfReprendreMarkedLeavesItsRowFocusOnTheNext() {
        withOptions(WatchRow.CONTINUE)
        focusIsOn("Heat")
        markIt()
        assertEquals(listOf("heat"), marked)
        compose.onNode(hasText("Heat") and hasClickAction()).assertDoesNotExist()
        focusIsOn("Alien")
    }

    /** A row its last card leaves goes, focus on the row under it. */
    @Test
    fun aRowEmptiedGoesFocusOnTheRowUnderIt() {
        home = fullHome(sections = listOf(rowOf(SectionType.CONTINUE, resuming.first()), rowOf(SectionType.NEXT_UP, nextEpisode)))
        withOptions(WatchRow.CONTINUE)
        markIt()
        focusIsOn("MobLand")
    }

    /** An episode of À suivre marked keeps its place until the rows read again, when the next takes it, and focus. */
    @Test
    fun theNextEpisodeTakesTheMarkedOnesPlaceAndFocus() {
        val other = MediaItem("severance-s2e1", MediaKind.EPISODE, "Hello, Ms. Cobel", seriesTitle = "Severance")
        home = fullHome(sections = listOf(rowOf(SectionType.NEXT_UP, nextEpisode, other)))
        withOptions(WatchRow.NEXT_UP)
        markIt()
        focusIsOn("MobLand")
        val after = nextEpisode.copy(id = "mobland-s1e5", episodeNumber = 5)
        home = fullHome(sections = listOf(rowOf(SectionType.NEXT_UP, after, other)))
        compose.waitForIdle()
        // The one MobLand card now is the next episode's, focused.
        compose.onAllNodes(hasText("MobLand") and hasClickAction()).assertCountEquals(1)
        focusIsOn("MobLand")
    }

    /** A change not saved is said in the hero, in place of its facts, its card focused again. */
    @Test
    fun aChangeNotSavedIsSaidInTheHero() {
        withOptions(WatchRow.LATEST)
        compose.press(Key.DirectionRight)
        focusIsOn("Film2")
        notSaved = NotSaved("film2", UiError.OFFLINE)
        compose.waitForIdle()
        compose.onNode(hasText(context.getString(R.string.action_not_saved), substring = true)).assertExists()
        focusIsOn("Film2")
    }

    /** Without the source keeping what is watched, a card has no options. */
    @Test
    fun withoutActionsACardHasNoOptions() {
        open(WatchRow.CONTINUE)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).assertCountEquals(0)
    }

    private fun rowOf(type: SectionType, vararg items: MediaItem) = HomeSection(type, items.toList(), landscape = true)

    @Test
    fun aRowItLacksOpensItOnItsFirst() {
        assertEquals(0, sectionOf(emptyList(), WatchRow.NEXT_UP))
        val rows = (fullHome().content as HomeContent.Rows).sections
        assertEquals(2, sectionOf(rows, WatchRow.LATEST))
        assertEquals(0, sectionOf(rows.drop(1).filterNot { it.type == app.houselights.core.SectionType.NEXT_UP }, WatchRow.NEXT_UP))
    }
}
