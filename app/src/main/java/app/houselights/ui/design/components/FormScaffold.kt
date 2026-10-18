package app.houselights.ui.design.components

import androidx.compose.foundation.gestures.ScrollableState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.scrollEdges

/**
 * The two-pane layout of setup and settings screens: a side column, a hairline, then scrolling content;
 * given its scroll [state], edges fade where more lies and focus stays [HlSpace.fadeEdge] clear of them.
 */
@Composable
fun HlTwoPane(
    side: @Composable ColumnScope.() -> Unit,
    sideWidth: Dp = HlLayout.sideColumn,
    state: ScrollableState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val room = Hl.colors.background
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)) {
            Column(Modifier.width(sideWidth).fillMaxHeight().padding(bottom = HlSpace.safeV), content = side)
            Spacer(Modifier.width(HlSpace.xl))
            HlDivider(Modifier.padding(vertical = HlSpace.xl), vertical = true)
            Spacer(Modifier.width(HlSpace.xl))
            val fades = if (state != null) Modifier.scrollEdges(state, room) else Modifier
            Box(Modifier.weight(1f).fillMaxHeight().then(fades)) {
                ListScroll(top = if (state != null) HlSpace.fadeEdge else HlSpace.none) { content() }
            }
        }
    }
}

/** Setup screen layout: title, description and status on the left, the form on the right. */
@Composable
fun HlFormScaffold(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    form: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    HlTwoPane(
        side = {
            ScreenTitle(title)
            Spacer(Modifier.height(HlSpace.m))
            Text(description, style = Hl.type.body, color = Hl.colors.contentSecondary)
            Spacer(Modifier.height(HlSpace.xl))
            aside()
        },
        sideWidth = HlLayout.sideColumnWide,
        state = scroll,
    ) {
        Column(
            Modifier.fillMaxSize().keepVerticalFocus().verticalScroll(scroll)
                .padding(top = HlLayout.paneTop, bottom = HlFocus.edge, end = HlSpace.m),
            verticalArrangement = Arrangement.spacedBy(HlSpace.l),
            content = form,
        )
    }
}

/**
 * A setup's status beside its form: the state with its light, [label] and [value] (an address), [detail]
 * and [badges]; under a hairline, [content] and the last [result], an [AttentionNote] if [resultIsError].
 */
@Composable
fun StatusPanel(
    state: StatusState,
    stateText: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = Hl.type.spec,
    detail: String? = null,
    badges: List<String> = emptyList(),
    result: String? = null,
    resultIsError: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Hl.colors
    HlPanel(modifier.fillMaxWidth(), lit = state == StatusState.On) {
        HlStatus(state, stateText)
        Spacer(Modifier.height(HlSpace.l))
        Text(label, style = Hl.type.caption, color = c.contentSecondary)
        Spacer(Modifier.height(HlSpace.xxs))
        Text(value, style = valueStyle, color = c.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(detail, style = Hl.type.meta, color = c.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (badges.isNotEmpty()) {
            Spacer(Modifier.height(HlSpace.s))
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) { badges.forEach { Badge(it) } }
        }
        if (content != null || result != null) {
            Spacer(Modifier.height(HlSpace.l))
            HlDivider()
            Spacer(Modifier.height(HlSpace.m))
            content?.invoke(this)
            if (result != null) {
                if (content != null) Spacer(Modifier.height(HlSpace.m))
                if (resultIsError) {
                    AttentionNote(result)
                } else {
                    Text(result, style = Hl.type.caption, color = c.content)
                }
            }
        }
    }
}

/** Small label above a control. */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = Hl.type.caption, color = Hl.colors.contentSecondary, modifier = Modifier.padding(bottom = HlSpace.xs))
}
