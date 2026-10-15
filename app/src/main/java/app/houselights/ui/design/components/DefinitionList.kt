package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * Label and value pairs in two aligned columns, each pair on its first baseline. Set inside a
 * panel or a block already, so it adds no box of its own.
 */
@Composable
fun DefinitionList(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, labelWidth: Dp = HlLayout.definitionLabel) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        rows.forEach { (label, value) ->
            DefinitionRow(label, labelWidth = labelWidth) {
                Text(value, style = Hl.type.meta, color = Hl.colors.content)
            }
        }
    }
}

/**
 * One pair of a [DefinitionList] whose value is not plain text: a state with its light, a logo, a
 * value with a note under it. The label sits on the value's first baseline, as on the back of a case.
 */
@Composable
fun DefinitionRow(label: String, modifier: Modifier = Modifier, labelWidth: Dp = HlLayout.definitionLabel, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth()) {
        Text(
            label,
            style = Hl.type.caption,
            color = Hl.colors.contentSecondary,
            modifier = Modifier.width(labelWidth).alignByBaseline(),
        )
        Box(Modifier.weight(1f).alignByBaseline()) { value() }
    }
}

/**
 * One subject of a sheet or a settings pane (the technical sheet, an NFO, what a display receives),
 * set like the back of a Blu-ray case: a hairline, its name, then what it says.
 */
@Composable
fun InfoBlock(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        HlDivider()
        Spacer(Modifier.height(HlSpace.xs))
        Text(title, style = Hl.type.group, color = Hl.colors.contentSecondary)
        Spacer(Modifier.height(HlSpace.xs))
        content()
    }
}

/** One row of a [SpecTable]: what it is, then its values in column order; [inUse] marks the one in service. */
@Immutable
data class SpecTableRow(val name: String, val values: List<String>, val inUse: Boolean = false)

/**
 * Rows read as a table, like the tracks of the technical sheet: the ember dot on the row in
 * service, its name, then each value in its column, all in `meta` with tabular figures so the
 * figures line up down the table. [columns] gives the width of each value column but the last,
 * which takes what is left and wraps. Set in an [InfoBlock]; it adds no box of its own.
 */
@Composable
fun SpecTable(rows: List<SpecTableRow>, columns: List<Dp>, modifier: Modifier = Modifier, nameWidth: Dp = HlLayout.specLanguage) {
    val c = Hl.colors
    val marked = rows.any { it.inUse }
    Column(modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = HlSpace.xxs)) {
                if (marked) {
                    Box(
                        Modifier
                            .align(Alignment.CenterVertically)
                            .size(HlSize.dot)
                            .clip(HlShapes.avatar)
                            .then(if (row.inUse) Modifier.background(c.ember) else Modifier),
                    )
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
