package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.FormatFamily
import app.houselights.core.Formats
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.plate
import app.houselights.ui.design.plateEdge
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatDuration
import java.text.NumberFormat
import java.util.Locale
import app.houselights.ui.util.NBSP
import androidx.compose.ui.graphics.Color

/** The films of a saga held, [owned] of [total] when its length is known: "2 films sur 4", else "2 films". */
@Composable
fun filmCount(owned: Int, total: Int?): String = if (total != null) {
    pluralStringResource(R.plurals.film_count_of, owned, owned, total)
} else {
    pluralStringResource(R.plurals.film_count, owned, owned)
}

/** A title's facts on one line, space-separated: year, length, rating badge, score, then genres. */
@Composable
fun MetaLine(item: MediaItem, modifier: Modifier = Modifier, showGenres: Boolean = true) {
    val c = Hl.colors
    val seasons = item.childCount?.takeIf { item.kind == MediaKind.SERIES && it > 0 }
        ?.let { pluralStringResource(R.plurals.season_count, it, it) }
    val films = item.childCount?.takeIf { item.kind == MediaKind.COLLECTION }?.let { owned -> filmCount(owned, item.totalCount) }
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

    // In order of importance: when space runs out, genres go first, then the score.
    OneLine(modifier) {
        years?.let { Text(it, style = Hl.type.meta, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        length?.let { Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item.officialRating?.let { Badge(it) }
        item.communityRating?.let { Score(it) }
        // Genres drop one by one from the last, as billing block names do.
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

/** A score out of ten, star then figure, quiet: a score is not a state, so it never lights. */
@Composable
fun Score(score: Double, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(HlIcons.Star, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.inlineIcon))
        Spacer(Modifier.width(HlSpace.xxs))
        Text(String.format(Locale.getDefault(), "%.1f", score), style = Hl.type.meta, color = c.contentSecondary)
    }
}

/** Text in a hairline frame (age rating, remote key, protocol); never a state or a format. [ink] colours both on a focused plate. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, ink: Color? = null) {
    val c = Hl.colors
    Text(
        text,
        style = Hl.type.badge,
        color = ink ?: c.contentSecondary,
        modifier = modifier
            .border(HlSize.hairline, ink ?: c.outline, HlShapes.badge)
            .padding(horizontal = HlControl.badgePaddingH, vertical = HlControl.badgePaddingV),
    )
}

@Composable
private fun FormatWord(text: String, ink: Color?) {
    Text(text, style = Hl.type.jaquette, color = ink ?: Hl.colors.contentSecondary, maxLines = 1, softWrap = false)
}

/**
 * A title's formats ([Formats]), picture then sound, from the file's media info, else the stored badges. On its
 * page, set as on a case's back ([plate]): a plate of two lines, "Image" and "Son", the words in `jaquette`.
 * In a row ([VersionsSheet]), unframed words, in [ink] when the row has focus.
 */
@Composable
fun FormatLines(item: MediaItem, modifier: Modifier = Modifier, ink: Color? = null, plate: Boolean = true) {
    val video = item.media?.video
    val audio = item.media?.audio.orEmpty()
    val marks = remember(item.badges) { Formats.fromBadges(item.badges) }
    val frames = video?.frameRate?.let { frameRateLabel(it) }
    val picture = remember(video, frames) {
        video?.let { Formats.picture(it) + listOfNotNull(frames) } ?: marks.filter { it.family == FormatFamily.PICTURE }.map { it.label }
    }
    val rate = Formats.bestTrack(audio)?.bitrateKbps?.let { audioBitrateLabel(it) }
    val sound = remember(audio, rate) {
        (Formats.sound(audio) + listOfNotNull(rate)).ifEmpty { marks.filter { it.family == FormatFamily.SOUND }.map { it.label } }
    }
    if (picture.isEmpty() && sound.isEmpty()) return
    if (plate) {
        Jaquette(listOf(stringResource(R.string.tech_video) to picture, stringResource(R.string.tech_audio) to sound), modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
        listOf(picture, sound).filter { it.isNotEmpty() }.forEach { words ->
            OneLine(spacing = HlSpace.fact, squeezeFirst = false) { words.forEach { FormatWord(it, ink) } }
        }
    }
}

/**
 * The back of a case: a plate in the picture's own dark, one line per family of [lines] with words, its name in
 * a caption, the words after it in `jaquette`, a hairline between lines. As wide as its longest line.
 */
@Composable
private fun Jaquette(lines: List<Pair<String, List<String>>>, modifier: Modifier) {
    val c = Hl.colors
    val shown = lines.filter { it.second.isNotEmpty() }
    // The names share a column, as wide as the widest, so the words start together.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val caption = Hl.type.caption
    val names = shown.map { it.first }
    val column = remember(names, caption, density) { with(density) { names.maxOf { measurer.measure(it, caption).size.width }.toDp() } }
    val light = LocalAmbient.current
    val edge = c.plateEdge
    Column(
        modifier
            .width(IntrinsicSize.Max)
            .drawBehind { drawRoundRect(c.plate(light), cornerRadius = CornerRadius(HlShapes.control.topStart.toPx(size, this))) }
            .border(HlSize.hairline, edge, HlShapes.control),
    ) {
        shown.forEachIndexed { i, (name, words) ->
            if (i > 0) Spacer(Modifier.fillMaxWidth().height(HlSize.hairline).background(edge))
            Row(Modifier.padding(horizontal = HlSpace.s, vertical = HlSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Hl.type.caption, color = c.contentSecondary, maxLines = 1, modifier = Modifier.width(column))
                Spacer(Modifier.width(HlSpace.m))
                OneLine(spacing = HlSpace.m, squeezeFirst = false) {
                    words.forEach { Text(it, style = Hl.type.jaquette, color = c.content, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** A frame rate as displayed: "23,976 i/s", "25 i/s". */
@Composable
fun frameRateLabel(fps: Float): String {
    val number = NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = FRAME_RATE_DIGITS }
    return stringResource(R.string.frame_rate, number.format(Formats.frameRate(fps)))
}

private const val FRAME_RATE_DIGITS = 3

/** An audio track's bitrate as displayed: "768 kb/s", "4 054 kb/s". */
@Composable
fun audioBitrateLabel(kbps: Int): String =
    stringResource(R.string.audio_bitrate, NumberFormat.getIntegerInstance(Locale.getDefault()).format(kbps))

/** A title's name in `rowTitle` and its facts over a wall, on one baseline; the name is ellipsised before facts drop. */
@Composable
fun TitleLine(item: MediaItem, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(
            { Text(item.title, style = Hl.type.rowTitle, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            { MetaLine(item, showGenres = false) },
        ),
        modifier = modifier,
    ) { (title, facts), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val afterTitle = HlSpace.m.roundToPx()
        // The facts are measured first, so the name takes what they leave.
        val factsLine = facts.firstOrNull()?.measure(loose)?.takeIf { it.width > 0 }
        val factsRoom = factsLine?.let { it.width + afterTitle } ?: 0
        val name = title.first().measure(loose.copy(maxWidth = (loose.maxWidth - factsRoom).coerceAtLeast(0)))
        val width = name.width + factsRoom
        val height = listOfNotNull(name, factsLine).maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        // The facts sit on the name's baseline, the name centred in the line.
        val nameY = (height - name.height) / 2
        val factsY = factsLine?.let { line ->
            val base = line[FirstBaseline]
            if (base == AlignmentLine.Unspecified) (height - line.height) / 2 else nameY + name[FirstBaseline] - base
        }
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            name.placeRelative(0, nameY)
            factsLine?.placeRelative(name.width + afterTitle, factsY ?: 0)
        }
    }
}

/**
 * Children on one line, [spacing] apart, kept while they fit whole; a [Continued] child follows the previous
 * with no gap. If even the first does not fit, it is ellipsised when [squeezeFirst], else nothing shows.
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
        // The gap before each child: none before the first or a continuation.
        val gaps = measurables.mapIndexed { i, child -> if (i == 0 || child.layoutId == Continued) 0 else space }
        val count = fitCount(measurables.map { it.maxIntrinsicWidth(loose.maxHeight) }, gaps, loose.maxWidth)
        var used = 0
        val placeables = when {
            count > 0 -> measurables.take(count).mapIndexed { i, child ->
                used += gaps[i]
                // Unbounded when measured for its intrinsic width.
                val room = if (loose.hasBoundedWidth) (loose.maxWidth - used).coerceAtLeast(0) else Constraints.Infinity
                child.measure(loose.copy(maxWidth = room)).also { used += it.width }
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

/** Layout id of a [OneLine] child that continues the previous one. */
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

/** A row heading billed as on a poster ("les films de DENIS VILLENEUVE"), read as one line. */
@Composable
fun FilmsOfHeading(name: String, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val t = Hl.type
    val of = stringResource(R.string.row_films_of)
    val text = buildAnnotatedString {
        withStyle(t.billingJoin.toSpanStyle().copy(color = c.contentTertiary)) { append(of + NBSP) }
        withStyle(t.billing.toSpanStyle().copy(color = c.content)) { append(name.uppercase(Locale.getDefault())) }
    }
    Text(text, style = t.billing, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The billing block ("a film by NAME with NAME and NAME") on one line, keeping as many names as fit. */
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
