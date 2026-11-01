package app.houselights.ui.design.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFrom
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.tv.material3.Text
import app.houselights.ui.design.EdgeScroll
import app.houselights.ui.design.Gradation
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.PaneDefaults
import app.houselights.ui.design.edge
import app.houselights.ui.design.plate

/** The screen title's baseline from its top, at the viewer's text size: where a pane's and a sheet's head set their own. */
@Composable
internal fun screenTitleBaseline(): Dp {
    val measurer = rememberTextMeasurer()
    val headline = Hl.type.headline
    val density = LocalDensity.current
    return remember(headline, density) { with(density) { measurer.measure("H", headline).firstBaseline.toDp() } }
}

/**
 * Where a sentence set right under a [title]'s box places its first baseline, from its own top: so that baseline falls
 * a title's line under the title's ([HlGap.titleToBody]), the title's descent taken off.
 */
@Composable
internal fun sentenceUnder(title: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val descent = remember(title, density) {
        with(density) { measurer.measure("H", title).let { it.size.height - it.firstBaseline }.toDp() }
    }
    return HlGap.titleToBody(title) - descent
}

/**
 * A settings pane's head (DESIGN.md, Secondary screens): its [title] in `subject` on the screen title's baseline,
 * an [aside] on that line in secondary `meta` after its [state]'s dot, then a [sentence] in secondary `body` or a
 * paragraph to [read] in white `body`. Its box ends on the title's baseline, or under its text, so what follows is
 * [HlSpace.l] away.
 */
@Composable
fun PaneHead(
    title: String,
    modifier: Modifier = Modifier,
    aside: String? = null,
    state: StatusState? = null,
    sentence: String? = null,
    read: String? = null,
) {
    val c = Hl.colors
    val baseline = screenTitleBaseline()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.endAt(baseline)) {
            Text(
                title,
                style = Hl.type.subject,
                color = c.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Its baseline on the screen title's, which the aside's is aligned on.
                modifier = Modifier.alignByBaseline().weight(1f, fill = false).paddingFrom(FirstBaseline, before = baseline),
            )
            if (aside != null) {
                Spacer(Modifier.width(HlSpace.fact))
                Row(Modifier.alignByBaseline(), verticalAlignment = Alignment.CenterVertically) {
                    if (state != null) {
                        StatusLight(state, Modifier.size(HlSize.dot))
                        Spacer(Modifier.width(HlGap.markToWord))
                    }
                    Text(aside, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (sentence != null) {
            Text(
                sentence,
                style = Hl.type.body,
                color = c.contentSecondary,
                // A title's line under the title's baseline; its box ends a little under its own.
                modifier = Modifier.widthIn(max = HlLayout.readableNarrow)
                    .paddingFromBaseline(top = HlGap.titleToBody(Hl.type.subject), bottom = HlSpace.xxs),
            )
        }
        if (read != null) {
            Text(
                read,
                style = Hl.type.body,
                color = c.content,
                // The lines' room under the last, as a page of running text keeps it.
                modifier = Modifier.widthIn(max = HlLayout.readableNarrow).paddingFromBaseline(top = HlSpace.xxl, bottom = HlSpace.m),
            )
        }
    }
}

/**
 * Ends a line's box [at] from its top, the rest of the line hanging below; its title sets its own baseline there. Not
 * the line's baseline, read here: Compose 1.10 on keeps the aside nested in the line where it stood before the line
 * moved, in the positions it reports (not in what it draws).
 */
private fun Modifier.endAt(at: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, at.roundToPx()) { placeable.placeRelative(0, 0) }
}

/**
 * About's head: the [Lockup] at [HlSize.wordmark], its name's foot on the screen title's baseline as a pane's title
 * would be; its box ends under the mark.
 */
@Composable
fun PaneLockup(modifier: Modifier = Modifier) {
    val capitals = HlSize.wordmark
    val overhang = capitals * ((Gradation.LOCKUP_MARK - 1f) / 2f)
    Lockup(capitals, modifier.padding(top = screenTitleBaseline() - capitals, bottom = overhang))
}

/** A group's [title] ([GroupTitle]) over what it holds (a [FactPlate], a [DefinitionPlate]), as [HlSettingGroup] sets it. */
@Composable
fun HlGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        GroupTitle(title)
        content()
    }
}

