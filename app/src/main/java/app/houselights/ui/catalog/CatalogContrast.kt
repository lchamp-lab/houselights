package app.houselights.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlColors
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.greyOf
import app.houselights.ui.design.plate
import java.util.Locale

/** Each ink on what it sits on, composed as the screens compose it, with its ratio (DESIGN.md, Contrast). */
internal fun LazyListScope.catalogContrast() {
    item { Group("Contrast: each ink on what it sits on, over the lightest picture; 4.5:1 for words, 3:1 for marks", still = true) {
        ContrastBoard()
    } }
}

/** An ink of the system, as words or as a mark (a glyph, a dot, a line). */
private class Ink(val name: String, val color: Color, val mark: Boolean = false)

/** What inks sit on: its colour as composed, how it is filled, and the ink a mark's ratio is written in. */
private class Under(val name: String, val color: Color, val inks: List<Ink>, val fill: Brush = SolidColor(color), val label: Color? = null)

/** Ember's roles, the only line of the board that names them. */
private class Embers(val word: Color, val raspberry: Color, val onLight: Color, val ink: Color, val light: Brush)

@Composable
private fun ContrastBoard() {
    val c = Hl.colors
    val boards = remember(c) { boards(c) }
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
        boards.chunked(PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                row.forEach { under -> Specimen(under.name, Modifier.weight(1f), gap = HlSpace.xs) { Board(under) } }
                repeat(PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The surface, each ink on it with its ratio: words in the ink itself, a mark as a check beside its ratio. */
@Composable
private fun Board(under: Under) {
    val label = under.label ?: Hl.colors.content
    Column(
        Modifier.fillMaxWidth().clip(HlShapes.panel).background(under.fill).padding(HlInset.card),
        verticalArrangement = Arrangement.spacedBy(HlSpace.xs),
    ) {
        under.inks.forEach { ink ->
            val says = "${ink.name}  ${ratioOf(ink.color, under.color)}"
            if (ink.mark) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HlIcons.Check, contentDescription = null, tint = ink.color, modifier = Modifier.size(HlSize.icon))
                    Spacer(Modifier.width(HlSpace.xs))
                    Text(says, style = Hl.type.meta, color = label)
                }
            } else {
                Text(says, style = Hl.type.meta, color = ink.color)
            }
        }
    }
}

/** The surfaces the components compose, each over the lightest picture where it lies on one. */
private fun boards(c: HlColors): List<Under> {
    val e = Embers(c.ember, c.emberTo, c.emberOnLight, c.onEmber, c.emberLight())
    val white = Color.White
    val words = listOf(Ink("content", c.content), Ink("secondary", c.contentSecondary), Ink("tertiary", c.contentTertiary))
    val veilFloor = c.ambientDeep.copy(alpha = HlScrim.need(1f, tint = greyOf(c.ambientDeep))).compositeOver(white)
    val fieldLevel = ColorSpaces.Srgb.oetf(HlScrim.FIELD_LUMINANCE_MAX.toDouble()).toFloat()
    return listOf(
        Under("the room", c.background, words + Ink("ember", e.word)),
        Under("glass: a sheet, a menu", c.glass, words + Ink("ember", e.word)),
        Under(
            "a control on glass",
            c.content.copy(alpha = HlAlpha.FILL).compositeOver(c.glass),
            listOf(Ink("content", c.content), Ink("ember", e.word)),
        ),
        Under("the left veil at its floor", veilFloor, words),
        Under(
            "a plate at its ceiling (PLATE_MAX)",
            c.plate(alpha = HlAlpha.PLATE_MAX).compositeOver(white),
            words + Ink("ember", e.word, mark = true),
        ),
        Under(
            "a card's mark plate (VEIL)",
            c.background.copy(alpha = HlAlpha.VEIL).compositeOver(white),
            listOf(Ink("content", c.content), Ink("raspberry", e.raspberry, mark = true)),
        ),
        Under(
            "a wall's field at its brightest",
            Color.hsv(0f, 0f, fieldLevel),
            listOf(Ink("content", c.content), Ink("secondary", c.contentSecondary)),
        ),
        Under(
            "focus",
            c.focus,
            listOf(Ink("on focus", c.onFocus), Ink("the line under", c.onFocusSecondary), Ink("raspberry", e.onLight, mark = true)),
            label = c.onFocus,
        ),
        Under("the main action, at its darkest end", e.raspberry, listOf(Ink("on ember", e.ink)), fill = e.light),
    )
}

/** WCAG's ratio of two opaque colours, as "4.5:1". */
private fun ratioOf(ink: Color, under: Color): String {
    val (light, dark) = listOf(ink.luminance(), under.luminance()).sortedDescending()
    return "%.1f:1".format(Locale.ROOT, (light + FLARE) / (dark + FLARE))
}

private const val FLARE = 0.05f
private const val PER_ROW = 3
