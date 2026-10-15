package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.Formats
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
import app.houselights.core.TrackNames
import app.houselights.ui.design.TechSpecsDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDimensions
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.formatRatio
import app.houselights.ui.util.languageName
import java.util.Locale

/**
 * The technical sheet of a file, set like the specifications on the back of a Blu-ray case: one
 * block per subject, each opened by a hairline and its name. The picture first, in the technical
 * voice; then every audio and subtitle track in aligned tables, the one the player starts with
 * marked; then the file itself. It does not scroll itself: the sheet it is read in does, whole.
 */
@Composable
fun TechSpecs(media: MediaInfo, modifier: Modifier = Modifier, fileName: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        media.video?.let { InfoBlock(stringResource(R.string.tech_video)) { PictureSpecs(media) } }
        if (media.audio.isNotEmpty() || media.subtitles.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
                if (media.audio.isNotEmpty()) {
                    InfoBlock(stringResource(R.string.tech_audio), Modifier.weight(AUDIO_SHARE)) { AudioTable(media.audio) }
                }
                if (media.subtitles.isNotEmpty()) {
                    InfoBlock(stringResource(R.string.tech_subtitles), Modifier.weight(1f)) { SubtitleTable(media.subtitles) }
                }
            }
        }
        InfoBlock(stringResource(R.string.tech_file)) { FileSpecs(media, fileName) }
    }
}

/** The audio tracks' share of the width beside the subtitles': their format, layout and bitrate need it. */
private const val AUDIO_SHARE = 2.2f

/** "Ultra HD   2,39:1   HEVC   10 bits   23,976 i/s", then the dynamic range and the exact frame size. */
@Composable
private fun PictureSpecs(media: MediaInfo) {
    val v = media.video ?: return
    FactRow(
        listOf(
            v.definition?.label,
            v.aspectRatio?.let(::formatRatio),
            v.codec,
            v.bitDepth?.let { stringResource(R.string.bit_depth, it) },
            v.frameRate?.let { frameRateLabel(it) },
        ),
        style = Hl.type.spec,
        color = Hl.colors.content,
    )
    Spacer(Modifier.height(HlSpace.xs))
    // Dolby Vision by its profile and what its base layer plays as: "profil 8.1".
    val dynamic = v.hdr.map { format ->
        val profile = v.dolbyVisionProfile?.let { p -> v.dolbyVisionCompatibility?.let { "$p.$it" } ?: "$p" }
        if (format == HdrFormat.DOLBY_VISION && profile != null) stringResource(R.string.dv_profile, profile)
        else MediaInfo.hdrLabel(format)
    }.ifEmpty { listOf(stringResource(R.string.sdr)) }
    val size = if (v.width != null && v.height != null) formatDimensions(v.width, v.height) else null
    FactRow(dynamic + size + v.bitrateKbps?.let { megabits(it.toLong()) })
}

/** A bitrate in megabits a second, to the tenth: "15,3 Mb/s". */
@Composable
private fun megabits(kbps: Long): String =
    stringResource(R.string.mbps, String.format(Locale.getDefault(), "%.1f", kbps / KBPS_PER_MBPS))

private const val KBPS_PER_MBPS = 1000.0

/**
 * A track's language as a sheet names it: by its region too when another track speaks the same
 * language from elsewhere ("Français (France)", "Français (Canada)"), else by its language alone.
 */
@Composable
private fun trackLanguages(tracks: List<Pair<String?, String?>>): List<String> {
    val unknown = stringResource(R.string.unknown_language)
    val names = tracks.map { (language, _) -> languageName(language) ?: unknown }
    val regions = tracks.map { (language, title) -> TrackNames.region(language, title) }
    return names.mapIndexed { i, name ->
        val alike = names.indices.filter { names[it] == name }
        val region = regions[i]
        if (region != null && alike.size > 1 && alike.map { regions[it] }.distinct().size > 1) {
            "$name (${java.util.Locale("", region).getDisplayCountry(Locale.getDefault())})"
        } else {
            name
        }
    }
}

@Composable
private fun AudioTable(tracks: List<AudioTrack>) {
    // The track the player starts with comes first.
    val ordered = tracks.sortedByDescending { it.default }
    val main = ordered.first()
    val languages = trackLanguages(ordered.map { it.language to it.title })
    Column {
        ordered.forEachIndexed { i, t ->
            SpecRow(
                marked = t === main,
                language = languages[i],
                cells = listOf(
                    Formats.soundName(t) to TechSpecsDefaults.soundColumn,
                    (t.layout ?: "") to TechSpecsDefaults.layoutColumn,
                    (t.bitrateKbps?.let { audioBitrateLabel(it) } ?: "") to TechSpecsDefaults.bitrateColumn,
                ),
                // Its title only when it says more than the row: a commentary, a restored version.
                note = TrackNames.note(t.title, t.language),
            )
        }
    }
}

@Composable
private fun SubtitleTable(tracks: List<SubtitleTrack>) {
    val forced = stringResource(R.string.forced_subs)
    val sdh = stringResource(R.string.sdh_subs)
    val ordered = tracks.sortedByDescending { it.default }
    val languages = trackLanguages(ordered.map { it.language to it.title })
    Column {
        ordered.forEachIndexed { i, t ->
            SpecRow(
                marked = t.default,
                language = languages[i],
                cells = listOf(t.format to TechSpecsDefaults.layoutColumn),
                // The dot already says which one is used by default.
                note = listOfNotNull(forced.takeIf { t.forced }, sdh.takeIf { t.hearingImpaired })
                    .joinToString(", ").ifEmpty { null },
            )
        }
    }
}

/**
 * One track: a ember dot when it is the one in use, its language, aligned format columns,
 * and its name or flags in the quietest tone.
 */
@Composable
private fun SpecRow(marked: Boolean, language: String, cells: List<Pair<String, androidx.compose.ui.unit.Dp>>, note: String?) {
    val c = Hl.colors
    Row(Modifier.fillMaxWidth().padding(vertical = HlSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(HlSize.dot).clip(HlShapes.avatar).background(if (marked) c.ember else Color.Transparent))
        Spacer(Modifier.width(HlSpace.s))
        Text(language, style = Hl.type.meta, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(HlLayout.specLanguage))
        cells.forEach { (text, width) ->
            Text(text, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width))
        }
        if (note != null) {
            Text(note, style = Hl.type.caption, color = c.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** Container, size, average bitrate and duration, then the file name. */
@Composable
private fun FileSpecs(media: MediaInfo, fileName: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxl)) {
        media.container?.let { Fact(stringResource(R.string.tech_container), it) }
        media.sizeBytes?.let { Fact(stringResource(R.string.tech_size), formatBytes(it)) }
        media.bitrateKbps?.let { Fact(stringResource(R.string.tech_bitrate), megabits(it)) }
        media.durationMs?.let { Fact(stringResource(R.string.tech_duration), formatDuration(it)) }
    }
    if (fileName != null) {
        Spacer(Modifier.height(HlSpace.s))
        Text(fileName, style = Hl.type.caption, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = Hl.type.caption, color = Hl.colors.contentSecondary)
        Text(value, style = Hl.type.meta, color = Hl.colors.content)
    }
}
