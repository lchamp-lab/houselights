package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSpace

/**
 * Label and value pairs in two aligned columns. Set inside a panel or a block already, so it adds
 * no box of its own.
 */
@Composable
fun DefinitionList(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, labelWidth: Dp = HlLayout.definitionLabel) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.width(labelWidth))
                Text(value, style = Hl.type.meta, color = Hl.colors.content)
            }
        }
    }
}

/**
 * One subject of a sheet (the technical sheet, an NFO), set like the back of a Blu-ray case: a
 * hairline, its name, then what it says.
 */
@Composable
fun InfoBlock(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        HlDivider()
        Spacer(Modifier.height(HlSpace.xs))
        Text(title, style = Hl.type.caption, color = Hl.colors.contentSecondary)
        Spacer(Modifier.height(HlSpace.xs))
        content()
    }
}
