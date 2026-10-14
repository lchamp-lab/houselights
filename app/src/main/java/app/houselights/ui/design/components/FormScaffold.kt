package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.keepVerticalFocus

/**
 * The two-pane layout of setup and settings screens: what this is (and its navigation) in a
 * side column on the left, the content on the right, separated by a hairline.
 */
@Composable
fun HlTwoPane(
    side: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Backdrop(null, BackdropStyle.Quiet)
        Row(Modifier.fillMaxSize().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)) {
            Column(Modifier.width(HlLayout.sideColumn).fillMaxHeight(), content = side)
            Spacer(Modifier.width(HlSpace.xl))
            HlDivider(Modifier.padding(vertical = HlSpace.xl), vertical = true)
            Spacer(Modifier.width(HlSpace.xl))
            Box(Modifier.weight(1f).fillMaxHeight(), content = content)
        }
    }
}

/** Setup screens: what this is and what is happening on the left, the form on the right. */
@Composable
fun HlFormScaffold(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    HlTwoPane(
        side = {
            ScreenTitle(title)
            Spacer(Modifier.height(HlSpace.m))
            Text(description, style = Hl.type.body, color = Hl.colors.contentSecondary)
            Spacer(Modifier.height(HlSpace.xl))
            aside()
        },
    ) {
        Column(
            Modifier.fillMaxSize().keepVerticalFocus().verticalScroll(rememberScrollState())
                .padding(top = HlLayout.paneTop, bottom = HlSpace.xxl, end = HlSpace.m),
            verticalArrangement = Arrangement.spacedBy(HlSpace.l),
            content = form,
        )
    }
}

/** Status of a setup screen: an icon in a disc, a label and a value, then a result line. */
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
    HlPanel(modifier.fillMaxWidth(), lit = lit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HlIconDisc(icon, lit = lit)
            Spacer(Modifier.width(HlSpace.m))
            Column {
                Text(label, style = Hl.type.caption, color = c.contentSecondary)
                Text(value, style = Hl.type.meta, color = c.content, maxLines = 3)
            }
        }
        if (result != null) {
            Spacer(Modifier.height(HlSpace.m))
            Text(result, style = Hl.type.caption, color = if (resultIsError) c.tungsten else c.content)
        }
    }
}

/** Small label above a control. */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.padding(bottom = HlSpace.xs))
}
