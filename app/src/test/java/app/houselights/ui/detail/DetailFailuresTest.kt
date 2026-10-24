package app.houselights.ui.detail

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.UserState
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlayOrigin
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.ui.util.PlaybackProblem
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** A title's page says what failed where it happened: a play where it was asked, a part under the title, with Réessayer. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailFailuresTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playback = PlaybackOutcome(MutableSharedFlow())

    private val provider = FakeProvider().apply {
        items["m1"] = film("m1", "Heat")
        items["show"] = series("show").copy(user = UserState(unplayedCount = 2))
        seasons = mapOf("show" to (1..2).map { season("s$it", it, "show").copy(childCount = 2) })
        episodes = (1..2).associate { n -> "s$n" to (1..2).map { episode("s${n}e$it", "s$n", it) } }
        nextUps = mapOf("show" to "s1e1")
    }

    /** Each play asked, and where from. */
    private val asked = mutableListOf<Pair<String, PlayOrigin>>()

    @After
    fun stop() = work.cancel()

    private fun page(id: String): DetailViewModel {
        val vm = DetailViewModel(id, { provider }, FakeEvents(), work, tracksOf = { null })
        compose.setTvContent {
            DetailPage(
                vm,
                playback,
                screen = PAGE,
                onPlay = { item, _, origin -> asked += item.id to origin },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
        return vm
    }

    private fun text(id: Int) = context.getString(id)

    private fun said(id: Int) = compose.onNodeWithText(text(id), useUnmergedTree = true)

    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())

    @Test
    fun aPlayAskedFromTheActionsIsSaidBesideThem() {
        page("m1")
        button(R.string.play).assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("m1" to PlayOrigin(PAGE)), asked)

        playback.reportProblem(PlaybackProblem(UiError.NO_PLAYER, PlayOrigin(PAGE)))
        compose.waitForIdle()
        said(R.string.play_failed).assertIsDisplayed()
        said(R.string.no_player).assertIsDisplayed()
    }

    /** Asked from another screen (home, another title), a problem never shows here. */
    @Test
    fun aPlayAskedElsewhereIsNotSaidHere() {
        page("m1")
        playback.reportProblem(PlaybackProblem(UiError.NO_PLAYER, PlayOrigin("home")))
        compose.waitForIdle()
        said(R.string.play_failed).assertDoesNotExist()
    }

    /** Asked from an episode's card, deep in the page, it is said on its row, in view, not in the hero above. */
    @Test
    fun aPlayAskedFromAnEpisodeIsSaidOnItsRow() {
        page("show")
        compose.press(Key.DirectionDown, times = 2)
        compose.onNode(hasText("s1e1") and hasClickAction()).assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("s1e1" to PlayOrigin(PAGE, "s1e1")), asked)

        playback.reportProblem(PlaybackProblem(UiError.OFFLINE, PlayOrigin(PAGE, "s1e1")))
        compose.waitForIdle()
        said(R.string.play_failed).assertIsDisplayed()
        said(R.string.error_offline).assertIsDisplayed()
    }

    /** The seasons failing once the title shows: said under it, its Réessayer a press below the actions. */
    @Test
    fun aPartThatFailedIsSaidUnderTheTitleWithRetry() {
        // The title shows once Lire's episode is read; the seasons fail after.
        val seasons = provider.gate("seasons show")
        page("show")
        provider.fail = { if (it == "seasons show") IOException("Server away") else null }
        seasons.complete(Unit)
        compose.waitForIdle()
        said(R.string.error_offline_title).assertIsDisplayed()
        compose.press(Key.DirectionDown)
        button(R.string.retry).assertIsFocused()

        provider.fail = { null }
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        said(R.string.error_offline_title).assertDoesNotExist()
        // Réessayer gone with the failure, focus goes back to Lire; the seasons came.
        button(R.string.play).assertIsFocused()
        compose.press(Key.DirectionDown, times = 2)
        compose.onNode(hasText("s1e1") and hasClickAction()).assertIsFocused()
    }

    /** What Lire starts unread: Réessayer on its button, why at the row's end; read, the same button plays it. */
    @Test
    fun aNextEpisodeUnreadPutsRetryOnLire() {
        provider.fail = { if (it == "nextUp show") IOException("Server away") else null }
        page("show")
        button(R.string.retry).assertIsFocused()
        said(R.string.error_offline_title).assertIsDisplayed()

        provider.fail = { null }
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        button(R.string.play).assertIsFocused()
        said(R.string.error_offline_title).assertDoesNotExist()
    }

    /** Lire focused as a reload cannot read the next episode: focus goes to the Réessayer in its place, never lost. */
    @Test
    fun lireFocusedGoesToTheRetryInItsPlace() {
        val vm = page("show")
        button(R.string.play).assertIsFocused()

        provider.fail = { if (it == "nextUp show") IOException("Server away") else null }
        vm.onLibraryChanged()
        compose.waitForIdle()
        button(R.string.retry).assertIsFocused()
    }

    /** Focus moved on while Réessayer reads, to the season tabs: Lire, read, leaves it there. */
    @Test
    fun focusMovedOnWhileRetryingStaysWhereItWent() {
        provider.fail = { if (it == "nextUp show") IOException("Server away") else null }
        page("show")
        button(R.string.retry).assertIsFocused()

        provider.fail = { null }
        val answer = provider.gate("nextUp show")
        compose.press(Key.DirectionCenter)
        compose.press(Key.DirectionDown)
        val tab = compose.onNode(isSelected() and hasText("Season 1", substring = true))
        tab.assertIsFocused()
        answer.complete(Unit)
        compose.waitForIdle()
        button(R.string.play).assertIsDisplayed()
        tab.assertIsFocused()
    }

    private companion object {
        const val PAGE = "page"
    }
}
