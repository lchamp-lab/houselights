package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSize

/** The two gestures a surface takes focus with; a field draws [FocusStyle.ring] itself. */
internal enum class Gesture {
    /** What carries its own light (a picture, the main action) keeps its colours and takes the detached frame. */
    Frame,

    /** A plate (a button, a tile, a tab, a row) turns white with black ink. */
    Fill,
}

/** A surface's fill, read while drawing so it can take a picture's colour without recomposing, and its ink. */
@Immutable
internal data class Plate(val fill: ColorProducer, val ink: Color)

/** A fill of one colour, equal to another of that colour: a surface given the same look again skips. */
@Immutable
private data class Steady(private val color: Color) : ColorProducer {
    override fun invoke(): Color = color
}

/** What a [HlFocusSurface] draws at rest, with focus and disabled; made by [FocusStyle] only. */
@Immutable
internal data class FocusLook(
    val gesture: Gesture,
    val rest: Plate,
    val focus: Plate,
    val disabled: Plate,
    /** Drawn inside the surface's edge, at rest and with focus. */
    val edge: BorderStroke?,
)

/**
 * The single definition of focus (DESIGN.md, Focus): projector white in two gestures, [frame] and [fill],
 * and the [ring] of what is read or typed in. Nothing is brighter than focus, and ember never marks it.
 * A disabled surface keeps its well: focus rings it, never fills it, since white would offer an action.
 * Components take focus from here only (`FocusStyleGuardTest`).
 */
internal object FocusStyle {
    /** Frame: the white frame [HlFocus.frameGap] off the edge, lit in the picture's own tone. */
    @Composable
    @ReadOnlyComposable
    fun frame(
        rest: Color,
        restInk: Color,
        edge: BorderStroke? = null,
        disabled: Color = rest,
        disabledInk: Color = restInk,
    ): FocusLook {
        val still = Plate(Steady(rest), restInk)
        return FocusLook(Gesture.Frame, still, still, Plate(Steady(disabled), disabledInk), edge)
    }

    /** Fill: the plate turns white and its ink black. */
    @Composable
    @ReadOnlyComposable
    fun fill(
        rest: Color,
        restInk: Color,
        disabled: Color = rest,
        disabledInk: Color = restInk,
        edge: BorderStroke? = null,
    ): FocusLook = fill(Steady(rest), restInk, Steady(disabled), disabledInk, edge)

    /** [fill] on a plate whose colour is read while drawing: a picture's own dark follows it without recomposing. */
    @Composable
    @ReadOnlyComposable
    fun fill(
        rest: ColorProducer,
        restInk: Color,
        disabled: ColorProducer = rest,
        disabledInk: Color = restInk,
        edge: BorderStroke? = null,
    ): FocusLook {
        val c = Hl.colors
        return FocusLook(
            Gesture.Fill,
            rest = Plate(rest, restInk),
            focus = Plate(Steady(c.focus), c.onFocus),
            disabled = Plate(disabled, disabledInk),
            edge = edge,
        )
    }

    /** The ring of focus in projector white, inside the edge: a field, a read-only area, what cannot act. */
    @Composable
    @ReadOnlyComposable
    fun ring(): BorderStroke = BorderStroke(HlSize.focusBorder, Hl.colors.focus)

    /** What a plate holds by state, words or a mark: [rest] at rest, focus's ink on its white. */
    @Composable
    @ReadOnlyComposable
    fun ink(focused: Boolean, rest: Color = Hl.colors.content): Color = if (focused) Hl.colors.onFocus else rest

    /** The quietest words by state (the file a scan reads): tertiary at rest, focus's quiet ink on its white. */
    @Composable
    @ReadOnlyComposable
    fun hintInk(focused: Boolean): Color = if (focused) Hl.colors.onFocusSecondary else Hl.colors.contentTertiary

    /** Ember as a mark by state: raspberry on focus's white, where ember words would not read. */
    @Composable
    @ReadOnlyComposable
    fun emberMark(focused: Boolean): Color = if (focused) Hl.colors.emberOnLight else Hl.colors.ember

    /** Secondary text colour by state; never tertiary at rest, which drops to 3.9:1 on `FILL_STRONG`. */
    @Composable
    @ReadOnlyComposable
    fun quietInk(focused: Boolean, enabled: Boolean, lit: Boolean, onFocus: Color = Hl.colors.onFocusSecondary): Color {
        val c = Hl.colors
        return when {
            focused -> onFocus
            !enabled -> c.contentTertiary
            lit -> c.ember
            else -> c.contentSecondary
        }
    }
}
