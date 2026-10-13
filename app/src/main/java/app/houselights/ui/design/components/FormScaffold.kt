package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * Layout shared by setup screens: what this is and what is happening on the left,
 * the form on the right, separated by a hairline like the settings panes.
 */
@Composable
fun HlFormScaffold(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(null, BackdropStyle.Quiet)
        Row(Modifier.fillMaxSize().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)) {
            Column(Modifier.width(340.dp).fillMaxHeight()) {
                ScreenTitle(title)
                Spacer(Modifier.height(HlSpace.m))
                Text(description, style = Hl.type.body, color = Hl.colors.contentSecondary)
                Spacer(Modifier.height(HlSpace.xl))
                aside()
            }
            Spacer(Modifier.width(HlSpace.xl))
            Box(Modifier.width(HlSize.hairline).fillMaxHeight().padding(vertical = HlSpace.xl).background(Hl.colors.outline.copy(alpha = 0.5f)))
            Spacer(Modifier.width(HlSpace.xl))
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 72.dp, bottom = HlSpace.xxl, end = HlSpace.m),
                verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                content = form,
            )
        }
    }
}

/** Status panel of a setup screen: an icon in a disc, a label and a value, then a result line. */
@Composable
fun StatusPanel(
    icon: ImageVector,
    label: String,
    value: String,
    lit: Boolean,
    result: String?,
    resultIsError: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = Hl.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(HlShapes.panel)
            .background(c.surface.copy(alpha = 0.8f))
            .border(HlSize.hairline, if (lit) c.tungsten.copy(alpha = 0.6f) else c.outline.copy(alpha = 0.6f), HlShapes.panel)
            .padding(HlSpace.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(HlShapes.avatar).background(if (lit) c.tungsten.copy(alpha = 0.2f) else c.content.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (lit) c.tungsten else c.contentSecondary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(HlSpace.m))
            Column {
                Text(label, style = Hl.type.caption, color = c.contentTertiary)
                Text(value, style = Hl.type.label, color = c.content, maxLines = 3)
            }
        }
        if (result != null) {
            Spacer(Modifier.height(HlSpace.m))
            Text(result, style = Hl.type.bodySmall, color = if (resultIsError) c.tungsten else c.content)
        }
    }
}

/** Small label above a control. */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.padding(bottom = HlSpace.xs - 2.dp))
}
