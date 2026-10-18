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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.text.TextStyle
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
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TechSpecsDefaults
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.formatRatio
import app.houselights.ui.util.languageName
import java.util.Locale
import kotlin.math.roundToInt
import app.houselights.ui.design.CAP_HEIGHT

/**
 * A title's technical sheet ([TechSheet]) in a side sheet: picture, sound, subtitles and file, one
 * focusable row per fact or track, the default track dotted; OK does nothing, Back closes.
 */
@Composable
fun TechSpecs(item: MediaItem, onDismiss: () -> Unit) {
    val media = item.media ?: return
    val sheet = remember(media, item.fileName) { TechSheet.of(media, item.fileName) }
    val first = remember { FocusRequester() }
    HlSideSheet(
        stringResource(R.string.tech_details),
        onDismiss = onDismiss,
        facts = listOf(item.title, item.year?.toString()),
        artwork = item.poster?.let { poster -> { PosterThumb(poster, width = HlSize.posterThumbLarge) } },
        initialFocus = first.takeIf { sheet.sections.isNotEmpty() },
    ) {
        sheet.sections.forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(HlSpace.l))
            Section(section, first.takeIf { i == 0 })
        }
    }
}

/** A section, its title revealed with its first row; later rows scroll on their own (long subtitle lists). */
@Composable
private fun ColumnScope.Section(section: TechSection, first: FocusRequester?) {
    Column(Modifier.revealOnFocus()) {
        SectionTitle(section)
        SheetRow(section.rows.first(), first?.let { Modifier.focusRequester(it) } ?: Modifier)
    }
    section.rows.drop(1).forEach { SheetRow(it) }
}

/** A section's name and track count: "Son   3 pistes". */
@Composable
private fun SectionTitle(section: TechSection) {
    val c = Hl.colors
    Row(Modifier.padding(start = HlInset.card, bottom = HlSpace.xs)) {
        Text(topicName(section.topic), style = Hl.type.group, color = c.contentSecondary)
        section.tracks?.let { count ->
            Spacer(Modifier.width(HlSpace.fact))
            Text(pluralStringResource(R.plurals.tech_track_count, count, count), style = Hl.type.group, color = c.contentTertiary)
        }
    }
}

@Composable
private fun SheetRow(row: TechRow, modifier: Modifier = Modifier) {
    when (row) {
        is TechRow.Fact -> {
            val label = labelName(row.label)
            val value = row.value.text()
            val detail = row.detail.text()
            SpecRow(
                label = label,
                value = value,
                detail = detail,
                stacked = row.label == TechLabel.NAME,
                readAs = listOfNotNull(label, value, detail).joinToString(", "),
                modifier = modifier,
            )
        }
        is TechRow.Track -> {
            val language = trackLanguage(row.language, row.region)
            val value = row.value.text()
            val detail = row.detail.text()
            val inUse = stringResource(R.string.tech_in_use).takeIf { row.inUse }
            SpecRow(
                label = language,
                value = value,
                detail = detail,
                note = row.note,
                inUse = row.inUse,
                readAs = listOfNotNull(language, value, detail, row.note, inUse).joinToString(", "),
                modifier = modifier,
            )
        }
    }
}

/**
 * A read-only row: [label] and [note] left, [value] and [detail] right, capped at the value width;
 * [stacked] puts the value under the label (a file name). The margin holds the in-use dot; read as [readAs].
 */
@Composable
private fun SpecRow(
    label: String,
    value: String?,
    readAs: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    note: String? = null,
    inUse: Boolean = false,
    stacked: Boolean = false,
) {
    val c = Hl.colors
    // Focus feeds the semantics below.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The in-use dot centres on the label's cap height.
    val capitals = with(LocalDensity.current) { (Hl.type.entry.fontSize.toPx() * CAP_HEIGHT / 2).roundToInt() }
    HlRowSurface(
        onClick = {},
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = readAs
                this.focused = focused
            },
        interactionSource = interaction,
    ) { _ ->
        val ink = LocalContentColor.current
        val secondary = if (focused) ink else c.contentSecondary
        val quiet = if (focused) c.onFocusSecondary else c.contentTertiary
        // Text centred vertically; label and value share a baseline.
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
                if (inUse) StatusLight(StatusState.On, ink = if (focused) ink else null)
            }
            if (stacked) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = Hl.type.entry, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    value?.let {
                        Text(it, style = Hl.type.meta, color = secondary, maxLines = STACKED_LINES, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Column(Modifier.weight(1f).alignByBaseline()) {
                    Text(label, style = Hl.type.entry, maxLines = LINES, overflow = TextOverflow.Ellipsis)
                    note?.let { Text(it, style = Hl.type.caption, color = quiet, maxLines = LINES, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(HlSpace.m))
                Column(
                    Modifier.widthIn(max = TechSpecsDefaults.valueWidth).alignByBaseline(),
                    horizontalAlignment = Alignment.End,
                ) {
                    value?.let { Value(it, Hl.type.meta, secondary) }
                    detail?.let { Value(it, Hl.type.caption, quiet) }
                }
            }
        } }
    }
}

@Composable
private fun Value(text: String, style: TextStyle, color: Color) =
    Text(text, style = style, color = color, textAlign = TextAlign.End, maxLines = LINES, overflow = TextOverflow.Ellipsis)

/** Maximum lines of a label, value, note or detail; a stacked file name gets [STACKED_LINES]. */
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

/** Values joined a fact's space apart; null when empty. */
@Composable
private fun List<TechValue>.text(): String? = map { it.text() }.takeIf { it.isNotEmpty() }?.joinToString(FACT_GAP)

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
