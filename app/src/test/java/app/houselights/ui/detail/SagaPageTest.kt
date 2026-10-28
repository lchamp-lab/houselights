package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.VoletDefaults
import app.houselights.ui.design.components.VoletPlan
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
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
 * DESIGN.md, Saga page: a saga's polyptych with the remote. It opens on the saga's panel, Lire focused; Up enters on
 * the film Lire plays, then on the panel left; Down keeps the film open, a grey one too; Back opens the saga's panel
 * again, then leaves; OK opens a film's page, a film the library lacks its synopsis; back from a film's page, its panel
 * is open from the first frame; a screen reader hears each panel by its title, then its state; a box set's panels are
 * its films; a saga read again in vain says why, with Réessayer.
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

    private fun SemanticsNodeInteraction.isOpen() = apply {
        assertEquals("open", VoletDefaults.open.value, getUnclippedBoundsInRoot().width.value, 1f)
    }

    private fun SemanticsNodeInteraction.isClosed() = apply {
        assertEquals("closed", dunePlan.closed.value, getUnclippedBoundsInRoot().width.value, 1f)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val left get() = compose.activity.isFinishing

    @Test
    fun theSagaOpensOnItsOwnPanelLireFocused() {
        page()
        resume.assertIsFocused()
        saga.isOpen()
        listOf(first, second, third).forEach { it.isClosed() }
    }

    @Test
    fun upEntersOnTheFilmLirePlaysThenOnThePanelLeft() {
        page()
        compose.press(Key.DirectionUp)
        second.assertIsFocused().isOpen()
        saga.isClosed()
        compose.press(Key.DirectionLeft)
        first.assertIsFocused().isOpen()
        second.isClosed()
        compose.press(Key.DirectionDown)
        resume.assertIsFocused()
        compose.press(Key.DirectionUp)
        first.assertIsFocused().isOpen()
    }

    /** « Greffe 1 »: down to Lire, the film stays open, one the library lacks too, and Lire still names its own. */
    @Test
    fun downKeepsTheFilmOpenAGreyOneToo() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionDown)
        resume.assertIsFocused()
        second.isOpen()
        saga.isClosed()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        third.assertIsFocused().isOpen()
        compose.press(Key.DirectionDown)
        resume.assertIsFocused().assert(hasText(duneTwo.title, substring = true))
        third.isOpen()
        second.isClosed()
    }

    @Test
    fun backFromAPanelOpensTheSagaThenLeaves() {
        page()
        compose.press(Key.DirectionUp)
        back()
        resume.assertIsFocused()
        saga.isOpen()
        second.isClosed()
        assertFalse(left)
        back()
        assertTrue(left)
    }

    @Test
    fun backFromLireUnderAFilmOpenOpensTheSagaThenLeaves() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionDown)
        second.isOpen()
        back()
        resume.assertIsFocused()
        saga.isOpen()
        second.isClosed()
        assertFalse(left)
        back()
        assertTrue(left)
    }

    @Test
    fun backFromTheSagasOwnPanelGoesToLireThenLeaves() {
        page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionLeft, times = 2)
        saga.assertIsFocused().isOpen()
        back()
        resume.assertIsFocused()
        assertFalse(left)
        back()
        assertTrue(left)
    }

    /** Back from a film's page, its panel is laid out open from the first frame, never opening, and has focus. */
    @Test
    fun backFromAFilmsPageItsPanelIsOpenWithoutMotion() {
        val page = page()
        compose.press(Key.DirectionUp)
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

    @Test
    fun okOnAFilmHeldOpensItsPage() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionLeft)
        compose.press(Key.DirectionCenter)
        assertEquals(listOf(dune.id), page.opened)
    }

    @Test
    fun okOnAFilmTheLibraryLacksOpensItsSynopsis() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionCenter)
        compose.onNodeWithText(duneThree.overview.orEmpty()).assertExists()
        compose.onNodeWithText(text(R.string.saga_release_on, out())).assertExists()
        assertEquals(emptyList<String>(), page.opened)
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(duneThree.overview.orEmpty()).assertDoesNotExist()
        third.assertIsFocused().isOpen()
    }

    /**
     * Each panel is one element, read by its title, its number and year, then its state and what it says open: the
     * saga's as « Dune, saga », its facts, its progress and its overview, which OK opens only once cut; a film's its
     * length, what is left of it and its overview.
     */
    @Test
    fun theScreenReaderHearsEachPanelByItsTitle() {
        page()
        val films = hasAnyAncestor(hasContentDescription(text(R.string.library_movies)))
        val sagaSays = listOf(
            "2021 – 2024",
            plural(R.plurals.film_count, 2),
            formatDuration((155 + 166) * MIN),
            plural(R.plurals.saga_upcoming_count, 1),
            context.resources.getQuantityString(R.plurals.saga_watched_of, 1, 1, 2),
            text(R.string.time_left, formatDuration(74 * MIN)),
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
    }

    /** A panel OK does nothing on, a film the library lacks without an overview, offers no action to a screen reader. */
    @Test
    fun aPanelOkCannotOpenIsNoButton() {
        page(duneSource(third = duneThree.copy(overview = null)))
        third.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.saga_card_release, out())))
    }

    /** Left and back with a film open, its frame gone to Lire, Up enters on it: nothing opens or closes but the frame. */
    @Test
    fun upAfterThePageWasLeftEntersOnThePanelStillOpen() {
        val page = page()
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
        compose.press(Key.DirectionDown)
        resume.assertIsFocused()
        compose.runOnIdle { page.shown = false }
        compose.waitForIdle()
        compose.runOnIdle { page.shown = true }
        compose.waitForIdle()
        resume.assertIsFocused()
        third.isOpen()
        compose.press(Key.DirectionUp)
        third.assertIsFocused().isOpen()
        second.isClosed()
    }

    /** A scan bringing the film the library lacked while its panel is open and focused: it stays so, in colour. */
    @Test
    fun aFilmTheLibraryGainsKeepsItsPanelOpenAndFocused() {
        val source = duneSource()
        val page = page(source)
        compose.press(Key.DirectionUp)
        compose.press(Key.DirectionRight)
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
     * A step along a long saga recomposes the panels it concerns, the one left and the one reached, never the others:
     * a panel's body runs fewer times in a step than the saga has panels.
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

    /** Reprendre names the film it plays and what is left of it, beside Depuis le début; OK resumes it. */
    @Test
    fun reprendreNamesTheFilmItPlays() {
        val page = page()
        resume.assert(hasText(duneTwo.title, substring = true))
            .assert(hasText(text(R.string.time_left, formatDuration(74 * MIN)), substring = true))
        compose.onNodeWithContentDescription(text(R.string.from_start)).assertExists()
        compose.press(Key.DirectionCenter)
        assertEquals(listOf("${duneTwo.id} true"), page.played)
    }

    /** Once a film is watched, the next one not started is « à suivre », and Lire names it, from its start. */
    @Test
    fun lireNamesTheFilmToFollow() {
        val next = duneTwo.copy(user = UserState())
        val page = page(duneSource(second = next))
        val lire = compose.onNode(hasText(text(R.string.play)) and hasClickAction())
        lire.assertIsFocused().assert(hasText(next.title, substring = true))
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
        compose.onNode(hasText(text(R.string.play)) and hasClickAction()).assertIsFocused().assert(hasText("John Wick", substring = true))
        compose.onNodeWithContentDescription(text(R.string.saga_volet_saga, box.title)).isOpen()
        films.forEachIndexed { i, film ->
            compose.onNodeWithContentDescription(text(R.string.saga_card_name, film.title, i + 1) + ", ${film.year}")
                // No state: nothing watched, none missing; its length alone.
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, formatDuration(130 * MIN)))
        }
    }

    /**
     * Read again in vain, the saga keeps its panels and says why at the predella's end, Réessayer after its actions; OK
     * there puts focus on Lire, where it stays once the saga is read, the saga's panel open.
     */
    @Test
    fun aSagaReadAgainInVainSaysWhyWithRetry() {
        val source = duneSource()
        val page = page(source)
        source.away = true
        compose.runOnIdle { page.vm.load() }
        compose.waitForIdle()
        compose.onNodeWithText(text(UiError.OFFLINE.title)).assertExists()
        val retry = compose.onNode(hasText(text(R.string.retry)) and hasClickAction())
        compose.press(Key.DirectionRight, times = 3)
        retry.assertIsFocused()
        compose.press(Key.DirectionRight)
        retry.assertIsFocused()
        source.away = false
        compose.press(Key.DirectionCenter)
        retry.assertDoesNotExist()
        resume.assertIsFocused()
        saga.isOpen()
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
     * Past eight films the panels scroll at their narrowest, the open one two closed panels from either end of the
     * polyptych, but at its ends; Up from Lire lands on the film it plays.
     */
    @Test
    fun aLongSagaScrollsTheOpenPanelKeptInView() {
        val films = bondFilms
        page(bondSource(), bond.id)
        val plan = VoletPlan.of(BOND + 1, HlLayout.CANVAS_WIDTH.dp - HlLayout.voletSide * 2, 370.5.dp)
        val kept = (plan.closed + plan.gap) * 2
        val start = HlLayout.voletSide
        val end = HlLayout.CANVAS_WIDTH.dp - HlLayout.voletSide
        compose.press(Key.DirectionUp)
        films.forEachIndexed { i, film ->
            if (i > 0) compose.press(Key.DirectionRight)
            val panel = compose.onNodeWithContentDescription(text(R.string.saga_card_name, film.title, i + 1) + ", ${film.year}")
                .assertIsFocused()
                .isOpen()
                .getUnclippedBoundsInRoot()
            // Panel i + 1 of the polyptych, the saga's first.
            if (i + 1 >= 2) assertTrue("${film.title}: ${panel.left}", panel.left >= start + kept - HALF.dp)
            if (i + 1 <= BOND - 2) assertTrue("${film.title}: ${panel.right}", panel.right <= end - kept + HALF.dp)
        }
    }

    /**
     * The predella stands on the safe area's foot at every text size, a tile's label room under it, the polyptych taking
     * what is left above it; the first panel's frame inside the safe area.
     */
    @Test
    fun thePredellaStandsOnTheSafeFoot() {
        val page = page()
        listOf(0.85f, 1f, 1.3f).forEach { step ->
            compose.runOnIdle { page.step = step }
            compose.waitForIdle()
            val panel = saga.getUnclippedBoundsInRoot()
            val lire = resume.getUnclippedBoundsInRoot()
            val room = page.labelRoom
            assertEquals("at $step: the polyptych's top", HlLayout.voletTop.value, panel.top.value, HALF)
            assertTrue("at $step: ${panel.left}", (panel.left - HlFocus.reach).value >= HlSpace.safeH.value - HALF)
            assertEquals("at $step: Lire under the polyptych", (panel.bottom + HlSpace.l).value, lire.top.value, HALF)
            assertEquals("at $step: the predella's foot", (SAFE_FOOT - room).value, lire.bottom.value, HALF)
        }
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

        /** The safe area's foot: 512 on the canvas. */
        val SAFE_FOOT = 540.dp - HlSpace.safeV
    }
}
