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
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSpace

/** Label and value pairs in two aligned columns, on a quiet panel (settings, NFO, diagnostics). */
@Composable
fun DefinitionList(rows: List<Pair<String, String>>, modifier: Modifier = Modifier, labelWidth: Dp = HlLayout.definitionLabel) {
    HlPanel(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            rows.forEach { (label, value) ->
                Row {
                    Text(label, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.width(labelWidth))
                    Text(value, style = Hl.type.meta, color = Hl.colors.content)
                }
            }
        }
    }
}
