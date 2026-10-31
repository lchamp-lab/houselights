package app.houselights.ui.home

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.FailureKind
import app.houselights.providers.local.ScanState
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.toFolder
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIconButtonDefaults
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.components.FirstTitle
import app.houselights.ui.design.components.labelX
import app.houselights.ui.detail.Speaking
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.pressBack
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatCount
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import kotlin.math.abs

/**
 * DESIGN.md, Layout: home's first scan at the default text size and the largest, in each of the app's languages, its
 * worst case shown (a long name on two lines, TMDB's note, « Modifier la clé » for a refused key): the column holds
 * under the head with room to spare, its lines whole, the block on the safe area's foot; « Aller à l'accueil » and its
 * line hold over the wall, which keeps its place, under the head, clear of the settings tile's label; a first Back's
 * line holds on two lines at most between the lockup and the tile, over the button. Measured in the app's own font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstScanLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The language home speaks, null the device's. */
    private var language by mutableStateOf<String?>(null)

    /** The pass at its fifth file of 412, the files counted. */
    private val reading = ScanState(running = true, sourceId = 1, processed = 5, total = 412, current = FILE, firstPass = true)

    private val scan = MutableStateFlow(reading)

    /** A long name, on two lines in `display`, and three titles found, so « Aller à l'accueil » shows. */
    private val shows = MutableStateFlow(
        FirstScanShows(
            folder = nfsFolder(name = LONG_NAME, host = "DS920", share = "volume1", path = "Films", id = 1).toFolder(),
            titles = (1..3).map { FirstTitle(it.toLong(), "Film $it", 2020, null) },
            found = 3,
        ),
    )

    /** How the screen sets `meta`, at the text size in use. */
    private lateinit var measurer: TextMeasurer
    private lateinit var meta: TextStyle
    private lateinit var density: Density

    private fun open(step: Float) {
        compose.setTvContent(textStep = step) {
            val measures = rememberTextMeasurer()
            val style = Hl.type.meta
            val shown = LocalDensity.current
            SideEffect {
                measurer = measures
                meta = style
                density = shown
            }
            Speaking(language) {
                HomeGrid(HomeState(content = HomeContent.FirstScan, firstPass = true), scan, HomeActions(), firstScan = shows)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theFirstScanHoldsAtTheDefaultTextSize() = holds(1f)

    @Test
    fun theFirstScanHoldsAtTheLargestTextSize() = holds(LARGEST_TEXT)

    private fun holds(step: Float) {
        open(step)
        // The wall in dp, whatever the text size: at the safe area's end, on its foot, two whole lines.
        val wall = compose.onNodeWithContentDescription("3${NBSP}titles").getUnclippedBoundsInRoot()
        assertEquals("the wall's left at $step", (CANVAS - HlSpace.safeH - FirstScanDefaults.wall).value, wall.left.value, HALF)
        assertEquals("the wall's foot at $step", SAFE_FOOT.value, wall.bottom.value, HALF)
        val device = Locale.getDefault()
        val faults = try {
            InterfaceLanguage.LANGUAGES.flatMap { tag ->
                Locale.setDefault(Locale.forLanguageTag(tag))
                compose.runOnIdle { language = tag }
                compose.waitForIdle()
                column(tag) + button(tag, wall) + back(tag)
            }
        } finally {
            Locale.setDefault(device)
            scan.value = reading
        }
        assertEquals("At $step", emptyList<String>(), faults)
    }

    /**
     * The column in [tag], for each of TMDB's troubles, the files counted then the folder listed: the note under the
     * head with room left, whole, « Modifier la clé » whole, the block on the safe area's foot, its lines whole.
     */
    private fun column(tag: String): List<String> = TROUBLES.flatMap { issue ->
        listOf(reading, reading.copy(processed = 0, total = 0, current = null, listed = LISTED)).flatMap { state ->
            compose.runOnIdle { scan.value = state.copy(tmdbIssue = issue) }
            compose.waitForIdle()
            val counted = state.total > 0
            val at = "$tag, $issue, ${if (counted) "counted" else "listed"}"
            val note = said(spoken(tag, noteId(issue)))
            val lead = said(spoken(tag, if (counted) R.string.first_scan_reading else R.string.first_scan_listing))
            val line = said(if (counted) FILE else found(tag))
            val block = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).getUnclippedBoundsInRoot()
            val key = said(spoken(tag, R.string.error_action_edit_tmdb_key)).takeIf { issue == FailureKind.TMDB_KEY_REFUSED }
            listOfNotNull(
                "$at: the column has no room left under the head".takeIf { note.top().value <= COLUMN_TOP.value + HALF },
                "$at: the block is off the safe area's foot".takeIf { abs(block.bottom.value - SAFE_FOOT.value) > HALF },
                cut("$at: the note", note),
                cut("$at: what the block does", lead),
                cut("$at: the block's last line", line),
                key?.let { cut("$at: « Modifier la clé »", it) },
            )
        }
    }

    /**
     * « Aller à l'accueil » in [tag]: under the head with room left, its words whole and its line on one line within the
     * [wall]'s width, both clear of the settings tile's label as focus would show it.
     */
    private fun button(tag: String, wall: DpRect): List<String> {
        val words = said(spoken(tag, R.string.first_scan_home))
        val plate = goHome(tag)
        val goesOn = width(spoken(tag, R.string.first_scan_home_goes_on))
        val tile = compose.onNodeWithContentDescription(spoken(tag, R.string.nav_settings)).getUnclippedBoundsInRoot()
        val label = width(spoken(tag, R.string.nav_settings)) + HlControl.labelPaddingH * 2
        val labelLeft = tile.left + labelX(
            (tile.right - tile.left).value.toInt(),
            label.value.toInt(),
            tile.left.value.toInt(),
            CANVAS.value.toInt(),
            HlIconButtonDefaults.labelEdge.value.toInt(),
        ).dp
        val labelFoot = tile.bottom + HlSpace.xs + HlControl.labelPaddingV * 2 + with(density) { meta.lineHeight.toDp() }
        val lineTop = plate.bottom + HlSpace.xxs
        return listOfNotNull(
            "$tag: « Aller à l'accueil » has no room left under the head".takeIf { plate.top.value <= HEAD_FOOT.value + HALF },
            "$tag: « Aller à l'accueil » touches the wall".takeIf { plate.bottom + FirstScanDefaults.buttonGap > wall.top + HALF.dp },
            cut("$tag: « Aller à l'accueil »", words),
            "$tag: its line, $goesOn, is wider than the wall".takeIf { goesOn > FirstScanDefaults.wall },
            "$tag: the tile's label touches the button".takeIf { labelLeft <= plate.right },
            "$tag: the tile's label touches the button's line".takeIf { labelFoot > lineTop && labelLeft <= wall.left + goesOn },
        )
    }

    /** A first Back in [tag]: its line whole, on two lines at most, between the lockup and the tile, over the button. */
    private fun back(tag: String): List<String> {
        compose.pressBack()
        val words = said(spoken(tag, R.string.first_scan_back_waits))
        val layout = layoutOf(words)
        val box = words.getUnclippedBoundsInRoot()
        val tile = compose.onNodeWithContentDescription(spoken(tag, R.string.nav_settings)).getUnclippedBoundsInRoot()
        val plate = goHome(tag)
        val faults = listOfNotNull(
            cut("$tag: Back's line", words),
            "$tag: Back's line on ${layout.lineCount} lines".takeIf { layout.lineCount > 2 },
            "$tag: Back's line under the tile".takeIf { box.right + HlControl.labelPaddingH > tile.left },
            "$tag: Back's line off the screen's top".takeIf { box.top - HlControl.labelPaddingV < 0.dp },
            "$tag: Back's line over the button".takeIf { box.bottom + HlControl.labelPaddingV > plate.top },
        )
        // The line goes with its time, so the next Back says it again rather than quits.
        compose.mainClock.advanceTimeBy(QUIT_WINDOW_MS + FRAME_MS)
        compose.waitForIdle()
        return faults
    }

    /** Where « Aller à l'accueil » lies, found as it is read in [tag], with its line. */
    private fun goHome(tag: String): DpRect {
        val read = "${spoken(tag, R.string.first_scan_home)}. ${spoken(tag, R.string.first_scan_home_goes_on)}"
        return compose.onNodeWithContentDescription(read).getUnclippedBoundsInRoot()
    }

    /** [id] as home says it in [tag]. */
    private fun spoken(tag: String, id: Int): String = speaking(tag).getString(id)

    /** The videos a listing found so far, in [tag], their thousands grouped as the block groups them. */
    private fun found(tag: String): String =
        speaking(tag).resources.getQuantityString(R.plurals.first_scan_found, LISTED, formatCount(LISTED.toLong()))

    private fun speaking(tag: String): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    private fun noteId(issue: FailureKind) = when (issue) {
        FailureKind.TMDB_KEY_REFUSED -> R.string.home_scan_tmdb_refused
        FailureKind.CERTIFICATE -> R.string.home_scan_tmdb_certificate
        else -> R.string.home_scan_tmdb_offline
    }

    /** How wide [text] is set in `meta`, at the text size in use. */
    private fun width(text: String): Dp = compose.runOnIdle { with(density) { measurer.measure(text, meta).size.width.toDp() } }

    private fun said(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.top(): Dp = getUnclippedBoundsInRoot().top

    /** [what], when [node]'s text is cut: an ellipsis or a line past its room. */
    private fun cut(what: String, node: SemanticsNodeInteraction): String? {
        val layout = layoutOf(node)
        val cut = layout.hasVisualOverflow || (0 until layout.lineCount).any(layout::isLineEllipsized)
        return "$what is cut: «${layout.layoutInput.text}»".takeIf { cut }
    }

    /** How the text [node] says is laid out. */
    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private companion object {
        const val LARGEST_TEXT = 1.3f
        const val HALF = 0.5f
        const val FRAME_MS = 32L
        const val LISTED = 1_240
        const val LONG_NAME = "Films de la médiathèque familiale en 4K"
        const val FILE = "Bugonia (2025).mkv"

        /** TMDB's troubles a scan says: the key refused, with « Modifier la clé », TMDB out of reach, its certificate refused. */
        val TROUBLES = listOf(FailureKind.TMDB_KEY_REFUSED, FailureKind.TMDB_UNREACHABLE, FailureKind.CERTIFICATE)
        val CANVAS = 960.dp

        /** Under home's head: the safe area's top, the lockup's band and the gap under it. */
        val HEAD_FOOT = HlSpace.safeV + HomeGridDefaults.head + HomeGridDefaults.headGap

        /** The column's top, `l` under the head. */
        val COLUMN_TOP = HEAD_FOOT + HlSpace.l
        val SAFE_FOOT = 540.dp - HlSpace.safeV
    }
}
