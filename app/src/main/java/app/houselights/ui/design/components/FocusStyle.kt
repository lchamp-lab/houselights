package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlSize

/**
 * How a piece of artwork rises with focus, by family: a poster the most, a wide still a little
 * less, a panel only just; its outline is the same projector white.
 */
enum class Lift(val scale: Float) {
    Artwork(HlFocus.ARTWORK_SCALE),
    Card(HlFocus.CARD_SCALE),
    Panel(HlFocus.PANEL_SCALE),
}

/**
 * The one place that says what focus looks like. Focus is projector white, everywhere, in one of
 * three gestures, and nothing on a screen is ever brighter than it:
 * - **fill**: a control, a row, the option of a track turns white with black words ([fill]); a pill
 *   or a disc grows a little ([Fill.Pill]), a row as wide as its column does not ([Fill.Row]);
 * - **lift**: a picture or a panel rises by its family's share and takes a white outline ([lift]);
 * - **ring**: what is read or typed in is circled in white ([ring]).
 *
 * A control that cannot act now keeps focus, outlined, never filled: white would offer an action
 * ([disabledRing]). A row that is only read (a technical sheet's) is no disabled control: it fills
 * white as every row, and OK does nothing. Components take their focus from here and nowhere else
 * (FocusStyleGuardTest).
 */
internal object FocusStyle {
    /** How a filled element grows: pills and discs a little, rows as wide as their column not at all. */
    enum class Fill(val scale: Float) {
        Pill(HlFocus.CONTROL_SCALE),
        Row(HlFocus.ROW_SCALE),
    }

    /**
     * The colours of a filled element: [rest] and [restContent] at rest, white with black words
     * under focus or pressed; [disabled] and [disabledContent] when it cannot act.
     */
    @Composable
    fun fill(
        rest: Color,
        restContent: Color,
        disabled: Color = rest,
        disabledContent: Color = restContent,
    ): ClickableSurfaceColors {
        val c = Hl.colors
        return ClickableSurfaceDefaults.colors(
            containerColor = rest,
            contentColor = restContent,
            focusedContainerColor = c.focus,
            focusedContentColor = c.onFocus,
            pressedContainerColor = c.focus,
            pressedContentColor = c.onFocus,
            disabledContainerColor = disabled,
            disabledContentColor = disabledContent,
        )
    }

    /** How much a filled element grows with focus, enabled or not. */
    @Composable
    fun scale(fill: Fill): ClickableSurfaceScale =
        ClickableSurfaceDefaults.scale(focusedScale = fill.scale, focusedDisabledScale = fill.scale)

    /** Focus on what cannot act: the outline of focus in projector white, never the white fill. */
    @Composable
    fun disabledRing(shape: Shape): ClickableSurfaceBorder =
        ClickableSurfaceDefaults.border(focusedDisabledBorder = Border(ring(), shape = shape))

    /**
     * The colours of a lifted element: the same with focus as at rest, since the lift and the
     * outline say focus; [disabled] and [disabledContent] when it cannot act.
     */
    @Composable
    fun still(
        rest: Color,
        restContent: Color,
        disabled: Color = rest,
        disabledContent: Color = restContent,
    ): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = rest,
        contentColor = restContent,
        focusedContainerColor = rest,
        focusedContentColor = restContent,
        pressedContainerColor = rest,
        pressedContentColor = restContent,
        disabledContainerColor = disabled,
        disabledContentColor = disabledContent,
    )

    /** How much a lifted element grows with focus, enabled or not. */
    @Composable
    fun scale(lift: Lift): ClickableSurfaceScale =
        ClickableSurfaceDefaults.scale(focusedScale = lift.scale, focusedDisabledScale = lift.scale)

    /**
     * The edges of a lifted element: [rest] at rest and [disabled] when it cannot act; the white
     * outline of focus in [shape], enabled or not.
     */
    @Composable
    fun lift(shape: Shape, rest: Border = Border.None, disabled: Border = rest): ClickableSurfaceBorder {
        val outline = Border(ring(), shape = shape)
        return ClickableSurfaceDefaults.border(
            border = rest,
            focusedBorder = outline,
            disabledBorder = disabled,
            focusedDisabledBorder = outline,
        )
    }

    /** The stroke of focus: an outline in projector white, for a lift, a ring, a field. */
    @Composable
    fun ring(): BorderStroke = BorderStroke(HlSize.focusBorder, Hl.colors.focus)

    /** The colour of focus, for what draws its ring by hand. */
    @Composable
    fun ringColor(): Color = Hl.colors.focus
}
