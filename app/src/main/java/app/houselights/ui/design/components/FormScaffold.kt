package app.houselights.ui.design.components

import app.houselights.ui.design.TwoPaneDefaults
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.OnArtwork
import app.houselights.ui.design.fadeIntoField
import app.houselights.ui.design.keepVerticalFocus
import app.houselights.ui.design.scrollEdges

/**
 * The two-pane layout of setup and settings screens: a side column, then scrolling content across a gap,
 * with no rule between; given its scroll [state], edges fade where more lies and focus stays
 * [HlSpace.fadeEdge] clear of them. With [fadeOnField], on a field of posters, the content ends at the safe area and
 * fades into the field itself ([fadeIntoField]), where a fade into black would not show over its plates, as dark.
 */
@Composable
fun HlTwoPane(
    side: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    sideWidth: Dp = TwoPaneDefaults.side,
    state: ScrollableState? = null,
    fadeOnField: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val black = Hl.colors.background
    // On a field of posters, the edges fade short of black, read while drawing: the pane is read on the field.
    val room = LocalRoomField.current
    val fadeInto = remember(room, black) { { room?.edge(black) ?: black } }
    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)) {
            Column(Modifier.width(sideWidth).fillMaxHeight().padding(bottom = HlSpace.safeV), content = side)
            Spacer(Modifier.width(HlSpace.xxl))
            val fades = when {
                state == null -> Modifier
                // On the field, the list stops at the safe area as it starts there, and its foot fades inside the screen.
                fadeOnField && room != null ->
                    Modifier.padding(bottom = HlSpace.safeV).fadeIntoField(state, start = HlSpace.xxl, end = HlSpace.safeH)
                else -> Modifier.scrollEdges(state, fadeInto)
            }
            Box(Modifier.weight(1f).fillMaxHeight().then(fades)) {
                ListScroll(top = if (state != null) HlSpace.fadeEdge else HlSpace.none) { content() }
            }
        }
    }
}

/**
 * A form's screen (DESIGN.md, Secondary screens): its title, what it does and where it stands ([aside]) on the left,
 * the form on the right from the top of the safe area, scrolled by [scroll]. With [room], it lies on those posters'
 * field ([PosterField]), its controls on plates as over a picture, the form ending at the safe area and fading into the
 * field where it scrolls; without, on the black room.
 */
@Composable
fun HlFormScaffold(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    room: List<ImageRef>? = null,
    scroll: ScrollState = rememberScrollState(),
    form: @Composable ColumnScope.() -> Unit,
) {
    FormPanes(title, description, aside, scroll, room, modifier) {
        Column(
            // On a field the list already stops at the safe area: room for a focus frame is enough under its last part.
            Modifier
                .fillMaxSize()
                .keepVerticalFocus()
                .verticalScroll(scroll)
                .padding(bottom = if (room != null) HlFocus.reach else HlFocus.edge),
            verticalArrangement = Arrangement.spacedBy(HlSpace.l),
            content = form,
        )
    }
}

/**
 * [HlFormScaffold] whose form is a lazy list held by [list], each of its items composed only when shown, as a level of
 * hundreds of folders is (« Ajouter un dossier »); its items set the gaps between them. The same column on the left, the
 * same room.
 */
@Composable
fun HlFormScaffold(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    list: LazyListState,
    modifier: Modifier = Modifier,
    room: List<ImageRef>? = null,
    form: LazyListScope.() -> Unit,
) {
    FormPanes(title, description, aside, list, room, modifier) {
        LazyColumn(
            Modifier.fillMaxSize().keepVerticalFocus(),
            state = list,
            contentPadding = PaddingValues(bottom = HlFocus.edge),
            content = form,
        )
    }
}

/**
 * A form's two panes, on [room]'s field or the black room: its [title], what it does ([description]) and [aside] on the
 * left, [form] on the right, scrolled by [state].
 */
@Composable
private fun FormPanes(
    title: String,
    description: String,
    aside: @Composable ColumnScope.() -> Unit,
    state: ScrollableState,
    room: List<ImageRef>?,
    modifier: Modifier = Modifier,
    form: @Composable BoxScope.() -> Unit,
) {
    val side: @Composable ColumnScope.() -> Unit = {
        TaskTitle(title)
        // From its baselines: its first under the title, its last over what stands beside the form.
        Text(
            description,
            style = Hl.type.body,
            color = Hl.colors.contentSecondary,
            modifier = Modifier.paddingFromBaseline(top = HlSpace.xl, bottom = HlSpace.xl),
        )
        aside()
    }
    if (room == null) {
        HlTwoPane(side, modifier, TwoPaneDefaults.sideWide, state, fadeOnField = true, content = form)
    } else {
        PosterField(room, modifier) {
            OnArtwork { HlTwoPane(side, sideWidth = TwoPaneDefaults.sideWide, state = state, fadeOnField = true, content = form) }
        }
    }
}

/**
 * Where a form stands, beside it, on a plate: the state with its light, then what it is about, [name] in `entry` or
 * [label] in grey over [value] (an address, in the technical voice), [detail], its printed [badges] and [facts]; then
 * [content] and the last [result], an [AttentionNote] if [resultIsError]. One mark a failure: a failure's mark is its
 * message's, beside its cause (here, or under the field at fault), and the state then says it by the ring
 * ([StatusState.Off]), never [StatusState.Attention].
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
    // Its state as near its top as a reading plate's name; what it is about where the board sets it.
    HlPlate(
        modifier.fillMaxWidth(),
        padding = PaddingValues(start = HlInset.card, end = HlInset.card, top = HlInset.reading, bottom = HlInset.plate),
    ) {
        HlStatus(state, stateText)
        Spacer(Modifier.height(HlSpace.fact))
        if (name != null) {
            Text(name, style = Hl.type.entry, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    AttentionNote(result)
                } else {
                    Text(result, style = Hl.type.meta, color = c.content)
                }
            }
        }
    }
}

/** The name of a group of choices above them (a form's track), in `group`. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Hl.type.group, color = Hl.colors.contentSecondary, modifier = modifier.padding(bottom = HlSpace.xxs))
}
