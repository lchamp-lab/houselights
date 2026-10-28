package app.houselights.ui.detail

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.NotFoundException
import app.houselights.core.UserState
import app.houselights.testing.http
import app.houselights.ui.design.HlMotion
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.episode
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.season
import app.houselights.ui.testing.series
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.ErrorAction
import app.houselights.ui.util.LibraryPlace
import app.houselights.ui.util.LocalErrorFix
import app.houselights.ui.util.LocalLibraryPlace
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

/**
 * A title's page says what failed where it happened: a play where it was asked, a part under the title, with Réessayer;
 * what Lire starts failing with the seasons or their episodes, once, in Lire's place.
 */
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

    /** Each fix an error's button led to. */
    private val fixed = mutableListOf<ErrorAction>()

    /** Where the library lies, as the app provides it from its source; not known unless a test says. */
    private var place: LibraryPlace? = null

    @After
    fun stop() = work.cancel()

    private fun page(id: String): DetailViewModel {
        val vm = DetailViewModel(id, { provider }, FakeEvents(), work, tracksOf = { null })
        compose.setTvContent {
            CompositionLocalProvider(LocalErrorFix provides { fixed += it }, LocalLibraryPlace provides place) {
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
        }
        compose.waitForIdle()
        return vm
    }

    private fun text(id: Int) = context.getString(id)

    private fun said(id: Int) = compose.onNodeWithText(text(id), useUnmergedTree = true)

    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())

    /** How many times [id] is said on the page. */
    private fun times(id: Int) = compose.onAllNodesWithText(text(id), useUnmergedTree = true).fetchSemanticsNodes().size

    /** How many buttons say [id]. */
    private fun buttons(id: Int) = compose.onAllNodes(hasText(text(id)) and hasClickAction()).fetchSemanticsNodes().size

    /** The show's page as Lire's episode and then the seasons fail, each with [lire] and [seasons]. */
    private fun bothFail(lire: Throwable, seasons: Throwable = lire): DetailViewModel {
        val read = provider.gate("seasons show")
        provider.fail = { if (it == "nextUp show") lire else null }
        val vm = page("show")
        provider.fail = {
            when (it) {
                "nextUp show" -> lire
                "seasons show" -> seasons
                else -> null
            }
        }
        read.complete(Unit)
        compose.waitForIdle()
        provider.gates.clear()
        return vm
    }

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

    /**
     * A server's title names the server out of reach wherever its page says it: what Lire starts, at the row's end, and
     * the seasons failing once the title shows, under it.
     */
    @Test
    fun aServerOutOfReachIsTheServerOnItsTitlesPage() {
        place = LibraryPlace.SERVER
        bothFail(IOException("Server away"))
        said(R.string.error_offline_server_title).assertIsDisplayed()
        assertEquals(0, times(R.string.error_offline_title))

        // Lire read again alone: the seasons' failure goes under the title, its Réessayer a press below.
        provider.fail = { if (it == "seasons show") IOException("Server away") else null }
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        button(R.string.play).assertIsFocused()
        assertEquals(1, times(R.string.error_offline_server_title))
        assertEquals(0, times(R.string.error_offline_title))
        compose.press(Key.DirectionDown)
        button(R.string.retry).assertIsFocused()
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

    /** Lire's episode and the seasons failing together: one message at the actions' end, one Réessayer in Lire's place. */
    @Test
    fun lireAndTheSeasonsFailingTogetherAreSaidOnce() {
        bothFail(IOException("Server away"))
        assertEquals(1, times(R.string.error_offline_title))
        assertEquals(1, buttons(R.string.retry))
        button(R.string.retry).assertIsFocused()
    }

    /** That Réessayer reloads all that failed, busy meanwhile; read, Lire takes its place under focus, the seasons below. */
    @Test
    fun retryInLiresPlaceReloadsEverything() {
        bothFail(IOException("Server away"))
        provider.fail = { null }
        val answer = provider.gate("nextUp show")
        compose.press(Key.DirectionCenter)
        said(R.string.retrying).assertIsDisplayed()
        answer.complete(Unit)
        compose.waitForIdle()
        button(R.string.play).assertIsFocused()
        assertEquals(0, times(R.string.error_offline_title))
        compose.onNode(isSelected() and hasText("Season 1", substring = true)).assertIsDisplayed()
    }

    /** Still failing, the same message comes back and focus stays on the button. */
    @Test
    fun failingAgainSaysTheSameOnce() {
        bothFail(IOException("Server away"))
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, times(R.string.error_offline_title))
        button(R.string.retry).assertIsFocused()
    }

    /** Lire's episode read again but not the seasons: Lire comes, and the seasons' failure goes under the title. */
    @Test
    fun lireReadAloneLeavesTheSeasonsFailureUnderTheTitle() {
        bothFail(IOException("Server away"))
        provider.fail = { if (it == "seasons show") IOException("Server away") else null }
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        button(R.string.play).assertIsFocused()
        assertEquals(1, times(R.string.error_offline_title))
        compose.press(Key.DirectionDown)
        button(R.string.retry).assertIsFocused()
    }

    /** Two reasons: the seasons' is said, and its fix is the button's. */
    @Test
    fun twoReasonsSayTheSeasons() {
        bothFail(http(401), IOException("Server away"))
        assertEquals(1, times(R.string.error_offline_title))
        assertEquals(0, times(R.string.error_denied_title))
        button(R.string.retry).assertIsFocused()
    }

    /** The server refused the app: Se reconnecter in Lire's place, leading to the sign-in. */
    @Test
    fun aRefusalTogetherSignsInAgain() {
        bothFail(http(401))
        assertEquals(1, times(R.string.error_denied_title))
        assertEquals(0, buttons(R.string.retry))
        button(R.string.error_action_reconnect).assertIsFocused()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf(ErrorAction.RECONNECT), fixed)
    }

    /** Nothing fixes it: the message alone, no button, focus on the first action. */
    @Test
    fun withoutAFixThereIsNoButtonAndFocusIsOnTheFirstAction() {
        bothFail(NotFoundException("show"))
        assertEquals(1, times(R.string.error_gone_title))
        assertEquals(0, buttons(R.string.retry))
        compose.onNode(hasContentDescription(text(R.string.mark_watched))).assertIsFocused()
    }

    /** Lire's episode and a season's episodes failing together: once, in Lire's place; the row keeps its title alone. */
    @Test
    fun lireAndTheEpisodesFailingTogetherAreSaidOnce() {
        provider.seasons = mapOf("show" to listOf(season("s1", 1, "show").copy(childCount = 2)))
        provider.fail = { if (it == "nextUp show" || it == "episodes s1") IOException("Server away") else null }
        page("show")
        assertEquals(1, times(R.string.error_offline_title))
        assertEquals(1, buttons(R.string.retry))
        button(R.string.retry).assertIsFocused()
        compose.onNodeWithText("Season 1", useUnmergedTree = true).assertIsDisplayed()

        provider.fail = { null }
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        button(R.string.play).assertIsFocused()
        compose.onNode(hasText("s1e1") and hasClickAction()).assertIsDisplayed()
    }

    /** Lire's episode answering before the seasons: Réessayer stays busy in its place, nothing said under the title. */
    @Test
    fun retryStaysBusyUntilAllOfTheReloadAnswers() {
        bothFail(IOException("Server away"))
        provider.fail = { null }
        val seasons = provider.gate("seasons show")
        compose.press(Key.DirectionCenter)
        button(R.string.play).assertDoesNotExist()
        said(R.string.retrying).assertIsDisplayed()
        assertEquals(1, times(R.string.error_offline_title))

        seasons.complete(Unit)
        compose.waitForIdle()
        button(R.string.play).assertIsFocused()
        assertEquals(0, times(R.string.error_offline_title))
    }

    /** A season chosen after Lire's episode failed whose episodes fail too: a failure of its own, said on its row. */
    @Test
    fun episodesFailingAfterLireAreSaidOnTheirRow() {
        provider.fail = { if (it == "nextUp show") IOException("Server away") else null }
        page("show")
        provider.fail = { if (it == "nextUp show" || it == "episodes s2") IOException("Server away") else null }
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionRight)
        compose.onNode(isSelected() and hasText("Season 2", substring = true)).assertIsFocused()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HlMotion.SETTLE + 1))
        compose.waitForIdle()

        compose.press(Key.DirectionDown)
        compose.onNode(hasText(text(R.string.retry)) and hasClickAction() and isFocused()).assertExists()
    }

    /** Alone, what Lire starts refused by the server puts Se reconnecter in its place, as its message says. */
    @Test
    fun aNextEpisodeRefusedPutsSignInOnLire() {
        provider.fail = { if (it == "nextUp show") http(401) else null }
        page("show")
        button(R.string.error_action_reconnect).assertIsFocused()
        said(R.string.error_denied_title).assertIsDisplayed()
    }

    private companion object {
        const val PAGE = "page"
    }
}
