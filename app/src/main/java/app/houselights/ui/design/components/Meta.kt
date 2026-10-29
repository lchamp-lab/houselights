package app.houselights.ui.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.FormatFamily
import app.houselights.core.FormatMark
import app.houselights.core.Formats
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.plate
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.languageName
import java.text.NumberFormat
import java.util.Locale

/** The films of a saga held, [owned] of [total] when its length is known: "2 films sur 4", else "2 films". */
@Composable
fun filmCount(owned: Int, total: Int?): String = if (total != null) {
    pluralStringResource(R.plurals.film_count_of, owned, owned, total)
} else {
    pluralStringResource(R.plurals.film_count, owned, owned)
}

/**
 * A title's facts on one line, [spacing] apart: year, length, rating badge, score, the filled heart of a favourite where
 * [favorite] asks for it (a wall's title line), then genres.
 */
@Composable
fun MetaLine(
    item: MediaItem,
    modifier: Modifier = Modifier,
    showGenres: Boolean = true,
    spacing: Dp = HlSpace.fact,
    favorite: Boolean = false,
) {
    val c = Hl.colors
    val seasons = item.childCount?.takeIf { item.kind == MediaKind.SERIES && it > 0 }
        ?.let { pluralStringResource(R.plurals.season_count, it, it) }
    val films = item.childCount?.takeIf { item.kind == MediaKind.COLLECTION }?.let { owned -> filmCount(owned, item.totalCount) }
    val years = item.years()
    val length = when (item.kind) {
        MediaKind.SERIES -> seasons
        MediaKind.COLLECTION -> films
        else -> item.runtimeMs?.let(::formatDuration)
    }

    // In order of importance: when space runs out, genres go first, then the score.
    OneLine(modifier, spacing = spacing) {
        years?.let { Text(it, style = Hl.type.meta, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        length?.let { Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        item.officialRating?.let { Badge(it) }
        item.communityRating?.let { Score(it) }
        // A favourite, its page's heart filled, quiet as the score: what a poster's options made it shows on its wall.
        if (favorite && item.user.favorite) {
            Icon(
                HlIcons.HeartFilled,
                contentDescription = null,
                tint = c.contentSecondary,
                modifier = Modifier.size(HlSize.inlineIcon).testTag(FAVORITE_HEART),
            )
        }
        // Genres drop one by one from the last, as billing block names do.
        if (showGenres) {
            item.genres.take(3).forEachIndexed { i, genre ->
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
}

/** Finds a favourite's heart in tests: it is read by no one, as the score's star. */
internal const val FAVORITE_HEART = "favorite-heart"

/** A title's years: a saga's from first to last film, a show's run (open while it continues), else its year. */
private fun MediaItem.years(): String? = when {
    kind == MediaKind.COLLECTION && year != null && endYear != null -> "$year – $endYear"
    kind != MediaKind.SERIES -> year?.toString()
    year != null && continuing -> "$year –"
    year != null && endYear != null && endYear != year -> "$year – $endYear"
    else -> year?.toString()
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
private fun FormatWord(word: FormatMark, ink: Color?) {
    Text(word.label, style = word.style, color = ink ?: Hl.colors.contentSecondary, maxLines = 1, softWrap = false)
}

/** A format in the technical voice, `jaquette`; a measure of it (a rate, channels) in the text voice, `meta`. */
private val FormatMark.style: TextStyle
    @Composable get() = if (measure) Hl.type.meta else Hl.type.jaquette

/**
 * A title's formats ([Formats]), picture then sound, from the file's media info, else the stored badges. On its
 * page, set as on a case's back ([plate]): a plate of two lines, "Image" and "Son", the words in `jaquette`.
 * In a row ([VersionsSheet]), unframed words, in [ink] when the row has focus. The sound is the audio Lire asks of
 * the player ([plays]), its language after it, else the file's best track.
 */
@Composable
fun FormatLines(item: MediaItem, modifier: Modifier = Modifier, ink: Color? = null, plate: Boolean = true, plays: AudioTrack? = null) {
    val video = item.media?.video
    val best = plays ?: Formats.bestTrack(item.media?.audio.orEmpty())
    val language = plays?.let { languageName(it.language) }
    val marks = remember(item.badges) { Formats.fromBadges(item.badges) }
    val frames = video?.frameRate?.let { frameRateLabel(it) }
    val picture = remember(video, frames) {
        video?.let { v ->
            // The codec, a detail beside what the picture promises.
            marks(FormatFamily.PICTURE, Formats.picture(v), listOfNotNull(frames)).map { it.copy(detail = it.label == v.codec) }
        }
            ?: marks.filter { it.family == FormatFamily.PICTURE }
    }
    val rate = best?.bitrateKbps?.let { audioBitrateLabel(it) }
    val sound = remember(best, rate, language) {
        best?.let { marks(FormatFamily.SOUND, listOf(Formats.soundName(it)), listOfNotNull(it.layout, rate, language)) }
            ?: marks.filter { it.family == FormatFamily.SOUND }
    }
    if (picture.isEmpty() && sound.isEmpty()) return
    if (plate) {
        Jaquette(
            listOf(
                CaseLine(stringResource(R.string.tech_video), HlIcons.Display, picture),
                CaseLine(stringResource(R.string.tech_audio), HlIcons.Sound, sound),
            ),
            modifier,
        )
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.xxs)) {
        listOf(picture, sound).filter { it.isNotEmpty() }.forEach { words ->
            OneLine(spacing = HlSpace.fact, squeezeFirst = false) { words.forEach { FormatWord(it, ink) } }
        }
    }
}

/** A line of a case's back: its family's [name] (read aloud) and [glyph], and its [words]. */
private class CaseLine(val name: String, val glyph: ImageVector, val words: List<FormatMark>)

/**
 * The back of a case, open on the picture like the facts above it: a line per family of [lines], its glyph in grey,
 * then its formats in `jaquette`, a detail (the codec) in grey, and their measures in a secondary `meta`.
 */
@Composable
private fun Jaquette(lines: List<CaseLine>, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
        lines.filter { it.words.isNotEmpty() }.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(line.glyph, contentDescription = line.name, tint = c.contentSecondary, modifier = Modifier.size(HlSize.inlineIcon))
                Spacer(Modifier.width(HlSpace.l))
                OneLine(spacing = HlSpace.m, squeezeFirst = false) {
                    line.words.forEach {
                        val ink = if (it.measure || it.detail) c.contentSecondary else c.content
                        Text(it.label, style = it.style, color = ink, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** [formats] of a [family], then their [measures]. */
private fun marks(family: FormatFamily, formats: List<String>, measures: List<String>): List<FormatMark> =
    formats.map { FormatMark(it, family) } + measures.map { FormatMark(it, family, measure = true) }

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

/**
 * A title's name in `rowTitle` and its facts over a wall, on one baseline; the name is ellipsised before facts drop. A
 * favourite ends its facts with the filled heart; a change its poster's options asked that was not saved ([notSaved],
 * « Non enregistré · Serveur injoignable ») is said in their place, by the attention mark.
 */
@Composable
fun TitleLine(item: MediaItem, modifier: Modifier = Modifier, notSaved: String? = null) {
    Layout(
        contents = listOf(
            { Text(item.title, style = Hl.type.rowTitle, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            {
                if (notSaved != null) {
                    HlStatus(StatusState.Attention, notSaved, maxLines = 1)
                } else {
                    MetaLine(item, showGenres = false, favorite = true)
                }
            },
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
        fun join(word: String) =
            withStyle(t.billingJoin.toSpanStyle().copy(color = c.contentTertiary)) { append(word.unbreakable() + NBSP) }
        fun names(list: List<String>) = list.forEachIndexed { i, name ->
            when {
                i == 0 -> Unit
                i == list.lastIndex -> {
                    append(" ")
                    join(and)
                }
                else -> withStyle(t.billing.toSpanStyle().copy(color = c.contentTertiary)) { append(", ") }
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
