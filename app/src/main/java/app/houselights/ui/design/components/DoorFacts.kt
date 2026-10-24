package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults

// The settings door's state (DESIGN.md, Layout): facts as on a case's back, kept by rank when they do not all fit.

/**
 * One fact of the settings' door: its [label] over its [value], a [detail] after it (quiet; unless [quietDetail], written
 * as the value, only the dot between them quiet), a [done] check or the [attention] mark before it, on a line of its own
 * when [wide]. When the door cannot hold them all, the fact of lowest [rank] goes first, its detail before it.
 */
@Immutable
data class StateFact(
    val label: String,
    val value: String,
    val detail: String? = null,
    val done: Boolean = false,
    val wide: Boolean = false,
    val quietDetail: Boolean = true,
    val attention: Boolean = false,
    val rank: Int = 0,
)

/**
 * The app's state as a case's back (DESIGN.md, Gestures), in a door: each label in the technical voice over its
 * value, two short ones side by side while they fit, a [StateFact.wide] one on its own line; centred down the door's
 * picture. What the picture has no room for (a long name, the largest text) goes by rank ([keptFacts]).
 */
@Composable
fun DoorFacts(facts: List<StateFact>, modifier: Modifier = Modifier) {
    val ordered = facts.sortedBy { it.wide }
    // Each fact with a detail is also laid out without it, for when it must give it up.
    val detailed = ordered.indices.filter { ordered[it].detail != null }
    Layout(
        content = {
            ordered.forEach { Fact(it) }
            // Read once, whole: the copy is only drawn.
            detailed.forEach { Fact(ordered[it].copy(detail = null), Modifier.clearAndSetSemantics {}) }
        },
        modifier = modifier.fillMaxSize().padding(horizontal = HlSpace.s),
    ) { measurables, constraints ->
        val rowGap = HomeGridDefaults.stateRows.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val full = measurables.take(ordered.size).map { it.measure(loose) }
        val bare = detailed.zip(measurables.drop(ordered.size)).associate { (k, m) -> k to m.measure(loose) }
        val kept = keptFacts(
            FactSizes(ordered.map { it.wide }, ordered.map { it.rank }, full.map { it.width }, bare.mapValues { it.value.width }),
            full.map { it.height },
            HomeGridDefaults.stateColumns.roundToPx(),
            rowGap,
            IntSize(loose.maxWidth, loose.maxHeight),
        )
        val placeable = { k: Int -> if (k in kept.bare) bare.getValue(k) else full[k] }
        val heights = kept.lines.map { line -> line.maxOf { placeable(it).height } }
        val used = heights.sum() + rowGap * (heights.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, constraints.maxHeight) {
            var y = (constraints.maxHeight - used) / 2
            kept.lines.forEachIndexed { i, line ->
                var x = 0
                line.forEach { k ->
                    placeable(k).placeRelative(x, y)
                    x += placeable(k).width + HomeGridDefaults.stateColumns.roundToPx()
                }
                y += heights[i] + rowGap
            }
        }
    }
}

/** The facts' parts the door measures: whether each is [wide], its [rank], its width, and without its detail ([bare]). */
internal class FactSizes(val wide: List<Boolean>, val rank: List<Int>, val widths: List<Int>, val bare: Map<Int, Int>)

/** The facts a door shows, as lines of indices, and those shown without their detail. */
internal class KeptFacts(val lines: List<List<Int>>, val bare: Set<Int>)

/**
 * The facts of [sizes] whose lines ([factLines]) fit in [room], [heights] tall, [gap] across and [rowGap] down: all of
 * them while they fit; else the one of lowest rank gives up its detail, then goes, and so on.
 */
internal fun keptFacts(sizes: FactSizes, heights: List<Int>, gap: Int, rowGap: Int, room: IntSize): KeptFacts {
    val shown = sizes.wide.indices.toMutableList()
    val bare = mutableSetOf<Int>()
    while (true) {
        val widths = shown.map { k -> if (k in bare) sizes.bare.getValue(k) else sizes.widths[k] }
        val lines = factLines(shown.map { sizes.wide[it] }, widths, gap, room.width).map { line -> line.map { shown[it] } }
        val tall = lines.sumOf { line -> line.maxOf { heights[it] } } + rowGap * (lines.size - 1).coerceAtLeast(0)
        if (tall <= room.height || shown.isEmpty()) return KeptFacts(lines, bare)
        val least = shown.minBy { sizes.rank[it] }
        if (least in sizes.bare && least !in bare) bare += least else shown -= least
    }
}

/** The facts by line, as indices: two short ones side by side when [widths] fit [room] with their [gap], a wide one alone. */
internal fun factLines(wide: List<Boolean>, widths: List<Int>, gap: Int, room: Int): List<List<Int>> {
    val lines = mutableListOf<List<Int>>()
    var waiting: Int? = null
    wide.indices.forEach { k ->
        val held = waiting
        when {
            wide[k] -> {
                held?.let { lines += listOf(it) }
                waiting = null
                lines += listOf(k)
            }
            held == null -> waiting = k
            widths[held] + gap + widths[k] <= room -> {
                lines += listOf(held, k)
                waiting = null
            }
            else -> {
                lines += listOf(held)
                waiting = k
            }
        }
    }
    waiting?.let { lines += listOf(it) }
    return lines
}

@Composable
private fun Fact(fact: StateFact, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Column(modifier) {
        Text(fact.label, style = Hl.type.jaquette, color = c.contentTertiary, maxLines = 1)
        Spacer(Modifier.height(HlSpace.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                fact.attention -> StatusLight(StatusState.Attention)
                fact.done -> Icon(HlIcons.Check, null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.inlineIcon))
            }
            if (fact.attention || fact.done) Spacer(Modifier.width(HlSpace.xs))
            val ink = if (fact.attention) c.ember else c.content
            Text(fact.value, style = Hl.type.meta, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            fact.detail?.let {
                Text(DETAIL_JOIN, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
                val detailInk = if (fact.quietDetail) c.contentSecondary else ink
                Text(it, style = Hl.type.meta, color = detailInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val DETAIL_JOIN = " · "
