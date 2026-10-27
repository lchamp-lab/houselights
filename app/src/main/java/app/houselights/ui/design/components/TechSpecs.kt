package app.houselights.ui.design.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.BaseRange
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.TechLabel
import app.houselights.core.TechRow
import app.houselights.core.TechSection
import app.houselights.core.TechSheet
import app.houselights.core.TechTopic
import app.houselights.core.TechValue
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TechSpecsDefaults
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.formatRatio
import app.houselights.ui.util.languageName
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * A title's technical sheet ([TechSheet]) in a side sheet, its file's container and size among its facts, read as
 * a case's back, block by block: picture, sound, subtitles and file. A block is a plate the remote rings
 * ([ReadingPlate]), scrolled through before focus leaves it; OK does nothing.
 */
@Composable
fun TechSpecs(item: MediaItem, onDismiss: () -> Unit) {
    val media = item.media
    val sections = remember(media, item.fileName) { media?.let { TechSheet.of(it, item.fileName).sections }.orEmpty() }
    val first = remember { FocusRequester() }
    val scroll = rememberScrollState()
    HlSideSheet(
        stringResource(R.string.tech_details),
        onDismiss = onDismiss,
        facts = listOf(item.title, item.year?.toString(), media?.let { fileFact(it) }),
        initialFocus = first.takeIf { sections.isNotEmpty() },
        scroll = scroll,
    ) {
        sections.forEachIndexed { i, section ->
            val modifier = Modifier.readThrough(scroll).then(if (i == 0) Modifier.focusRequester(first) else Modifier)
            when (section.topic) {
                TechTopic.PICTURE -> PictureBlock(section, modifier)
                TechTopic.SOUND, TechTopic.SUBTITLES -> TracksBlock(section, modifier)
                TechTopic.FILE -> FileBlock(section, modifier)
            }
        }
    }
}

/** The file as a case's spine prints it: its container and its size ("MKV, 23,4 Go"). */
@Composable
private fun fileFact(media: MediaInfo): String? {
    val size = media.sizeBytes?.let { formatBytes(it) }
    return when {
        media.container != null && size != null -> stringResource(R.string.tech_file_fact, media.container, size)
        else -> media.container ?: size
    }
}

/**
 * Down and up scroll the sheet through a block taller than its room, [HlSize.scrollStep] a press, before focus
 * moves on: what does not fit is read, never skipped.
 */
@Composable
private fun Modifier.readThrough(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { HlSize.scrollStep.toPx() }
    val placed = remember { Placed() }
    return onGloballyPositioned { placed.at = it }.onPreviewKeyEvent { event ->
        val down = event.key == Key.DirectionDown
        val moves = event.type == KeyEventType.KeyDown && (down || event.key == Key.DirectionUp)
        val at = placed.at?.takeIf { moves && it.isAttached } ?: return@onPreviewKeyEvent false
        // Its whole span against the part its sheet shows.
        val shown = at.boundsInRoot()
        val top = at.positionInRoot().y
        val hidden = if (down) top + at.size.height - shown.bottom else shown.top - top
        if (hidden < 1f) return@onPreviewKeyEvent false
        scope.launch { scroll.animateScrollBy(if (down) min(step, hidden) else -min(step, hidden)) }
        true
    }
}

/** Where a block was last placed, kept without recomposing. */
private class Placed {
    var at: LayoutCoordinates? = null
}

/** A block's name in `group`, and how many tracks it holds ("Son   2 pistes"). */
@Composable
private fun BlockName(name: String, count: String? = null) {
    val c = Hl.colors
    Row {
        Text(name, style = Hl.type.group, color = c.contentSecondary, modifier = Modifier.alignByBaseline())
        count?.let {
            Spacer(Modifier.width(HlSpace.s))
            Text(it, style = Hl.type.meta, color = c.contentTertiary, modifier = Modifier.alignByBaseline())
        }
    }
    Spacer(Modifier.height(HlSpace.m))
}

/** A format as a case's back prints it, and the measures that qualify it, one a line under it. */
private data class Mark(val format: String, val measures: List<String>)

