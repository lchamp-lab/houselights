package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.PaneDefaults

/**
 * What a pane states, as a case's back prints it (DESIGN.md, Secondary screens): a plate ([HlPlate]) of [facts]
 * across, [gap] apart ([PlateFact]), then a [note] in secondary `meta`. Nothing in it takes focus.
 */
@Composable
fun FactPlate(
    modifier: Modifier = Modifier,
    note: String? = null,
    gap: Dp = HlSpace.xxl,
    facts: @Composable RowScope.() -> Unit,
) {
    HlPlate(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap), content = facts)
        if (note != null) {
            Spacer(Modifier.height(HlSpace.l))
            Text(note, style = Hl.type.meta, color = Hl.colors.contentSecondary)
        }
    }
}

/**
 * One thing a [FactPlate] states: its [label] in secondary `meta` over its [value], words in `entry` or, when
 * [technical] (a format, a figure), in `spec`; a [measure] follows on its baseline in secondary `meta`
 * ("1920 × 1080"), and a [state] says itself by its dot before it.
 */
@Composable
fun PlateFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    technical: Boolean = false,
    measure: String? = null,
    state: StatusState? = null,
) {
    val c = Hl.colors
    Column(modifier) {
        Text(label, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(HlGap.labelToValue))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state != null) {
                StateDot(state)
                Spacer(Modifier.width(HlGap.markToWord))
            }
            Text(
                value,
                style = if (technical) Hl.type.spec else Hl.type.entry,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
            if (measure != null) {
                Spacer(Modifier.width(HlSpace.s))
                Text(measure, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

/** A state's light alone, flush with the words above it; attention keeps its circled mark. */
@Composable
private fun StateDot(state: StatusState) {
    if (state == StatusState.Attention) StatusLight(state) else StatusLight(state, Modifier.size(HlSize.dot))
}

/** One row of a [SpecTable]: its [name], then [values] in column order; [inUse] marks the active row. */
@Immutable
data class SpecTableRow(val name: String, val values: List<String>, val inUse: Boolean = false)

/**
 * Rows set as a table in `meta` with tabular figures, the active row's state by its white dot; [columns] sets
 * every value column's width but the last, which takes the rest and wraps.
 */
@Composable
fun SpecTable(rows: List<SpecTableRow>, columns: List<Dp>, modifier: Modifier = Modifier, nameWidth: Dp = PaneDefaults.specName) {
    val c = Hl.colors
    val marked = rows.any { it.inUse }
    Column(modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = HlSpace.xxs)) {
                if (marked) {
                    // A state, white; the others keep its room.
                    Box(Modifier.align(Alignment.CenterVertically).size(HlSize.dot)) { if (row.inUse) StateDot(StatusState.On) }
                    Spacer(Modifier.width(HlSpace.s))
                }
                Text(
                    row.name,
                    style = Hl.type.meta,
                    color = c.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(nameWidth).alignByBaseline(),
                )
                row.values.forEachIndexed { i, value ->
                    val width = columns.getOrNull(i)
                    Text(
                        value,
                        style = Hl.type.meta,
                        color = c.contentSecondary,
                        maxLines = if (width != null) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                        modifier = (if (width != null) Modifier.width(width) else Modifier.weight(1f)).alignByBaseline(),
                    )
                }
            }
        }
    }
}
