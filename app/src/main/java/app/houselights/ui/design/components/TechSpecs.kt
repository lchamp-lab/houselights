package app.houselights.ui.design.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.BaseRange
import app.houselights.core.HdrFormat
import app.houselights.core.MediaItem
import app.houselights.core.TechLabel
import app.houselights.core.TechRow
import app.houselights.core.TechSection
import app.houselights.core.TechSheet
import app.houselights.core.TechTopic
import app.houselights.core.TechValue
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TechSpecsDefaults
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.formatRatio
import app.houselights.ui.util.languageName
import java.util.Locale
import kotlin.math.roundToInt
import app.houselights.ui.design.CAP_HEIGHT

/** One craft of a title's credits: its [job] as credited ("Musique") and its [names] in billing order. */
data class Craft(val job: String, val names: List<String>)

/** At most this many names per craft, the first in billing order, as on a poster. */
const val CRAFT_NAMES = 3

/**
 * A title's technical sheet ([TechSheet]) in a side sheet: picture, sound, then the [crafts] its head does not
 * name, subtitles and file; one focusable row per fact, track or craft, the default track dotted. OK does
 * nothing, Back closes.
 */
@Composable
fun TechSpecs(item: MediaItem, onDismiss: () -> Unit, crafts: List<Craft> = emptyList()) {
    val media = item.media
    val sections = remember(media, item.fileName) { media?.let { TechSheet.of(it, item.fileName).sections }.orEmpty() }
    val blocks = remember(sections, crafts) {
        val credited = crafts.filter { it.names.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let(Block::Credits)
        // The credits follow the picture and the sound.
        val (head, tail) = sections.partition { it.topic == TechTopic.PICTURE || it.topic == TechTopic.SOUND }
        head.map(Block::Media) + listOfNotNull(credited) + tail.map(Block::Media)
    }
    val first = remember { FocusRequester() }
    HlSideSheet(
        stringResource(R.string.tech_details),
        onDismiss = onDismiss,
        facts = listOf(item.title, item.year?.toString()),
        artwork = item.poster?.let { poster -> { PosterThumb(poster, width = null) } },
        initialFocus = first.takeIf { blocks.isNotEmpty() },
    ) {
        blocks.forEachIndexed { i, block ->
            if (i > 0) Spacer(Modifier.height(HlSpace.l))
            when (block) {
                is Block.Media -> MediaSection(block.section, first.takeIf { i == 0 })
                is Block.Credits -> Credits(block.crafts, first.takeIf { i == 0 })
            }
        }
    }
}

/** A section of the sheet: the file's, or the credits. */
private sealed interface Block {
    data class Media(val section: TechSection) : Block
    data class Credits(val crafts: List<Craft>) : Block
}

/** A section of the file's sheet. */
@Composable
private fun ColumnScope.MediaSection(section: TechSection, first: FocusRequester?) {
    val count = section.tracks?.let { pluralStringResource(R.plurals.tech_track_count, it, it) }
    Section(topicName(section.topic), count, section.rows, first) { row, modifier -> SheetRow(row, modifier) }
}

/** The crafts the head does not name, under the end credits' name, up to [CRAFT_NAMES] names each. */
@Composable
private fun ColumnScope.Credits(crafts: List<Craft>, first: FocusRequester?) {
    Section(stringResource(R.string.end_credits), null, crafts, first) { craft, modifier ->
        val names = craft.names.take(CRAFT_NAMES)
        SpecRow(
            label = craft.job,
            words = names.map { Word(it, Voice.Name) },
            readAs = (listOf(craft.job) + names).joinToString(", "),
            modifier = modifier,
        )
    }
}

/** A section, its name revealed with its first row; later rows scroll on their own (long subtitle lists). */
@Composable
private fun <T> ColumnScope.Section(
    name: String,
    count: String?,
    rows: List<T>,
    first: FocusRequester?,
    row: @Composable (T, Modifier) -> Unit,
) {
    Column(Modifier.revealOnFocus()) {
        SectionName(name, count)
        row(rows.first(), first?.let { Modifier.focusRequester(it) } ?: Modifier)
    }
    rows.drop(1).forEach { row(it, Modifier) }
}

/** A section's name and its track count ("Son   2 pistes"), in the voice of a case back's line names. */
@Composable
private fun SectionName(name: String, count: String?) {
    val c = Hl.colors
    Row(Modifier.padding(start = HlInset.card, bottom = HlSpace.xs)) {
        Text(name, style = Hl.type.caption, color = c.contentSecondary)
        count?.let {
            Spacer(Modifier.width(HlSpace.fact))
            Text(it, style = Hl.type.caption, color = c.contentTertiary)
        }
    }
}

@Composable
private fun SheetRow(row: TechRow, modifier: Modifier = Modifier) {
    when (row) {
        is TechRow.Fact -> {
            val label = labelName(row.label)
            val words = words(row.value, row.detail)
            SpecRow(
                label = label,
                words = words,
                stacked = row.label == TechLabel.NAME,
                readAs = (listOf(label) + words.map { it.text }).joinToString(", "),
                modifier = modifier,
            )
        }
        is TechRow.Track -> {
            val language = trackLanguage(row.language, row.region)
            val words = words(row.value, row.detail)
            val inUse = stringResource(R.string.tech_in_use).takeIf { row.inUse }
            SpecRow(
                label = language,
                words = words,
                note = row.note,
                inUse = row.inUse,
                readAs = (listOf(language) + words.map { it.text } + listOfNotNull(row.note, inUse)).joinToString(", "),
                modifier = modifier,
            )
        }
    }
}

/** How a word on a row's right reads: a format as on a case's back, a name, or what qualifies them. */
private enum class Voice { Format, Name, Detail }

private data class Word(val text: String, val voice: Voice)

/** A fact's words: its first value as on a case's back, the rest of it and its [detail] qualifying it. */
@Composable
private fun words(value: List<TechValue>, detail: List<TechValue>): List<Word> =
    value.mapIndexed { i, v -> Word(v.text(), if (i == 0) Voice.Format else Voice.Detail) } + detail.map { Word(it.text(), Voice.Detail) }

/**
 * A read-only row: [label] in `body` and [note] left, its [words] right, capped at the value width; [stacked]
 * puts them under the label (a file name). The margin holds the in-use dot; read as [readAs].
 */
@Composable
private fun SpecRow(
    label: String,
    words: List<Word>,
    readAs: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    inUse: Boolean = false,
    stacked: Boolean = false,
) {
    val c = Hl.colors
    // Focus feeds the semantics below.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The in-use dot centres on the label's cap height.
    val capitals = with(LocalDensity.current) { (Hl.type.body.fontSize.toPx() * CAP_HEIGHT / 2).roundToInt() }
    HlRowSurface(
        onClick = {},
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = readAs
                this.focused = focused
            },
        interactionSource = interaction,
    ) { lit ->
        val ink = LocalContentColor.current
        val secondary = FocusStyle.quietInk(lit, enabled = true, lit = false)
        // Text centred vertically; label and words share a baseline.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TechSpecsDefaults.rowHeight)
                .padding(end = HlInset.card, top = HlSpace.xs, bottom = HlSpace.xs),
            contentAlignment = Alignment.CenterStart,
        ) { Row(Modifier.fillMaxWidth()) {
            // Only as tall as the dot: a line-tall margin would add space under the text.
            Box(
                Modifier.width(HlInset.card).alignBy { it.measuredHeight / 2 + capitals },
                contentAlignment = Alignment.Center,
            ) {
                if (inUse) StatusLight(StatusState.On, ink = if (lit) ink else null)
            }
            if (stacked) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = Hl.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    words.forEach {
                        Text(it.text, style = Hl.type.meta, color = secondary, maxLines = STACKED_LINES, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Column(Modifier.weight(1f).alignByBaseline()) {
                    Text(label, style = Hl.type.body, maxLines = LINES, overflow = TextOverflow.Ellipsis)
                    note?.let { Text(it, style = Hl.type.caption, color = secondary, maxLines = LINES, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(HlSpace.m))
                EndWords(Modifier.widthIn(max = TechSpecsDefaults.valueWidth).alignByBaseline()) {
                    words.forEach { word ->
                        val (style, color) = when (word.voice) {
                            Voice.Format -> Hl.type.jaquette to ink
                            Voice.Name -> Hl.type.meta to ink
                            Voice.Detail -> Hl.type.meta to secondary
                        }
                        Text(
                            word.text,
                            style = style,
                            color = color,
                            textAlign = TextAlign.End,
                            maxLines = LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } }
    }
}

/**
 * Words set from the end of their line, a fact's space apart and on one baseline, each whole: one that does not
 * fit starts the next line. Its first baseline is its first line's.
 */
@Composable
private fun EndWords(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = HlSpace.fact.roundToPx()
        val between = HlSpace.xxs.roundToPx()
        val words = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val lines = mutableListOf<MutableList<Placeable>>()
        var used = 0
        words.forEach { word ->
            val line = lines.lastOrNull()
            if (line == null || used + gap + word.width > constraints.maxWidth) {
                lines += mutableListOf(word)
                used = word.width
            } else {
                line += word
                used += gap + word.width
            }
        }
        fun Placeable.base() = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height
        val widths = lines.map { line -> line.sumOf { it.width } + gap * (line.size - 1) }
        val ascents = lines.map { line -> line.maxOf { it.base() } }
        val heights = lines.mapIndexed { i, line -> line.maxOf { ascents[i] - it.base() + it.height } }
        val width = (widths.maxOrNull() ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = heights.sum() + between * (lines.size - 1).coerceAtLeast(0)
        layout(width, height, mapOf(FirstBaseline to (ascents.firstOrNull() ?: 0))) {
            var y = 0
            lines.forEachIndexed { i, line ->
                var x = width - widths[i]
                line.forEach { word ->
                    word.placeRelative(x, y + ascents[i] - word.base())
                    x += word.width + gap
                }
                y += heights[i] + between
            }
        }
    }
}

/** Maximum lines of a label, a word or a note; a stacked file name gets [STACKED_LINES]. */
private const val LINES = 2
private const val STACKED_LINES = 3

@Composable
private fun topicName(topic: TechTopic): String = stringResource(
    when (topic) {
        TechTopic.PICTURE -> R.string.tech_video
        TechTopic.SOUND -> R.string.tech_audio
        TechTopic.SUBTITLES -> R.string.tech_subtitles
        TechTopic.FILE -> R.string.tech_file
    },
)

@Composable
private fun labelName(label: TechLabel): String = stringResource(
    when (label) {
        TechLabel.DEFINITION -> R.string.tech_definition
        TechLabel.ASPECT_RATIO -> R.string.tech_aspect_ratio
        TechLabel.DYNAMIC_RANGE -> R.string.tech_dynamic_range
        TechLabel.CODEC -> R.string.tech_codec
        TechLabel.FRAME_RATE -> R.string.tech_frame_rate
        TechLabel.BITRATE -> R.string.tech_video_bitrate
        TechLabel.CONTAINER -> R.string.tech_container
        TechLabel.SIZE -> R.string.tech_size
        TechLabel.AVERAGE_BITRATE -> R.string.tech_bitrate
        TechLabel.DURATION -> R.string.tech_duration
        TechLabel.NAME -> R.string.tech_file_name
    },
)

/** A track's language, with its region when known: "Français (Canada)". */
@Composable
private fun trackLanguage(code: String?, region: String?): String {
    val name = languageName(code) ?: stringResource(R.string.unknown_language)
    return region?.let { "$name (${Locale("", it).getDisplayCountry(Locale.getDefault())})" } ?: name
}

@Composable
@Suppress("CyclomaticComplexMethod") // One branch per kind of value: read down.
private fun TechValue.text(): String = when (this) {
    is TechValue.Word -> text
    is TechValue.Dimensions -> formatDimensions(width, height)
    is TechValue.Ratio -> formatRatio(value)
    is TechValue.BitDepth -> stringResource(R.string.bit_depth, bits)
    is TechValue.FrameRate -> frameRateLabel(fps)
    is TechValue.Megabits -> megabits(kbps)
    is TechValue.Kilobits -> audioBitrateLabel(kbps)
    is TechValue.Bytes -> formatBytes(bytes)
    is TechValue.Duration -> formatDuration(ms)
    is TechValue.DolbyVisionProfile -> stringResource(R.string.tech_dv_profile, name)
    is TechValue.BaseLayer -> stringResource(R.string.tech_base_layer, rangeName(range))
    TechValue.StandardRange -> stringResource(R.string.sdr)
    TechValue.Forced -> stringResource(R.string.forced_subs)
    TechValue.HearingImpaired -> stringResource(R.string.sdh_subs)
}

@Composable
private fun rangeName(range: BaseRange): String = when (range) {
    BaseRange.HDR10 -> HdrFormat.HDR10.token
    BaseRange.HLG -> HdrFormat.HLG.token
    BaseRange.SDR -> stringResource(R.string.sdr)
}

/** A bitrate in megabits a second, to the tenth: "15,3 Mb/s". */
@Composable
private fun megabits(kbps: Long): String =
    stringResource(R.string.mbps, String.format(Locale.getDefault(), "%.1f", kbps / KBPS_PER_MBPS))

private const val KBPS_PER_MBPS = 1000.0
