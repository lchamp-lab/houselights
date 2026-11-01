package app.houselights.ui.detail

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.core.SagaTally
import app.houselights.core.UserState
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.HeroDefaults
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.components.TILE_LABEL
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.cutTexts
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressBack
import app.houselights.ui.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * DESIGN.md, Saga page: its words in each of the app's languages at the largest text size (1.3), on the saga that says
 * the most, its films held out of those out, two to come, one watched and one half seen of the board's longest title.
 * The open panels' words, as wide as a hero's title: the saga's facts on one line, its years, films and those watched
 * whole, the films to come then the length giving way whole as a line of facts drops its last; each film's state on its
 * facts' line. The saga's actions: Reprendre and its tiles whole inside its panel, the film it plays named over them.
 * Nothing is cut but a film's title in an open panel's measure. The saga's facts in their four forms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so words measure as on the player.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SagaWordsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** Loads at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val device = Locale.getDefault()

    @After
    fun stop() {
        work.cancel()
        Locale.setDefault(device)
    }

    /**
     * Each open panel's words, as the page composes them in the panel's measure, here outside the panel, which a screen
     * reader hears as one element.
     */
    @Test
    fun thePanelsWordsAreWholeInEveryLanguage() {
        var language by mutableStateOf(InterfaceLanguage.LANGUAGES.first())
        val tally = SagaTally(parts, reckoning)
        compose.setContent {
            Speaking(language) {
                HouselightsTheme(LARGEST) {
                    // As tall as they are: one under the other, past the screen's foot.
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        (listOf(null) + parts).forEach { part ->
                            PanelWords(mission, part, tally, onCut = {}, Modifier.width(HeroDefaults.titleWidth))
                        }
                    }
                }
            }
        }
        val wrong = InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val words = Words(tag)
            Locale.setDefault(words.locale)
            compose.runOnIdle { language = tag }
            compose.waitForIdle()
            words.panels()
        }
        assertTrue("At $LARGEST:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The saga's actions on arrival: Reprendre, its three tiles whole beside it inside its panel, its film named over them. */
    @Test
    fun theSagasActionsHoldInEveryLanguage() {
        val page = SagaPage(compose, source(), work)
        page.step = LARGEST
        page.language = InterfaceLanguage.LANGUAGES.first()
        page.open(mission.id)
        val wrong = InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val words = Words(tag)
            Locale.setDefault(words.locale)
            compose.runOnIdle { page.language = tag }
            compose.waitForIdle()
            words.actions()
        }
        assertTrue("At $LARGEST:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /**
     * The saga's facts on one line in their four forms: before a film is begun, its years, its films and their length;
     * some missing, « 2 films sur 3 »; one watched, « 1 vu » and what is left; one started, none watched, what is left
     * alone; at 1.3, its years, films and those watched still whole.
     */
    @Test
    fun theSagasFactsInTheirFourForms() {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val fresh = first.copy(user = UserState())
        val half = reckoning
        val whole = listOf(SagaPart(fresh, fresh.title, 1996), SagaPart(reckoning.copy(user = UserState()), reckoning.title, 2023))
        val missing = listOf(SagaPart(fresh, fresh.title, 1996), SagaPart(null, "Mission : Impossible 2", 2000))
        val watched = listOf(SagaPart(first, first.title, 1996), SagaPart(half, half.title, 2023))
        val started = listOf(SagaPart(fresh, fresh.title, 1996), SagaPart(half, half.title, 2023))
        var parts by mutableStateOf(whole)
        var step by mutableFloatStateOf(1f)
        compose.setContent {
            HouselightsTheme(
                step,
            ) { PanelWords(mission, null, SagaTally(parts, null), onCut = {}, Modifier.width(HeroDefaults.titleWidth)) }
        }
        fun says(case: List<SagaPart>, vararg facts: String, not: List<String> = emptyList()) {
            compose.runOnIdle { parts = case }
            compose.waitForIdle()
            facts.forEach { assertTrue("$case: «$it»", compose.placed(it) == 1) }
            not.forEach { assertTrue("$case: no «$it»", compose.placed(it) == 0) }
        }
        val left = { ms: Long -> res.getString(R.string.time_left, formatDuration(ms)) }
        says(whole, "1996 – 2023", res.getQuantityString(R.plurals.film_count, 2, 2), formatDuration(274 * MIN))
        says(missing, "1996 – 2000", res.getQuantityString(R.plurals.film_count_of, 1, 1, 2), formatDuration(110 * MIN))
        says(watched, res.getQuantityString(R.plurals.saga_watched, 1, 1), left(82 * MIN), not = listOf(formatDuration(274 * MIN)))
        says(started, left((110 + 82) * MIN), not = listOf(res.getQuantityString(R.plurals.saga_watched, 0, 0)))
        compose.runOnIdle { step = LARGEST }
        says(watched, "1996 – 2023", res.getQuantityString(R.plurals.film_count, 2, 2), res.getQuantityString(R.plurals.saga_watched, 1, 1))
    }

    /**
     * Every tile's label of the saga's actions and of a film's (Reprendre, Depuis le début, its check and its page) keeps
     * within its panel's actions in every language at 1.3: centred under its tile when it fits, else slid under it to
     * their edge; here in a panel at its narrowest (428, five films).
     */
    @Test
    fun theTilesLabelsKeepWithinANarrowPanelInEveryLanguage() {
        val wrong = labelsAcross(source(), mission.id) { words ->
            val saga = words.labelsMisplaced(words.sagaPanel, SAGA_TILES, "the saga's actions, 428")
            compose.press(Key.DirectionUp)
            compose.press(Key.DirectionRight, times = 3)
            saga + words.labelsMisplaced(words.reckoningPanel, FILM_TILES, "a film's actions, 428")
        }
        assertTrue("At $LARGEST:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The same in a wider panel (541, three films), where a label has room to stay centred. */
    @Test
    fun theTilesLabelsKeepWithinAWidePanelInEveryLanguage() {
        val wrong = labelsAcross(duneSource(), duneSaga.id) { words ->
            val saga = words.labelsMisplaced(words.dunePanel, SAGA_TILES, "the saga's actions, 541")
            compose.press(Key.DirectionUp)
            compose.press(Key.DirectionRight, times = 2)
            saga + words.labelsMisplaced(words.duneTwoPanel, FILM_TILES, "a film's actions, 541")
        }
        assertTrue("At $LARGEST:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** What [check] finds wrong on the page of [id] from [source] in each language at 1.3, back on the saga after each. */
    private fun labelsAcross(source: SagaSource, id: String, check: (Words) -> List<String>): List<String> {
        val page = SagaPage(compose, source, work)
        page.step = LARGEST
        page.language = InterfaceLanguage.LANGUAGES.first()
        page.open(id)
        return InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val words = Words(tag)
            Locale.setDefault(words.locale)
            compose.runOnIdle { page.language = tag }
            compose.waitForIdle()
            check(words).also { compose.pressBack() }
        }
    }

    /** What the page says in [tag], and where it says it wrong. */
    private inner class Words(val tag: String) {
        val locale: Locale = Locale.forLanguageTag(tag)
        private val res: Resources = ApplicationProvider.getApplicationContext<Context>().let { context ->
            val speaking = Configuration(context.resources.configuration).apply { setLocale(this@Words.locale) }
            context.createConfigurationContext(speaking).resources
        }
        private val wrong = mutableListOf<String>()
        private val timeLeft = res.getString(R.string.time_left, formatDuration(reckoning.runtimeMs!! - reckoning.user.positionMs))

        /** The panels whose actions are measured, as read in this language: Mission : Impossible's and Dune's. */
        val sagaPanel: String = res.getString(R.string.saga_volet_saga, mission.title)
        val reckoningPanel: String = res.getString(R.string.saga_card_name, reckoning.title, 3) + ", 2023"
        val dunePanel: String = res.getString(R.string.saga_volet_saga, duneSaga.title)
        val duneTwoPanel: String = res.getString(R.string.saga_card_name, duneTwo.title, 2) + ", 2024"

        /**
         * The labels now laid out of the tiles named [tiles], in their order, that are not where they belong in the
         * panel read as [panel]: centred under their tile, else slid to the edge of its actions (its words' inset).
         */
        fun labelsMisplaced(panel: String, tiles: List<Int>, where: String): List<String> {
            val bounds = compose.onNode(hasContentDescription(panel)).getUnclippedBoundsInRoot()
            val start = (bounds.left + HlInset.card).value
            val end = (bounds.right - HlInset.card).value
            val labels = compose.onAllNodes(hasTestTag(TILE_LABEL), useUnmergedTree = true).fetchSemanticsNodes()
                .filter { it.layoutInfo.isPlaced }
            val buttons = tiles.map {
                compose.onNode(
                    hasContentDescription(res.getString(it)) and hasClickAction(),
                ).getUnclippedBoundsInRoot()
            }
            if (labels.size != buttons.size) return listOf("$tag, $where: ${labels.size} labels for ${buttons.size} tiles")
            val density = compose.density.density
            return labels.zip(buttons).mapNotNull { (node, tile) ->
                val left = node.boundsInRoot.left / density
                val width = node.boundsInRoot.width / density
                val centred = (tile.left + tile.right).value / 2 - width / 2
                val expected = centred.coerceAtMost(end - width).coerceAtLeast(start)
                "$tag, $where: a label from $left, $width wide, where $expected (actions $start..$end)"
                    .takeIf { abs(left - expected) > 1f }
            }
        }

        /** The saga's panel and each film's, open. */
        fun panels(): List<String> {
            // The films to come, last on the line, may give way whole, as a line of facts drops its last.
            shown("the saga's facts", "1996 – 2023", res.getQuantityString(R.plurals.film_count_of, 2, 2, 3))
            shown("the saga's films watched", res.getQuantityString(R.plurals.saga_watched, 1, 1))
            shown("a film watched", res.getString(R.string.watched))
            shown("a film missing", res.getString(R.string.saga_absent))
            // The film half seen says its time left, which the saga's line may give way.
            if (compose.placed(timeLeft) < 1) wrong += "$tag, the film in progress: «$timeLeft» not shown"
            shown("a film to come", res.getString(R.string.saga_release_on, longDate(finalOut, locale)))
            shown("a film announced", res.getString(R.string.saga_announced))
            cut("the panels", but = TITLES)
            return wrong
        }

        fun actions(): List<String> {
            val panel = compose.onNode(hasContentDescription(res.getString(R.string.saga_volet_saga, mission.title)))
                .getUnclippedBoundsInRoot()
            val end = panel.right - HlInset.card
            val resume = compose.onNode(hasText(res.getString(R.string.resume)) and hasClickAction())
            inside("Reprendre", resume, end)
            listOf(
                "Depuis le début" to res.getString(R.string.from_start),
                "the watched tile" to res.getString(R.string.mark_saga_watched),
                "Modifier" to res.getString(R.string.edit),
            ).forEach { (what, label) -> tile(what, compose.onNode(hasContentDescription(label) and hasClickAction()), end) }
            shown("Reprendre", res.getString(R.string.resume))
            shown("the line over them", res.getString(R.string.section_next_up))
            // The film it plays named, cut at the line's end as a title in a panel's measure is.
            cut("the saga's actions", but = TITLES)
            return wrong
        }

        /** Each of [words] laid out in its own box: none dropped from its line. */
        private fun shown(what: String, vararg words: String) {
            words.filter { compose.placed(it) == 0 }.forEach { wrong += "$tag, $what: «$it» not shown" }
        }

        /** Nothing on screen cut, [but] film titles a panel sets in its own measure, ending in an ellipsis past it. */
        private fun cut(where: String, but: Set<String> = emptySet()) {
            val cut = compose.cutTexts().filter { it !in but }
            if (cut.isNotEmpty()) wrong += "$tag, $where: cut ${cut.joinToString { "«$it»" }}"
        }

        private fun inside(what: String, node: SemanticsNodeInteraction, end: Dp) {
            val right = node.getUnclippedBoundsInRoot().right
            if (right > end + HALF.dp) wrong += "$tag, $what: ends at $right, past $end"
        }

        /** A tile inside its panel at its whole width, never squeezed by the button before it. */
        private fun tile(what: String, node: SemanticsNodeInteraction, end: Dp) {
            inside(what, node, end)
            val width = node.getUnclippedBoundsInRoot().width
            if (width < HlSize.tile - HALF.dp) wrong += "$tag, $what: $width wide, under ${HlSize.tile}"
        }
    }

    /** How many texts reading [text] are laid out and placed. */
    private fun SemanticsNodeInteractionsProvider.placed(text: String): Int =
        onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().count { it.layoutInfo.isPlaced }

    private companion object {
        const val LARGEST = 1.3f

        /** The tiles of the saga's actions and of a film's, by their labels, in their order. */
        val SAGA_TILES = listOf(R.string.from_start, R.string.mark_saga_watched, R.string.edit)
        val FILM_TILES = listOf(R.string.from_start, R.string.mark_watched, R.string.saga_film_page)
        const val HALF = 0.5f

        val mission = MediaItem(
            "mi",
            MediaKind.COLLECTION,
            "Mission : Impossible",
            overview = "Ethan Hunt et son équipe, de mission en mission.",
            backdrop = picture("mi-saga"),
            totalCount = 5,
        )

        val first = MediaItem(
            "mi1",
            MediaKind.MOVIE,
            "Mission : Impossible",
            year = 1996,
            runtimeMs = 110 * MIN,
            poster = picture("mi1-poster"),
            backdrop = picture("mi1-backdrop"),
            user = UserState(played = true),
        )

        /** Half seen, its title the longest of the board's: 1 h 22 left. */
        val reckoning = MediaItem(
            "mi7",
            MediaKind.MOVIE,
            "Mission : Impossible - Dead Reckoning Partie 1",
            year = 2023,
            runtimeMs = 164 * MIN,
            poster = picture("mi7-poster"),
            backdrop = picture("mi7-backdrop"),
            user = UserState(positionMs = 82 * MIN),
        )

        val finalOut: LocalDate = LocalDate.of(2026, 12, 15)

        val parts = listOf(
            SagaPart(first, first.title, first.year, overview = "Ethan Hunt, accusé à tort.", backdrop = first.backdrop),
            SagaPart(null, "Mission : Impossible 2", 2000, overview = "Une arme biologique.", poster = picture("mi2-poster")),
            SagaPart(reckoning, reckoning.title, reckoning.year, overview = "Une IA.", backdrop = reckoning.backdrop),
            SagaPart(null, "The Final Reckoning", 2026, released = false, poster = picture("mi8-poster"), releaseDate = finalOut),
            SagaPart(null, "Mission : Impossible 9", null, released = false),
        )

        /** Film titles, which an open panel sets in its own measure, ending in an ellipsis past it, as a hero does. */
        val TITLES = parts.map { it.title }.toSet()

        fun source(): SagaSource {
            val provider = FakeProvider().apply {
                items[mission.id] = mission
                items[first.id] = first
                items[reckoning.id] = reckoning
                catalog = FakeCatalog().apply { sagas = mapOf(mission.id to Saga(mission, parts)) }
                capabilities = capabilities.copy(sagaParts = true)
            }
            return SagaSource(provider, mission, listOf(first, reckoning))
        }
    }
}