/**
 * A pane's row of [Showcase]s, [HlSpace.m] apart, running on to the screen's end where the next one waits; a focused
 * one comes inside the safe area with its frame, clear of the row's start. Its [state] scrolls it to a showcase it has
 * not composed yet, a folder just added.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowcaseRow(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    val density = LocalDensity.current
    // At the end, the safe area and the frame's reach past the showcase.
    val end = HlSpace.safeH + HlFocus.reach
    val spec = remember(density) { with(density) { EdgeScroll(HlSpace.s.toPx(), end.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyRow(
            modifier.bleed(start = HlSpace.s, end = HlSpace.safeH),
            state = state,
            contentPadding = PaddingValues(start = HlSpace.s, end = end),
            horizontalArrangement = Arrangement.spacedBy(HlSpace.m),
            content = content,
        )
    }
}

/** Draws [start] and [end] past the room it is given, its own width unchanged: a pane's list or row running to the screen's edge. */
internal fun Modifier.bleed(start: Dp = HlSpace.none, end: Dp = HlSpace.none): Modifier = layout { measurable, constraints ->
    val before = start.roundToPx()
    val extra = before + end.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else Constraints.Infinity
    val placeable = measurable.measure(constraints.copy(minWidth = if (constraints.hasBoundedWidth) wide else 0, maxWidth = wide))
    val width = constraints.constrainWidth(placeable.width - extra)
    layout(width, placeable.height) { placeable.placeRelative(-before, 0) }
}

/**
 * Pairs printed on one line as on a case's back (About's version and licence): each label in secondary `meta`, its
 * value in `jaquette`, on a plate of the light it lies on.
 */
@Composable
fun JaquetteLine(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val light = LocalAmbient.current
    Row(
        modifier
            .drawBehind { drawRoundRect(c.plate(light), cornerRadius = CornerRadius(HlShapes.control.topStart.toPx(size, this))) }
            .border(HlSize.hairline, c.edge, HlShapes.control)
            .padding(horizontal = HlSpace.s, vertical = HlSpace.xxs),
    ) {
        facts.forEachIndexed { i, (label, value) ->
            if (i > 0) Spacer(Modifier.width(HlSpace.l))
            Text(label, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(HlSpace.m))
            Text(value, style = Hl.type.jaquette, color = c.content, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
        }
    }
}

/** Definitions on a plate (About's credits): each [PlateDefinition] a line, [HlSpace.m] apart. Nothing in it takes focus. */
@Composable
fun DefinitionPlate(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HlPlate(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m), content = content)
    }
}

/**
 * A [DefinitionPlate]'s line: its [label] in secondary `meta` in [PaneDefaults.plateLabel], its [value] on its baseline
 * (or level with its top, a logo), then a [note] under the value in secondary `meta`.
 */
@Composable
fun PlateDefinition(label: String, modifier: Modifier = Modifier, note: String? = null, value: @Composable () -> Unit) {
    val c = Hl.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                label,
                style = Hl.type.meta,
                color = c.contentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(PaneDefaults.plateLabel).alignByBaseline(),
            )
            Box(Modifier.weight(1f).alignByBaseline()) { value() }
        }
        if (note != null) {
            Text(
                note,
                style = Hl.type.meta,
                color = c.contentSecondary,
                modifier = Modifier.padding(start = PaneDefaults.plateLabel, top = HlGap.nameToNoteWrapped),
            )
        }
    }
}

/**
 * A part of a meter stated as a case's back (Stockage): its ink's square before its [label] in secondary `meta`, then
 * its [value], a figure in `spec` or, not [technical], words in `entry`; an [empty] part's square is its ring.
 */
@Composable
fun MeterFact(
    label: String,
    value: String,
    ink: MeterInk,
    modifier: Modifier = Modifier,
    technical: Boolean = true,
    empty: Boolean = false,
) {
    val c = Hl.colors
    val tone = ink.color()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(HlSize.swatch).drawBehind {
                    if (empty) {
                        val line = HlSize.dotRing.toPx()
                        drawRect(tone, Offset(line / 2, line / 2), Size(size.width - line, size.height - line), style = Stroke(line))
                    } else {
                        drawRect(tone)
                    }
                },
            )
            Spacer(Modifier.width(HlSpace.xs))
            Text(label, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(HlGap.labelToValue))
        Text(value, style = if (technical) Hl.type.spec else Hl.type.entry, color = c.content, maxLines = 1)
    }
}
