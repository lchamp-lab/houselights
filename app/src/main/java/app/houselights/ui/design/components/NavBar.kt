package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import app.houselights.ui.design.GradationMark
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlNavBarDefaults
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.tryRequestFocus
import kotlin.math.roundToInt

/**
 * The bar of the app's places, on home and on a library's wall: Gradation in projector white, its signature, neither
 * focused nor read, so the place you are in stays its one ember light; the [places] as words, [current] lit,
 * [onPlace] on OK; at its end [status], a passing line, then the [tiles]. As tall as a compact tile at any text
 * size; [track] goes to the words' group.
 */
@Composable
fun HlNavBar(
    places: List<String>,
    current: Int,
    onPlace: (Int) -> Unit,
    modifier: Modifier = Modifier,
    track: Modifier = Modifier,
    status: String? = null,
    tiles: @Composable RowScope.() -> Unit,
) {
    OnArtwork { HeaderControls { Row(
        modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A long track scrolls; the tiles stay.
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            GradationMark(Modifier.clearAndSetSemantics {}, ink = Hl.colors.content)
            Spacer(Modifier.width(HlNavBarDefaults.markGap))
            HlSegmented(places, current, onPlace, track.padding(end = HlSpace.l), scrollable = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.CenterVertically) {
            if (status != null) PassingStatus(status)
            tiles()
        }
    } } }
}

/** Whether the bar over a wall shows, and the way to bring it back with focus on it ([reveal]). */
@Stable
class BarFoldState internal constructor(open: Boolean) {
    /** The bar shows: focus is in it, or went from it to the header. */
    var open by mutableStateOf(open)
        internal set

    internal var focusAsked by mutableStateOf(false)

    internal val bar = FocusRequester()

    internal val header = FocusRequester()

    /** Brings the bar back, focus on its current place once it is there. */
    fun reveal() {
        open = true
        focusAsked = true
    }

    internal companion object {
        val Saver: Saver<BarFoldState, Boolean> = Saver(save = { it.open }, restore = ::BarFoldState)
    }
}

/** A bar's fold, kept while its screen is in the back stack. */
@Composable
fun rememberBarFoldState(): BarFoldState = rememberSaveable(saver = BarFoldState.Saver) { BarFoldState(false) }

/**
 * A wall under the bar: open while focus is in the [bar], the [header] and the [content] lie [HlNavBarDefaults.fold]
 * lower; once focus enters the content, the bar slides up out of the screen and they take its place, in the focus pull
 * as it comes and the fall back as it goes. Focus in the header leaves the bar as it is; [header] is told whether it
 * lies under the bar. Up from the header brings the bar back on its current place, Down from the bar goes to the
 * header. Folded, the bar is not composed: neither the remote nor a screen reader finds it off the screen. Positions
 * are read while placing, so the motion recomposes nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BarFold(
    bar: @Composable () -> Unit,
    header: @Composable (underBar: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    state: BarFoldState = rememberBarFoldState(),
    content: @Composable () -> Unit,
) {
    val shown by animateFloatAsState(
        if (state.open) 1f else 0f,
        tween(HlMotion.STANDARD, easing = if (state.open) HlMotion.focusPull else HlMotion.fallBack),
        label = "bar",
    )
    val there by remember { derivedStateOf { state.open || shown > 0f } }
    Box(modifier) {
        Column(Modifier.fillMaxSize().offset { IntOffset(0, (HlNavBarDefaults.fold.toPx() * shown).roundToInt()) }) {
            Box(
                Modifier
                    .focusRequester(state.header)
                    // Up from the header: the bar's current place, the bar brought back first when folded.
                    .focusProperties { up = if (there) state.bar else FocusRequester.Default }
                    .onPreviewKeyEvent { event ->
                        val back = !there && event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                        if (back) state.reveal()
                        back
                    },
            ) { header(state.open) }
            Box(Modifier.weight(1f).onFocusChanged { if (it.hasFocus) state.open = false }) { content() }
        }
        if (there) {
            Box(
                Modifier
                    .slideUp { 1f - shown }
                    .focusRequester(state.bar)
                    .focusProperties { exit = { if (it == FocusDirection.Down) state.header else FocusRequester.Default } }
                    .focusGroup()
                    .onFocusChanged { if (it.hasFocus) state.open = true },
            ) { bar() }
        }
    }
    LaunchedEffect(state.focusAsked) {
        if (!state.focusAsked) return@LaunchedEffect
        // The bar composed and laid out first.
        withFrameNanos { }
        state.bar.tryRequestFocus()
        state.focusAsked = false
    }
}

/** Placed up by [hidden] of its own height, read while placing: 1 is out of the screen above. */
private fun Modifier.slideUp(hidden: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, -(placeable.height * hidden()).roundToInt()) }
}
