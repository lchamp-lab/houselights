package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.plate
import app.houselights.ui.design.textRoom

/** The keys of a [LetterGrid], row by row: the alphabet, then the digits. */
val LETTER_KEYS: List<List<Char>> = ("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").chunked(LETTER_COLUMNS).map { it.toList() }

/** Keys per row of a [LetterGrid]. */
const val LETTER_COLUMNS = 6

/**
 * The typed [query] on a field the keys always type in, [HlSize.field] tall: glass in the dark of the picture the page
 * lies on ([tintedPlate]), ringed by the ember light, the search icon, the words, then the raspberry cursor; [hint]
 * below. The screen's title names the place above it.
 */
@Composable
fun SearchQuery(query: String, hint: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val fill = tintedPlate()
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = HlSize.field)
                .drawWithCache {
                    val area = HlShapes.field.createOutline(size, layoutDirection, this)
                    onDrawBehind { drawOutline(area, fill()) }
                }
                .border(BorderStroke(HlSize.focusBorder, c.emberLight()), HlShapes.field)
                .padding(horizontal = HlSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HlIcons.Search, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.icon))
            Spacer(Modifier.width(HlSpace.s))
            Text(
                query,
                style = Hl.type.lead,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // As tall as the words, and still.
            val cursor = with(LocalDensity.current) { Hl.type.lead.fontSize.toDp() }
            Box(Modifier.width(HlSize.focusBorder).height(cursor).background(c.emberTo))
        }
        Spacer(Modifier.height(HlSpace.s))
        Text(hint, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Search keys (letters, digits, then Erase and Space) without the system keyboard: square tiles of [HlSize.tileCompact]
 * in the dark of the picture the page lies on, no edge, white with focus; focus starts on [first].
 */
@Composable
fun LetterGrid(
    onKey: (Char) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
) {
    val fill = tintedPlate()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            LETTER_KEYS.forEachIndexed { row, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                    keys.forEachIndexed { column, key ->
                        val start = if (row == 0 && column == 0 && first != null) Modifier.focusRequester(first) else Modifier
                        KeyTile(key.toString(), fill, onClick = { onKey(key) }, modifier = start.size(HlSize.tileCompact))
                    }
                }
            }
        }
        // As wide as the wider of the two, each on a line of keys.
        Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            val key = Modifier.fillMaxWidth().height(HlSize.tileCompact)
            KeyTile(stringResource(R.string.search_delete), fill, onClick = onDelete, modifier = key, icon = HlIcons.Erase)
            KeyTile(stringResource(R.string.search_space), fill, onClick = onSpace, modifier = key)
        }
    }
}

/**
 * One key on its plate, white on focus: a letter in `lead`, square; a word in `entry` after its [icon], as wide as
 * it needs.
 */
@Composable
private fun KeyTile(
    label: String,
    fill: ColorProducer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val c = Hl.colors
    val letter = label.length == 1
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.fill(rest = fill, restInk = c.content),
        shape = HlShapes.control,
        modifier = modifier.semantics { contentDescription = label },
    ) {
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = if (letter) HlSpace.none else HlSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(HlSize.icon))
                Spacer(Modifier.width(HlControl.iconGap))
            }
            Text(label, style = if (letter) Hl.type.lead else Hl.type.entry, maxLines = 1)
        }
    }
}

/**
 * Glass in the dark of the picture the page lies on ([LocalAmbient]), read while drawing: the room's glass without
 * one, a darker glass of its hue with one. Opaque, so a key reads on the black room as on a picture.
 */
@Composable
private fun tintedPlate(): ColorProducer {
    val c = Hl.colors
    val light = LocalAmbient.current
    return remember(c, light) { ColorProducer { c.plate(light).compositeOver(c.glass) } }
}

/** A search row's title in the poster's voice, then how many it holds in `meta`, on its baseline. */
@Composable
fun ResultsHeading(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier) {
        SectionHeader(title, Modifier.alignByBaseline())
        Spacer(Modifier.width(HlSpace.s))
        Text(
            count.toString(),
            style = Hl.type.meta,
            color = Hl.colors.contentSecondary,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * Under a row of results, the one whose light the page takes ([item], when it is of this row) by its name in
 * `cardName` and its year; the line keeps its room when empty, so nothing moves as focus does.
 */
@Composable
fun ResultName(item: MediaItem?, modifier: Modifier = Modifier) {
    WithItsPicture(item, modifier.fillMaxWidth().height(textRoom(Hl.type.cardName))) { shown ->
        Row {
            Text(
                shown.title,
                style = Hl.type.cardName,
                color = Hl.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            shown.year?.let { year ->
                Spacer(Modifier.width(HlSpace.s))
                Text(
                    year.toString(),
                    style = Hl.type.meta,
                    color = Hl.colors.contentSecondary,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
