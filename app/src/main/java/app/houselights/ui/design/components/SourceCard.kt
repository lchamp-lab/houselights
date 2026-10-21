package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Ground
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.edge

/**
 * A library folder: a glass panel opened by its latest [posters] edge to edge, melting into it at their foot; its
 * [name] in the poster's voice beside its [protocol], printed; its [address]; then its [facts] and when it was
 * [scanned], a state. [state] takes the facts' place (a scan running, a failure), told when the card has focus.
 * Focus fills it white, the posters as they are.
 */
@Composable
fun SourceCard(
    name: String,
    protocol: String,
    address: String,
    posters: List<ImageRef>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    facts: List<String?> = emptyList(),
    scanned: String? = null,
    state: (@Composable (focused: Boolean) -> Unit)? = null,
) {
    val c = Hl.colors
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = c.glass, restInk = c.content, edge = BorderStroke(HlSize.hairline, c.edge)),
        shape = HlShapes.panel,
        modifier = modifier.fillMaxWidth(),
    ) { lit ->
        val ink = LocalContentColor.current
        val quiet = FocusStyle.quietInk(lit, enabled = true, lit = false)
        val plate = LocalPlate.current
        CompositionLocalProvider(LocalGround provides Ground.Glass) {
            Column {
                // Without posters the strip sets the name like a one-sheet, at its foot: nothing melts over it.
                val bare = posters.isEmpty()
                val melt = remember(plate, bare) { if (bare || plate == null) Modifier else Modifier.meltsInto(plate) }
                PosterStrip(posters, SOURCE_STRIP, melt, name = name)
                Column(Modifier.padding(start = HlInset.card, end = HlInset.card, top = HlSpace.xs, bottom = HlInset.card)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        Text(
                            name,
                            style = Hl.type.rowTitle,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Badge(protocol, ink = quiet.takeIf { lit })
                    }
                    Spacer(Modifier.height(HlSpace.xxs))
                    Text(address, style = Hl.type.meta, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(HlSpace.m))
                    if (state != null) {
                        state(lit)
                    } else {
                        OneLine {
                            facts.filterNotNull().filter { it.isNotBlank() }.forEach {
                                Text(it, style = Hl.type.meta, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (scanned != null) Scanned(scanned, ink, lit)
                        }
                    }
                }
            }
        }
    }
}

/** When the folder was read, a state done: the white dot, black on focus's plate, before the words. */
@Composable
private fun Scanned(text: String, ink: Color, lit: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLight(StatusState.Done, ink = ink.takeIf { lit })
        Spacer(Modifier.width(HlSpace.xs))
        Text(text, style = Hl.type.meta, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Melts what it draws into its [plate] ([LocalPlate]) at its foot, along the system's curve ([HlScrim.STRIP_FOOT]);
 * the plate is read while drawing, so the melt follows focus's white.
 */
private fun Modifier.meltsInto(plate: ColorProducer): Modifier =
    drawWithCache {
        // One mask, cached; the plate's colour tints it.
        val mask = Brush.verticalGradient(*HlScrim.STRIP_FOOT.colorStops(Color.White), startY = 0f, endY = size.height)
        var tone = Color.Unspecified
        var tint: ColorFilter? = null
        onDrawWithContent {
            drawContent()
            val now = plate()
            if (now != tone) {
                tone = now
                tint = ColorFilter.tint(now, BlendMode.SrcIn)
            }
            drawRect(mask, colorFilter = tint)
        }
    }

/** Posters across a source card. */
const val SOURCE_STRIP = 6
