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

/** Focus growth by family: a poster most, a wide still less, a panel slightly. */
enum class Lift(val scale: Float) {
    Artwork(HlFocus.ARTWORK_SCALE),
    Card(HlFocus.CARD_SCALE),
    Panel(HlFocus.PANEL_SCALE),
}

/**
 * The single definition of focus (DESIGN.md, Focus): projector white in three gestures, [fill], [lift]
 * and [ring]. A disabled control is outlined, never filled, since white would offer an action
 * ([disabledRing]). Components take focus from here only (FocusStyleGuardTest).
 */
internal object FocusStyle {
    /** How a filled element grows: pills and discs slightly, full-width rows not at all. */
    enum class Fill(val scale: Float) {
        Pill(HlFocus.CONTROL_SCALE),
        Row(HlFocus.ROW_SCALE),
    }

    /** A filled element's colours: white with black content when focused or pressed. */
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

    /** Focus on a disabled element: the projector-white outline, never the white fill. */
    @Composable
    fun disabledRing(shape: Shape): ClickableSurfaceBorder =
        ClickableSurfaceDefaults.border(focusedDisabledBorder = Border(ring(), shape = shape))

    /** A lifted element's colours, unchanged by focus, which the lift and outline show. */
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

    /** Lifted growth, kept while pressed so a pressed card stays as large as its glow. */
    @Composable
    fun scale(lift: Lift): ClickableSurfaceScale =
        ClickableSurfaceDefaults.scale(focusedScale = lift.scale, focusedDisabledScale = lift.scale, pressedScale = lift.scale)

    /** A lifted element crossed while an arrow is held ([app.houselights.ui.design.Scrub]) does not grow. */
    @Composable
    fun crossed(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = 1f, focusedDisabledScale = 1f, pressedScale = 1f)

    /** A lifted element's borders, with the white focus outline in [shape], enabled or not. */
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

    /** The focus stroke in projector white, for a lift, a ring or a field. */
    @Composable
    fun ring(): BorderStroke = BorderStroke(HlSize.focusBorder, Hl.colors.focus)

    /** Secondary text colour by state; never tertiary at rest, which drops to 3.9:1 on a primary button. */
    @Composable
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
