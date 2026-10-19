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
import androidx.compose.foundation.shape.CircleShape
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
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/** Label and value pairs in two aligned columns, on their first baselines; no container of its own. */
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

/** A [DefinitionList] pair whose value is not plain text (a state with its light, a logo). */
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

/** A sheet or settings section (the technical sheet, an NFO): a hairline, its title, its content. */
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

/** One row of a [SpecTable]: its [name], then [values] in column order; [inUse] marks the active row. */
@Immutable
data class SpecTableRow(val name: String, val values: List<String>, val inUse: Boolean = false)

/**
 * Rows set as a table in `meta` with tabular figures, the ember dot on the active row; [columns] sets
 * every value column's width but the last, which takes the rest and wraps.
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
                            // Dots are the only round things.
                            .size(HlSize.dot).clip(CircleShape)
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
