package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSpace

/** Label and value pairs in two aligned columns, on a quiet panel (settings, NFO, diagnostics). */
@Composable
fun DefinitionList(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, labelWidth: Dp = 220.dp) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(HlShapes.panel)
            .background(Hl.colors.surface.copy(alpha = 0.6f))
            .padding(horizontal = HlSpace.l, vertical = HlSpace.m),
        verticalArrangement = Arrangement.spacedBy(HlSpace.s),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = Hl.type.bodySmall, color = Hl.colors.contentTertiary, modifier = Modifier.width(labelWidth))
                Text(value, style = Hl.type.bodySmall, color = Hl.colors.content)
            }
        }
    }
}
