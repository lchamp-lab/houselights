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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.cutTexts
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

/**
 * DESIGN.md, Saga page: its words in each of the app's languages at the largest text size (1.3), on the saga that says
 * the most, its films held out of those out, two to come, one watched and one half seen of the board's longest title.
 * The open panels' words, as wide as a hero's title: the saga's years, films and length, its progress whole, each
 * film's state on its facts' line, the films to come, last, giving way whole as a line of facts drops its last.
 * Reprendre names its film whole, its time left going whole before a tile gives way, its tiles whole beside it inside
 * the safe area. Nothing is cut but a film's title in an open panel's measure.
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

    /** The predella on arrival: Reprendre naming its film and its time left, its two tiles whole beside it, all inside the safe area. */
    @Test
    fun thePredellaHoldsInEveryLanguage() {
        val page = SagaPage(compose, source(), work)
        page.step = LARGEST
        page.language = InterfaceLanguage.LANGUAGES.first()
        page.open(mission.id)
        val wrong = InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val words = Words(tag)
            Locale.setDefault(words.locale)
            compose.runOnIdle { page.language = tag }
            compose.waitForIdle()
            words.predella()
        }
        assertTrue("At $LARGEST:\n" + wrong.joinToString("\n"), wrong.isEmpty())
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

        /** The saga's panel and each film's, open. */
        fun panels(): List<String> {
            // The films to come, last on the line, may give way whole, as a line of facts drops its last.
            shown(
                "the saga's facts",
                "1996 – 2023",
                res.getQuantityString(R.plurals.film_count_of, 2, 2, 3),
                formatDuration(first.runtimeMs!! + reckoning.runtimeMs!!),
            )
            shown("the saga's progress", res.getQuantityString(R.plurals.saga_watched_of, 1, 1, 2))
            shown("a film watched", res.getString(R.string.watched))
            shown("a film missing", res.getString(R.string.saga_absent))
            // The saga's progress and the film half seen say the same time left.
            if (compose.placed(timeLeft) < 2) wrong += "$tag, the saga's progress and the film in progress: «$timeLeft» not shown twice"
            shown("a film to come", res.getString(R.string.saga_release_on, longDate(finalOut, locale)))
            shown("a film announced", res.getString(R.string.saga_announced))
            cut("the panels", but = TITLES)
            return wrong
        }

        fun predella(): List<String> {
            val resume = compose.onNode(hasText(res.getString(R.string.resume)) and hasClickAction())
            inside("Reprendre", resume)
            listOf(
                "Depuis le début" to res.getString(R.string.from_start),
                "the watched tile" to res.getString(R.string.mark_saga_watched),
            ).forEach { (what, label) -> tile(what, compose.onNode(hasContentDescription(label) and hasClickAction())) }
            // Its film named whole; its time left whole too, or gone whole where the tiles need the room.
            shown("Reprendre", res.getString(R.string.resume), reckoning.title)
            cut("the predella")
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

        private fun inside(what: String, node: SemanticsNodeInteraction) {
            val right = node.getUnclippedBoundsInRoot().right
            if (right > SAFE_END + HALF.dp) wrong += "$tag, $what: ends at $right, past $SAFE_END"
        }

        /** A tile inside the safe area at its whole width, never squeezed by the button before it. */
        private fun tile(what: String, node: SemanticsNodeInteraction) {
            inside(what, node)
            val width = node.getUnclippedBoundsInRoot().width
            if (width < HlSize.tile - HALF.dp) wrong += "$tag, $what: $width wide, under ${HlSize.tile}"
        }
    }

    /** How many texts reading [text] are laid out and placed. */
    private fun SemanticsNodeInteractionsProvider.placed(text: String): Int =
        onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().count { it.layoutInfo.isPlaced }

    private companion object {
        const val LARGEST = 1.3f
        const val HALF = 0.5f

        /** The safe area's end on the canvas: 912. */
        val SAFE_END = HlLayout.CANVAS_WIDTH.dp - HlSpace.safeH

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
