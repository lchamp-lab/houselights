package app.houselights.ui.design.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.scrollEdges

/**
 * The two-pane layout of setup and settings screens: a side column, then scrolling content across a gap,
 * with no rule between; given its scroll [state], edges fade where more lies and focus stays
 * [HlSpace.fadeEdge] clear of them.
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
            Spacer(Modifier.width(HlSpace.xxl))
            val fades = if (state != null) Modifier.scrollEdges(state, room) else Modifier
            Box(Modifier.weight(1f).fillMaxHeight().then(fades)) {
                ListScroll(top = if (state != null) HlSpace.fadeEdge else HlSpace.none) { content() }
            }
        }
    }
}

/**
 * A form's screen (DESIGN.md, Secondary screens): its title, what it does and where it stands ([aside]) on the left,
 * the form on the right from the top of the safe area. With [room], it lies on those posters' field ([PosterField]),
 * its controls on plates as over a picture; without, on the black room.
 */
@Composable
fun HlFormScaffold(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    room: List<ImageRef>? = null,
    form: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    @Composable
    fun Screen() = HlTwoPane(
        side = {
            TaskTitle(title)
            Spacer(Modifier.height(HlSpace.m))
            Text(description, style = Hl.type.body, color = Hl.colors.contentSecondary)
            Spacer(Modifier.height(HlSpace.xl))
            aside()
        },
        sideWidth = HlLayout.sideColumnWide,
        state = scroll,
    ) {
        Column(
            Modifier.fillMaxSize().keepVerticalFocus().verticalScroll(scroll).padding(bottom = HlFocus.edge),
            verticalArrangement = Arrangement.spacedBy(HlSpace.l),
            content = form,
        )
    }
    if (room == null) {
        Screen()
    } else {
        Box(Modifier.fillMaxSize()) {
            PosterField(room)
            OnArtwork { Screen() }
        }
    }
}

/**
 * Where a form stands, beside it, on a plate: the state with its light, then what it is about, [name] in `lead` or
 * [label] in grey over [value] (an address, in the technical voice), [detail], its printed [badges] and [facts]; then
 * [content] and the last [result], an [AttentionNote] if [resultIsError].
 */
@Composable
fun StatusPanel(
    state: StatusState,
    stateText: String,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = Hl.type.spec,
    name: String? = null,
    detail: String? = null,
    badges: List<String> = emptyList(),
    facts: List<String?> = emptyList(),
    result: String? = null,
    resultIsError: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Hl.colors
    HlPlate(modifier.fillMaxWidth(), padding = PaddingValues(horizontal = HlInset.card, vertical = HlInset.plate)) {
        HlStatus(state, stateText)
        Spacer(Modifier.height(HlSpace.l))
        if (name != null) {
            Text(name, style = Hl.type.lead, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(HlSpace.xxs))
        } else {
            Text(label, style = Hl.type.meta, color = c.contentSecondary)
            Spacer(Modifier.height(HlSpace.xs))
        }
        Text(value, style = valueStyle, color = c.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Spacer(Modifier.height(HlSpace.xxs))
            Text(detail, style = Hl.type.meta, color = c.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (badges.isNotEmpty() || facts.any { !it.isNullOrBlank() }) {
            Spacer(Modifier.height(HlSpace.m))
            BadgedFactRow(badges, facts, lead = c.content, gap = HlSpace.s)
        }
        if (content != null || result != null) {
            Spacer(Modifier.height(HlSpace.m))
            content?.invoke(this)
            if (result != null) {
                if (content != null) Spacer(Modifier.height(HlSpace.m))
                if (resultIsError) {
                    AttentionNote(result, style = Hl.type.meta)
                } else {
                    Text(result, style = Hl.type.meta, color = c.content)
                }
            }
        }
    }
}

/** The name of a group of choices above them (a form's track), in `group`. */
@Composable
fun FieldLabel(text: String) {
    Text(text, style = Hl.type.group, color = Hl.colors.contentSecondary, modifier = Modifier.padding(bottom = HlSpace.xxs))
}
