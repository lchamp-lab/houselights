package app.houselights.ui.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatDuration
import java.util.Locale
import app.houselights.ui.util.NBSP

/**
 * Facts about a title, separated by space rather than punctuation: year, length,
 * age rating (as the badge it really is), score, then genres in a quieter tone. On one line:
 * what does not fit is left out whole, from the end.
 */
@Composable
fun MetaLine(item: MediaItem, modifier: Modifier = Modifier, showGenres: Boolean = true) {
    val c = Hl.colors
    val seasons = item.childCount?.takeIf { item.kind == MediaKind.SERIES && it > 0 }
        ?.let { pluralStringResource(R.plurals.season_count, it, it) }
    val films = item.childCount?.takeIf { item.kind == MediaKind.COLLECTION }?.let { owned ->
        item.totalCount?.let { total -> pluralStringResource(R.plurals.film_count_of, owned, owned, total) }
            ?: pluralStringResource(R.plurals.film_count, owned, owned)
    }
    val years = when {
        item.kind == MediaKind.COLLECTION && item.year != null && item.endYear != null -> "${item.year} – ${item.endYear}"
        item.kind != MediaKind.SERIES -> item.year?.toString()
        item.year != null && item.continuing -> "${item.year} –"
        item.year != null && item.endYear != null && item.endYear != item.year -> "${item.year} – ${item.endYear}"
        else -> item.year?.toString()
    }
    val length = when (item.kind) {
        MediaKind.SERIES -> seasons
        MediaKind.COLLECTION -> films
        else -> item.runtimeMs?.let(::formatDuration)
    }

    // In order of what matters: when the line runs out of room, genres go first, then the score.
    OneLine(modifier) {
        years?.let { Text(it, style = Hl.type.meta, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        length?.let { Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item.officialRating?.let { Badge(it) }
        item.communityRating?.let { Score(it) }
        // Genres go one by one, from the last, as the names of the billing block do.
        if (showGenres) item.genres.take(3).forEachIndexed { i, genre ->
            Text(
                if (i == 0) genre else ", $genre",
                style = Hl.type.meta,
                color = c.contentTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (i == 0) Modifier else Modifier.layoutId(Continued),
            )
        }
    }
}

/**
 * A score out of ten, as a fact among the others: the line star, then the figure, both quiet. A
 * score is not a state: it never lights.
 */
@Composable
fun Score(score: Double, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(HlIcons.Star, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.inlineIcon))
        Spacer(Modifier.width(HlSpace.xxs))
        Text(String.format(Locale.getDefault(), "%.1f", score), style = Hl.type.meta, color = c.contentSecondary)
    }
}

/** Hairline badge for ratings and technical formats; [lit] in tungsten for a state (in use). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, lit: Boolean = false) {
    val c = Hl.colors
    Text(
        text,
        style = Hl.type.badge,
        color = if (lit) c.tungsten else c.contentSecondary,
        modifier = modifier
            .border(HlSize.hairline, if (lit) c.tungsten.copy(alpha = HlAlpha.BORDER) else c.outline, HlShapes.badge)
            .padding(horizontal = HlControl.badgePaddingH, vertical = HlControl.badgePaddingV),
    )
}

/**
 * Formats of a title, in the order given; they wrap to a second line rather than run out of room.
 * On [oneLine] (a line of fixed height), the last ones are left out instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier, oneLine: Boolean = false) {
    if (badges.isEmpty()) return
    if (oneLine) {
        OneLine(modifier, spacing = HlSpace.xs, squeezeFirst = false) { badges.forEach { Badge(Definition.label(it)) } }
        return
    }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(HlSpace.xs),
        verticalArrangement = Arrangement.spacedBy(HlSpace.xs),
    ) { badges.forEach { Badge(Definition.label(it)) } }
}

/**
 * The line naming a title over a wall: its name, its facts, its formats, on one line of fixed
 * height. When room runs short, the formats go first, from the last; then the name gives way,
 * with an ellipsis. The facts stay, dropping their own from the end only past that.
 */
@Composable
fun TitleLine(item: MediaItem, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(
            { Text(item.title, style = Hl.type.entry, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            { MetaLine(item, showGenres = false) },
            { BadgeRow(item.badges.take(TITLE_LINE_BADGES), oneLine = true) },
        ),
        modifier = modifier,
    ) { (title, facts, badges), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val afterTitle = HlSpace.m.roundToPx()
        val beforeBadges = HlSpace.l.roundToPx()
        // The facts are measured first, so the name takes what they leave, and the formats the rest.
        val factsLine = facts.firstOrNull()?.measure(loose)?.takeIf { it.width > 0 }
        val factsRoom = factsLine?.let { it.width + afterTitle } ?: 0
        val name = title.first().measure(loose.copy(maxWidth = (loose.maxWidth - factsRoom).coerceAtLeast(0)))
        val left = loose.maxWidth - name.width - factsRoom - beforeBadges
        val formats = badges.firstOrNull()?.takeIf { left > 0 }?.measure(loose.copy(maxWidth = left))?.takeIf { it.width > 0 }
        val width = name.width + factsRoom + (formats?.let { it.width + beforeBadges } ?: 0)
        val height = listOfNotNull(name, factsLine, formats).maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            name.placeRelative(0, (height - name.height) / 2)
            factsLine?.placeRelative(name.width + afterTitle, (height - factsLine.height) / 2)
            formats?.placeRelative(name.width + factsRoom + beforeBadges, (height - formats.height) / 2)
        }
    }
}

/** Formats a [TitleLine] shows at most: past four, they say little a title's name has not. */
private const val TITLE_LINE_BADGES = 4

/**
 * Children on one line, [spacing] apart, kept from the first for as long as they fit whole: those
 * past the room are left out, never cut mid-word. A child with the layout id [Continued] carries on
 * the one before it (", Drame"), with no space between. When not even the first fits, it takes
 * the line and ends in an ellipsis ([squeezeFirst]), else the line stays empty.
 */
@Composable
internal fun OneLine(
    modifier: Modifier = Modifier,
    spacing: Dp = HlSpace.fact,
    squeezeFirst: Boolean = true,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val space = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        // The space before each child: none before the first, nor before one that carries on.
        val gaps = measurables.mapIndexed { i, child -> if (i == 0 || child.layoutId == Continued) 0 else space }
        val count = fitCount(measurables.map { it.maxIntrinsicWidth(loose.maxHeight) }, gaps, loose.maxWidth)
        var used = 0
        val placeables = when {
            count > 0 -> measurables.take(count).mapIndexed { i, child ->
                used += gaps[i]
                child.measure(loose.copy(maxWidth = (loose.maxWidth - used).coerceAtLeast(0))).also { used += it.width }
            }
            squeezeFirst && measurables.isNotEmpty() -> listOf(measurables.first().measure(loose).also { used = it.width })
            else -> emptyList()
        }
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(used.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            var x = 0
            placeables.forEachIndexed { i, placeable ->
                x += gaps[i]
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

/** The layout id of a child of a [OneLine] that carries on the one before it. */
internal object Continued

/** How many of [widths], from the first, fit whole in [room], each after its gap in [gaps]. */
internal fun fitCount(widths: List<Int>, gaps: List<Int>, room: Int): Int {
    var used = 0L
    widths.forEachIndexed { i, width ->
        used += width + gaps[i]
        if (used > room) return i
    }
    return widths.size
}

/**
 * The credits at the foot of a poster, read as a sentence: "a film by NAME with NAME, NAME and
 * NAME". Names in tall condensed capitals; the small words and commas recede. Always one line:
 * it keeps as many leading names as fit. The one place where Houselights sets words in capitals.
 */
@Composable
fun BillingBlock(directors: List<String>, cast: List<String>, modifier: Modifier = Modifier) {
    if (directors.isEmpty() && cast.isEmpty()) return
    val c = Hl.colors
    val t = Hl.type
    val locale = Locale.getDefault()
    val by = stringResource(R.string.billing_by)
    val with = stringResource(R.string.billing_with)
    val and = stringResource(R.string.billing_and)
    fun credits(castCount: Int) = buildAnnotatedString {
        // Names never break inside, nor away from the word before them.
        fun join(word: String) = withStyle(t.billingJoin.toSpanStyle().copy(color = c.contentTertiary)) { append(word.unbreakable() + NBSP) }
        fun comma() = withStyle(t.billing.toSpanStyle().copy(color = c.contentTertiary)) { append(", ") }
        fun names(list: List<String>) = list.forEachIndexed { i, name ->
            when {
                i == 0 -> Unit
                i == list.lastIndex -> { append(" "); join(and) }
                else -> comma()
            }
            withStyle(t.billing.toSpanStyle().copy(color = c.contentSecondary)) { append(name.uppercase(locale).unbreakable()) }
        }
        if (directors.isNotEmpty()) {
            join(by)
            names(directors.take(1))
        }
        if (castCount > 0) {
            if (directors.isNotEmpty()) append(" ")
            join(with)
            names(cast.take(castCount))
        }
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val line = remember(directors, cast, width) {
            val most = minOf(cast.size, 3)
            val least = if (directors.isEmpty()) minOf(1, most) else 0
            (most downTo least).map(::credits)
                .firstOrNull { measurer.measure(it, t.billing, softWrap = false, maxLines = 1).size.width <= width }
                ?: credits(least)
        }
        Text(line, style = t.billing, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


private fun String.unbreakable() = replace(' ', NBSP).replace('-', '\u2011')