/**
 * The picture as marks across its plate ("Ultra HD" over "3832 × 1592" and "2,39:1"): each fact led by a format
 * starts a mark, the others measure the mark before them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PictureBlock(section: TechSection, modifier: Modifier) {
    val c = Hl.colors
    val name = topicName(section.topic)
    val marks = mutableListOf<Mark>()
    section.rows.filterIsInstance<TechRow.Fact>().forEach { fact ->
        val words = fact.value.map { it.text() }
        val qualified = fact.detail.map { it.text() }
        if (fact.value.firstOrNull()?.voice == Voice.Format || marks.isEmpty()) {
            marks += Mark(words.firstOrNull() ?: return@forEach, qualified + words.drop(1))
        } else {
            marks[marks.lastIndex] = marks.last().let { it.copy(measures = it.measures + words + qualified) }
        }
    }
    val readAs = (listOf(name) + marks.flatMap { listOf(it.format) + it.measures }).joinToString(", ")
    ReadingPlate(modifier.fillMaxWidth(), readAs = readAs) {
        BlockName(name)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            marks.forEach { mark ->
                Column {
                    Text(mark.format, style = Hl.type.spec, color = c.content, maxLines = 1)
                    Spacer(Modifier.height(HlSpace.xs))
                    // One text, its lines at their own height, a measure a line.
                    if (mark.measures.isNotEmpty()) {
                        Text(mark.measures.joinToString("\n"), style = Hl.type.meta, color = c.contentSecondary, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Sound or subtitle tracks, one a line: the format as a case prints it, then its language in white, the file's
 * default after its white dot, then what measures and qualifies it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TracksBlock(section: TechSection, modifier: Modifier) {
    val c = Hl.colors
    val name = topicName(section.topic)
    val count = section.tracks?.let { pluralStringResource(R.plurals.tech_track_count, it, it) }
    val inUse = stringResource(R.string.tech_in_use)
    val tracks = section.rows.filterIsInstance<TechRow.Track>().map { track ->
        val format = track.value.firstOrNull()?.takeIf { it.voice == Voice.Format }
        Triple(track, format?.text(), (track.value - listOfNotNull(format) + track.detail).map { it.text() } + listOfNotNull(track.note))
    }
    val languages = tracks.map { (track) -> trackLanguage(track.language, track.region) }
    val readAs = (listOfNotNull(name, count) + tracks.mapIndexed { i, (track, format, words) ->
        (listOfNotNull(format, languages[i]) + words + listOfNotNull(inUse.takeIf { track.inUse })).joinToString(", ")
    }).joinToString(", ")
    val formatColumn = formatColumn(tracks.mapNotNull { (_, format) -> format })
    ReadingPlate(modifier.fillMaxWidth(), readAs = readAs) {
        BlockName(name, count)
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            tracks.forEachIndexed { i, (track, format, words) ->
                Row {
                    Text(
                        format.orEmpty(),
                        style = Hl.type.spec,
                        color = c.content,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(formatColumn).alignByBaseline(),
                    )
                    FlowRow(
                        Modifier.weight(1f).alignByBaseline(),
                        horizontalArrangement = Arrangement.spacedBy(HlSpace.fact),
                        verticalArrangement = Arrangement.spacedBy(HlSpace.xxs),
                    ) {
                        Row(Modifier.alignByBaseline(), verticalAlignment = Alignment.CenterVertically) {
                            // The file's default, a state.
                            if (track.inUse) {
                                StatusLight(StatusState.Done)
                                Spacer(Modifier.width(HlSpace.xxs))
                            }
                            Text(languages[i], style = Hl.type.meta, color = c.content, maxLines = 1)
                        }
                        words.forEach {
                            Text(it, Modifier.alignByBaseline(), style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The formats' column: the widest of [formats] on one line in `spec` and [HlSpace.m] before the language, held
 * between the column's least and most.
 */
@Composable
private fun formatColumn(formats: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val spec = Hl.type.spec
    val density = LocalDensity.current
    return remember(formats, spec, density) {
        with(density) {
            formats.maxOfOrNull { measurer.measure(it, spec, softWrap = false, maxLines = 1).size.width.toDp() + HlSpace.m }
                ?.coerceIn(TechSpecsDefaults.formatColumn, TechSpecsDefaults.formatColumnWide)
                ?: TechSpecsDefaults.formatColumn
        }
    }
}

/** The file as a case's back states it: each fact's label over its value, then its name, whole. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileBlock(section: TechSection, modifier: Modifier) {
    val c = Hl.colors
    val name = topicName(section.topic)
    val nameLabel = labelName(TechLabel.NAME)
    val facts = section.rows.filterIsInstance<TechRow.Fact>().map { fact ->
        labelName(fact.label) to (fact.value + fact.detail).map { it.text() }.joinToString(" ")
    }
    val (named, stated) = facts.partition { (label) -> label == nameLabel }
    val readAs = (listOf(name) + facts.map { (label, value) -> "$label, $value" }).joinToString(", ")
    ReadingPlate(modifier.fillMaxWidth(), readAs = readAs) {
        BlockName(name)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl), verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
            stated.forEach { (label, value) -> PlateFact(label, value, technical = true) }
        }
        named.forEach { (label, value) ->
            Spacer(Modifier.height(HlSpace.m))
            Text(label, style = Hl.type.meta, color = c.contentSecondary)
            Spacer(Modifier.height(HlSpace.xs))
            Text(value, style = Hl.type.meta, color = c.content, maxLines = NAME_LINES, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Lines of a file name, as long as names get. */
private const val NAME_LINES = 3

/** How a value reads: a format as on a case's back, or a measure or what qualifies it. */
internal enum class Voice { Format, Measure }

/** Formats (Ultra HD, Dolby Vision, profil 8.1, PGS) as on a case's back; sizes, rates, durations, 7.1 and qualifiers measured. */
internal val TechValue.voice: Voice
    get() = when (this) {
        is TechValue.Word, is TechValue.DolbyVisionProfile, is TechValue.BaseLayer, TechValue.StandardRange -> Voice.Format
        is TechValue.Dimensions, is TechValue.Ratio, is TechValue.FrameRate, is TechValue.BitDepth, is TechValue.Channels,
        is TechValue.Megabits, is TechValue.Kilobits, is TechValue.Bytes, is TechValue.Duration,
        TechValue.Forced, TechValue.HearingImpaired,
        -> Voice.Measure
    }

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
internal fun trackLanguage(code: String?, region: String?): String {
    val name = languageName(code) ?: stringResource(R.string.unknown_language)
    return region?.let { "$name (${Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.getDefault())})" } ?: name
}

@Composable
@Suppress("CyclomaticComplexMethod") // One branch per kind of value: read down.
private fun TechValue.text(): String = when (this) {
    is TechValue.Word -> text
    is TechValue.Channels -> layout
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
