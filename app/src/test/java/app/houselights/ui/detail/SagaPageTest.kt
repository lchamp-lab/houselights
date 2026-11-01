package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.design.components.MENU_KEY
import app.houselights.ui.design.components.VoletPlan
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressBack
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.pressOnRemote
import app.houselights.ui.util.UiError
import app.houselights.ui.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * DESIGN.md, Saga page: a saga's polyptych with the remote, as the board's navigation table has it. It opens on the
 * saga's panel, its Lire focused; Up goes to the saga's panel, Left and Right open the panel reached, Down enters the
 * open panel's actions and Up comes back to it, nothing past their ends; Back opens the saga's panel again, focus on its
 * Lire, then leaves; OK opens a film's page, a film the library lacks its synopsis, which has no actions; back from a
 * film's page, its panel is open from the first frame; a screen reader hears each panel by its title, then its state,
 * its actions after it; a box set's panels are its films; a saga read again in vain says why, with Réessayer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class SagaPageTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Loads at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun stop() = work.cancel()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun plural(id: Int, count: Int) = context.resources.getQuantityString(id, count, count)

    private fun page(source: SagaSource = duneSource(), id: String = duneSaga.id) = SagaPage(compose, source, work).also { it.open(id) }

    private fun volet(title: String, number: Int, year: Int) =
        compose.onNodeWithContentDescription(text(R.string.saga_card_name, title, number) + ", $year")

    private val saga get() = compose.onNodeWithContentDescription(text(R.string.saga_volet_saga, duneSaga.title))
    private val first get() = volet(dune.title, 1, 2021)
    private val secondName get() = text(R.string.saga_card_name, duneTwo.title, 2) + ", 2024"
    private val second get() = compose.onNodeWithContentDescription(secondName)
    private val third get() = volet(duneThree.title, 3, 2026)
    private val resume get() = compose.onNode(hasText(text(R.string.resume)) and hasClickAction())
    private val lire get() = compose.onNode(hasText(text(R.string.play)) and hasClickAction())
    private fun tile(id: Int) = compose.onNode(hasContentDescription(text(id)) and hasClickAction())

    private fun SemanticsNodeInteraction.isOpen() = apply {
        assertEquals("open", dunePlan.open.value, getUnclippedBoundsInRoot().width.value, 1f)
    }

    private fun SemanticsNodeInteraction.isClosed() = apply {
        assertEquals("closed", dunePlan.closed.value, getUnclippedBoundsInRoot().width.value, 1f)
    }

    private val left get() = compose.activity.isFinishing

    @Test
    fun theSagaOpensOnItsOwnPanelLireFocused() {
        page()
        resume.assertIsFocused()
        saga.isOpen()
        listOf(first, second, third).forEach { it.isClosed() }
    }

    /** Up from Lire goes to the saga's panel, Down back to its Lire; Right opens film 1, Down enters its actions. */
    @Test
    fun upGoesToTheSagasPanelRightOpensFilmOneDownEntersItsActions() {
        page()
        compose.press(Key.DirectionUp)
        saga.assertIsFocused().isOpen()
        compose.press(Key.DirectionUp)
        saga.assertIsFocused()
        compose.press(Key.DirectionDown)
        resume.assertIsFocused()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        first.assertIsFocused().isOpen()
        saga.isClosed()
        compose.press(Key.DirectionDown)
        // Dune, watched: its own Lire, then its check, then its page.
        lire.assertIsFocused()
        first.isOpen()
        compose.press(Key.DirectionRight)
        tile(R.string.mark_unwatched).assertIsFocused()
        compose.press(Key.DirectionRight)
        tile(R.string.saga_film_page).assertIsFocused()
        compose.press(Key.DirectionRight)
        tile(R.string.saga_film_page).assertIsFocused()
        first.isOpen()
        second.isClosed()
        compose.press(Key.DirectionLeft, times = 3)
        lire.assertIsFocused()
        first.isOpen()
        saga.isClosed()
        compose.press(Key.DirectionDown)
        lire.assertIsFocused()
        compose.press(Key.DirectionUp)
        first.assertIsFocused().isOpen()
    }

    /** The saga's actions: Lire, Depuis le début, the check, Modifier; nothing before the first nor after the last. */
    @Test
    fun theSagasActionsRunFromLireToModifier() {
        page()
        compose.press(Key.DirectionLeft)
        resume.assertIsFocused()
        compose.press(Key.DirectionRight)
        tile(R.string.from_start).assertIsFocused()
        compose.press(Key.DirectionRight)
        tile(R.string.mark_saga_watched).assertIsFocused()
        compose.press(Key.DirectionRight)
        tile(R.string.edit).assertIsFocused()
        compose.press(Key.DirectionRight)
        tile(R.string.edit).assertIsFocused()
        compose.press(Key.DirectionDown)
        tile(R.string.edit).assertIsFocused()
        saga.isOpen()
        compose.press(Key.DirectionUp)
        saga.assertIsFocused()
    }

    @Test
    fun backFromAFilmsActionsOpensTheSagaFocusOnLireThenLeaves() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 2)
        compose.press(Key.DirectionDown)
        second.isOpen()
        compose.pressBack()
        resume.assertIsFocused()
        saga.isOpen()
        second.isClosed()
        assertFalse(left)
        compose.pressBack()
        assertTrue(left)
    }

    @Test
    fun backFromAPanelOpensTheSagaThenLeaves() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 2)
        compose.pressBack()
        resume.assertIsFocused()
        saga.isOpen()
        second.isClosed()
        assertFalse(left)
        compose.pressBack()
        assertTrue(left)
    }

    @Test
    fun backFromTheSagasPanelOrItsCheckGoesToLireThenLeaves() {
        page()
        compose.press(Key.DirectionUp)
        saga.assertIsFocused()
        compose.pressBack()
        resume.assertIsFocused()
        compose.press(Key.DirectionRight, times = 2)
        tile(R.string.mark_saga_watched).assertIsFocused()
        compose.pressBack()
        resume.assertIsFocused()
        assertFalse(left)
        compose.pressBack()
        assertTrue(left)
    }

    /** Back from a film's page, its panel is laid out open from the first frame, never opening, and has focus. */
    @Test
    fun backFromAFilmsPageItsPanelIsOpenWithoutMotion() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 2)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf(duneTwo.id), page.opened)
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { page.shown = true }
        // Frame by frame from the first it is drawn in: open, the saga's closed, nothing moving.
        var frames = 0
        while (compose.onAllNodes(hasContentDescription(secondName)).fetchSemanticsNodes().isEmpty()) {
            check(frames++ < FRAMES) { "Not drawn after $FRAMES frames" }
            compose.mainClock.advanceTimeByFrame()
        }
        repeat(FRAMES) {
            second.isOpen()
            saga.isClosed()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        second.assertIsFocused().isOpen()
    }

    /** Back from a film's page opened by its tile, focus is on that tile, the film open. */
    @Test
    fun aFilmsPageTileOpensItsPageFocusBackOnIt() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionRight, times = 2)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf(dune.id), page.opened)
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.runOnIdle { page.shown = true }
        compose.waitForIdle()
        tile(R.string.saga_film_page).assertIsFocused()
        first.isOpen()
    }

    @Test
    fun okOnAFilmHeldOpensItsPage() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf(dune.id), page.opened)
    }

    /** A film the library lacks has no actions: Down leaves focus on it, OK opens its synopsis. */
    @Test
    fun aFilmTheLibraryLacksHasNoActionsOkOpensItsSynopsis() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 3)
        third.assertIsFocused().isOpen()
        compose.press(Key.DirectionDown)
        third.assertIsFocused()
        compose.press(Key.DirectionRight)
        third.assertIsFocused()
        assertTrue("no Lire", compose.onAllNodes(hasText(text(R.string.play)) and hasClickAction()).fetchSemanticsNodes().isEmpty())
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(duneThree.overview.orEmpty()).assertExists()
        compose.onNodeWithText(text(R.string.saga_release_on, out())).assertExists()
        assertEquals(emptyList<String>(), page.opened)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(duneThree.overview.orEmpty()).assertDoesNotExist()
        third.assertIsFocused().isOpen()
    }

    /** Dune's source, and the fake under it, which says what the page asked of it. */
    private fun duneProvider(): Pair<FakeProvider, SagaSource> {
        val provider = FakeProvider().apply {
            items[duneSaga.id] = duneSaga
            items[dune.id] = dune
            items[duneTwo.id] = duneTwo
            catalog = FakeCatalog().apply { sagas = mapOf(duneSaga.id to duneWhole()) }
            capabilities = capabilities.copy(sagaParts = true)
        }
        return provider to SagaSource(provider, duneSaga, listOf(dune, duneTwo))
    }

    /** What the page marked, film by film. */
    private fun FakeProvider.marked() = calls.filter { it.startsWith("played ") }

    /** A film's check marks that film alone, shown at once; its Lire plays it, from where it was left. */
    @Test
    fun aFilmsActionsMarkAndPlayThatFilmAlone() {
        val (provider, source) = duneProvider()
        val page = page(source)
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 2)
        compose.press(Key.DirectionDown)
        resume.assertIsFocused().assert(hasText(formatDuration(74 * MIN), substring = true))
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("${duneTwo.id} true"), page.played)
        compose.press(Key.DirectionRight, times = 2)
        tile(R.string.mark_watched).assertIsFocused()
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals("film 2 alone", listOf("played ${duneTwo.id} true"), provider.marked())
    }

    /** The saga's check marks the saga, its films held with it. */
    @Test
    fun theSagasCheckMarksTheSaga() {
        val (provider, source) = duneProvider()
        page(source)
        compose.press(Key.DirectionRight, times = 2)
        tile(R.string.mark_saga_watched).assertIsFocused()
        compose.press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(listOf("played ${duneSaga.id} true"), provider.marked())
    }

    /**
     * Each panel is one element, read by its title, its number and year, then its state and what it says open: the
     * saga's as « Dune, saga », its facts and its overview, which OK opens only once cut; a film's its length, what is
     * left of it and its overview; the open panel's actions read after it.
     */
    @Test
    fun theScreenReaderHearsEachPanelByItsTitle() {
        page()
        val films = hasAnyAncestor(hasContentDescription(text(R.string.library_movies)))
        val sagaSays = listOf(
            "2021 – 2024",
            plural(R.plurals.film_count, 2),
            plural(R.plurals.saga_watched, 1),
            text(R.string.time_left, formatDuration(74 * MIN)),
            plural(R.plurals.saga_upcoming_count, 1),
            duneSaga.overview,
        )
        compose.onNode(hasContentDescription(text(R.string.saga_volet_saga, "Dune")) and films)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, sagaSays.joinToString(", ")))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        listOf(
            text(R.string.saga_card_name, dune.title, 1) + ", 2021" to
                listOf(text(R.string.card_watched), formatDuration(155 * MIN), "Paul part pour Arrakis."),
            text(R.string.saga_card_name, duneTwo.title, 2) + ", 2024" to listOf(
                text(R.string.card_in_progress, 55),
                formatDuration(166 * MIN),
                text(R.string.time_left, formatDuration(74 * MIN)),
                "Paul s’allie aux Fremen.",
            ),
            text(R.string.saga_card_name, duneThree.title, 3) + ", 2026" to
                listOf(text(R.string.saga_card_release, out()), duneThree.overview),
        ).forEach { (name, says) ->
            compose.onNode(hasContentDescription(name) and films)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, says.joinToString(", ")))
                .assert(hasClickAction())
        }
        // Its words are the panel's: read once, as it; its actions after it.
        assertTrue(compose.onAllNodesWithText(duneSaga.overview.orEmpty()).fetchSemanticsNodes().isEmpty())
        val order = readingOrder()
        val panel = order.indexOfFirst {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == text(R.string.saga_volet_saga, "Dune")
        }
        val reprendre = order.indexOfFirst { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text(R.string.resume) } == true
        }
        assertTrue("the panel, then its actions: $panel, $reprendre", panel in 0 until reprendre)
    }

    /** A panel OK does nothing on, a film the library lacks without an overview, offers no action to a screen reader. */
    @Test
    fun aPanelOkCannotOpenIsNoButton() {
        page(duneSource(third = duneThree.copy(overview = null)))
        third.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.saga_card_release, out())))
    }

    /** Left and back with focus in a film's actions: they have it again, the film open. */
    @Test
    fun backOnThePageFocusIsInTheFilmsActionsLeft() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 2)
        compose.press(Key.DirectionDown)
        compose.press(Key.DirectionRight)
        tile(R.string.from_start).assertIsFocused()
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.runOnIdle { page.shown = true }
        compose.waitForIdle()
        tile(R.string.from_start).assertIsFocused()
        second.isOpen()
        saga.isClosed()
    }

    /** A scan bringing the film the library lacked while its panel is open and focused: it stays so, in colour. */
    @Test
    fun aFilmTheLibraryGainsKeepsItsPanelOpenAndFocused() {
        val source = duneSource()
        val page = page(source)
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight, times = 3)
        third.assertIsFocused().isOpen()
        val held = MediaItem("dune3", MediaKind.MOVIE, duneThree.title, year = 2026, runtimeMs = 150 * MIN)
        (source.catalog as FakeCatalog).sagas = mapOf(duneSaga.id to duneWhole(third = duneThree.copy(item = held, released = true)))
        compose.runOnIdle { page.vm.load() }
        compose.waitForIdle()
        third.assertIsFocused().isOpen()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    listOf(formatDuration(150 * MIN), duneThree.overview).joinToString(", "),
                ),
            )
        second.isClosed()
    }

    /**
     * A step along a long saga recomposes the panels it concerns, the one left, the one reached and the spine saying
     * where focus is, never the others: a panel's body runs fewer times in a step than the saga has panels.
     */
    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun aStepAlongRecomposesThePanelsItConcernsAlone() {
        page(bondSource(), BOND_ID)
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(HlMotion.SETTLE + HlMotion.HOLD + HlMotion.TINT)
        compose.waitForIdle()
        val panels = AtomicInteger()
        Composer.setTracer(
            object : CompositionTracer {
                override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
                    if (info.startsWith(VOLET_BODY)) panels.incrementAndGet()
                }

                override fun traceEventEnd() = Unit

                override fun isTraceInProgress() = true
            },
        )
        try {
            compose.press(Key.DirectionRight)
            compose.mainClock.advanceTimeBy(HlMotion.SETTLE + HlMotion.HOLD + HlMotion.TINT)
            compose.waitForIdle()
        } finally {
            Composer.setTracer(NoTrace)
        }
        assertTrue("the panels' bodies ran: ${panels.get()}", panels.get() > 0)
        assertTrue("${panels.get()} runs of a panel's body for ${BOND + 1} panels", panels.get() < BOND + 1)
    }

    /** The line over Reprendre names the film it plays, Reprendre says what is left of it, beside Depuis le début; OK resumes it. */
    @Test
    fun theLineOverReprendreNamesTheFilmItPlays() {
        val page = page()
        compose.onNodeWithText(text(R.string.section_next_up)).assertExists()
        compose.onNodeWithText(duneTwo.title).assertExists()
        resume.assert(hasText(formatDuration(74 * MIN), substring = true))
        compose.onNodeWithContentDescription(text(R.string.from_start)).assertExists()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("${duneTwo.id} true"), page.played)
    }

    /** Once a film is watched, the next one not started is « à suivre », named over Lire, which says its length. */
    @Test
    fun lireSaysTheLengthOfTheFilmToFollow() {
        val next = duneTwo.copy(user = UserState())
        val page = page(duneSource(second = next))
        lire.assertIsFocused().assert(hasText(formatDuration(166 * MIN), substring = true))
        compose.onNodeWithText(next.title).assertExists()
        compose.onNodeWithContentDescription(text(R.string.from_start)).assertDoesNotExist()
        second.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                listOf(text(R.string.saga_card_next), formatDuration(166 * MIN), "Paul s’allie aux Fremen.").joinToString(", "),
            ),
        )
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("${next.id} false"), page.played)
    }

    /** A box set (a source that does not know the saga): its panels are the films it holds, in the server's order. */
    @Test
    fun aBoxSetsPanelsAreTheFilmsItHolds() {
        val box = MediaItem("wick", MediaKind.COLLECTION, "John Wick", backdrop = picture("wick"))
        val films = listOf("John Wick" to 2014, "John Wick 2" to 2017, "John Wick : Parabellum" to 2019, "John Wick 4" to 2023)
            .mapIndexed { i, (title, year) -> film("wick$i", title).copy(year = year, runtimeMs = 130 * MIN) }
        val provider = FakeProvider().apply {
            items[box.id] = box
            films.forEach { items[it.id] = it }
        }
        page(SagaSource(provider, box, films), box.id)
        lire.assertIsFocused()
        compose.onNodeWithText("John Wick").assertExists()
        assertEquals(
            "open",
            437f,
            compose.onNodeWithContentDescription(text(R.string.saga_volet_saga, box.title)).getUnclippedBoundsInRoot().width.value,
            1f,
        )
        films.forEachIndexed { i, film ->
            compose.onNodeWithContentDescription(text(R.string.saga_card_name, film.title, i + 1) + ", ${film.year}")
                // No state: nothing watched, none missing; its length alone.
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, formatDuration(130 * MIN)))
        }
    }

    /**
     * Read again in vain, the saga keeps its panels and says why over its actions, Réessayer after them; OK there puts
     * focus on Lire, where it stays once the saga is read, the saga's panel open.
     */
    @Test
    fun aSagaReadAgainInVainSaysWhyWithRetry() {
        val source = duneSource()
        val page = page(source)
        source.away = true
        compose.runOnIdle { page.vm.load() }
        compose.waitForIdle()
        compose.onNodeWithText(text(UiError.OFFLINE.title)).assertExists()
        compose.onNodeWithText(text(R.string.section_next_up)).assertDoesNotExist()
        val retry = compose.onNode(hasText(text(R.string.retry)) and hasClickAction())
        compose.press(Key.DirectionRight, times = 4)
        retry.assertIsFocused()
        compose.press(Key.DirectionRight)
        retry.assertIsFocused()
        source.away = false
        compose.press(Key.DirectionCenter)
        retry.assertDoesNotExist()
        resume.assertIsFocused()
        saga.isOpen()
    }

    /** The Menu key does nothing on the page: no sheet, from Lire as from a panel. */
    @Test
    fun theMenuKeyDoesNothing() {
        page()
        compose.pressOnRemote(Key.Menu)
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        resume.assertIsFocused()
        compose.press(Key.DirectionUp)
        compose.pressOnRemote(Key.Menu)
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        saga.assertIsFocused()
        assertTrue("no Menu key", compose.onAllNodes(hasTestTag(MENU_KEY), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private val bond = MediaItem(BOND_ID, MediaKind.COLLECTION, "James Bond", backdrop = picture("bond"))
    private val bondFilms = (1..BOND).map { film("bond$it", "Bond $it").copy(year = 1960 + it * 2, runtimeMs = 120 * MIN) }

    /** James Bond, a box set of 25 films. */
    private fun bondSource(): SagaSource {
        val provider = FakeProvider().apply {
            items[bond.id] = bond
            bondFilms.forEach { items[it.id] = it }
        }
        return SagaSource(provider, bond, bondFilms)
    }

    /**
     * From five films the films scroll, the open one two closed panels after the saga's spine, pinned at the start, a
     * closed panel whole after it, but at the ends.
     */
    @Test
    fun aLongSagaScrollsTheOpenPanelKeptInView() {
        val films = bondFilms
        page(bondSource(), bond.id)
        val plan = VoletPlan.of(BOND + 1, HlLayout.CANVAS_WIDTH.dp - HlLayout.voletSide * 2, 473.dp)
        val step = plan.closed + plan.gap
        val start = HlLayout.voletSide
        val end = HlLayout.CANVAS_WIDTH.dp - HlLayout.voletSide
        compose.press(Key.DirectionUp)
        val spine = compose.onNodeWithContentDescription(text(R.string.saga_volet_saga, bond.title))
        films.forEachIndexed { i, film ->
            compose.press(Key.DirectionRight)
            val panel = compose.onNodeWithContentDescription(text(R.string.saga_card_name, film.title, i + 1) + ", ${film.year}")
                .assertIsFocused()
                .getUnclippedBoundsInRoot()
            assertEquals("${film.title} open", plan.open.value, panel.width.value, 1f)
            assertEquals("${film.title}: the spine pinned", start.value, spine.getUnclippedBoundsInRoot().left.value, HALF)
            if (i + 1 >= 3) assertTrue("${film.title}: ${panel.left}", panel.left >= start + step * 3 - HALF.dp)
            if (i + 1 < BOND) assertTrue("${film.title}: ${panel.right}", panel.right + step <= end + HALF.dp)
        }
    }

    /** The panels run from the safe area's top to its foot at every text size, a frame's reach inside it; Lire over its label room. */
    @Test
    fun thePanelsStandOnTheSafeArea() {
        val page = page()
        listOf(0.85f, 1f, 1.3f).forEach { step ->
            compose.runOnIdle { page.step = step }
            compose.waitForIdle()
            val panel = saga.getUnclippedBoundsInRoot()
            val reprendre = resume.getUnclippedBoundsInRoot()
            assertEquals("at $step: the polyptych's top", HlLayout.voletTop.value, panel.top.value, HALF)
            assertEquals("at $step: the polyptych's foot", (SAFE_FOOT - HlFocus.reach).value, panel.bottom.value, HALF)
            assertTrue("at $step: ${panel.left}", (panel.left - HlFocus.reach).value >= HlSpace.safeH.value - HALF)
            assertEquals(
                "at $step: Lire over the label room",
                (panel.bottom - VoletDefaults.actionsFoot - page.labelRoom).value,
                reprendre.bottom.value,
                HALF,
            )
            assertEquals("at $step: Lire at the words' start", (panel.left + HlInset.card).value, reprendre.left.value, HALF)
        }
    }

    /** The page's semantics nodes in reading order: the tree walked depth first, as a screen reader goes. */
    private fun readingOrder(): List<SemanticsNode> {
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        walk(compose.onRoot().fetchSemanticsNode())
        return nodes
    }

    /** The release date in full, as the page writes it in the viewer's language. */
    private fun out() = longDate(duneThreeOut, context.resources.configuration.locales[0])

    private companion object {
        /** Frames watched coming back: past the opening's 220 ms. */
        const val FRAMES = 20

        const val HALF = 0.5f

        /** James Bond's films: past eight, the panels scroll. */
        const val BOND = 25
        const val BOND_ID = "bond"

        /** What the compiler names a panel's body by as it traces it. */
        const val VOLET_BODY = "app.houselights.ui.design.components.Volet ("

        /** Tracing off again, as before the test. */
        @OptIn(InternalComposeTracingApi::class)
        val NoTrace = object : CompositionTracer {
            override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit

            override fun traceEventEnd() = Unit

            override fun isTraceInProgress() = false
        }

        /** The safe area's foot: 512 on the canvas, the polyptych's 506.5. */
        val SAFE_FOOT = 540.dp - HlSpace.safeV
    }
}
