package app.houselights.ui.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
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
 * marked; then the file itself.
 */
@Composable
fun TechSpecs(media: MediaInfo, modifier: Modifier = Modifier, fileName: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        media.video?.let { InfoBlock(stringResource(R.string.tech_video)) { PictureSpecs(media) } }
        if (media.audio.isNotEmpty() || media.subtitles.isNotEmpty()) {
            HlScrollArea(Modifier.heightIn(max = HlLayout.specTables), framed = false, padding = HlSpace.none) {
                Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
                    if (media.audio.isNotEmpty()) {
                        InfoBlock(stringResource(R.string.tech_audio), Modifier.weight(1.4f)) { AudioTable(media.audio) }
                    }
                    if (media.subtitles.isNotEmpty()) {
                        InfoBlock(stringResource(R.string.tech_subtitles), Modifier.weight(1f)) { SubtitleTable(media.subtitles) }
                    }
                }
            }
        }
        InfoBlock(stringResource(R.string.tech_file)) { FileSpecs(media, fileName) }
    }
}

/** "Ultra HD   2,39:1   HEVC   10 bits", then the dynamic range and the exact frame size. */
@Composable
private fun PictureSpecs(media: MediaInfo) {
    val v = media.video ?: return
    FactRow(
        listOf(
            v.definition?.label,
            v.aspectRatio?.let(::formatRatio),
            v.codec,
            v.bitDepth?.let { stringResource(R.string.bit_depth, it) },
        ),
        style = Hl.type.spec,
        color = Hl.colors.content,
    )
    Spacer(Modifier.height(HlSpace.xs))
    val dynamic = v.hdr.map { format ->
        if (format == HdrFormat.DOLBY_VISION && v.dolbyVisionProfile != null) stringResource(R.string.dv_profile, v.dolbyVisionProfile)
        else MediaInfo.hdrLabel(format)
    }.ifEmpty { listOf(stringResource(R.string.sdr)) }
    val size = if (v.width != null && v.height != null) formatDimensions(v.width, v.height) else null
    FactRow(dynamic + size)
}

@Composable
private fun AudioTable(tracks: List<AudioTrack>) {
    // The track the player starts with comes first.
    val ordered = tracks.sortedByDescending { it.default }
    val main = ordered.first()
    Column {
        ordered.forEach { t ->
            SpecRow(
                marked = t === main,
                language = languageName(t.language) ?: stringResource(R.string.unknown_language),
                cells = listOf(t.format to HlLayout.specFormat, (t.layout ?: "") to HlLayout.specLayout),
                note = t.title?.takeIf { it.isNotBlank() },
            )
        }
    }
}

@Composable
private fun SubtitleTable(tracks: List<SubtitleTrack>) {
    val forced = stringResource(R.string.forced_subs)
    val sdh = stringResource(R.string.sdh_subs)
    Column {
        tracks.sortedByDescending { it.default }.forEach { t ->
            SpecRow(
                marked = t.default,
                language = languageName(t.language) ?: stringResource(R.string.unknown_language),
                cells = listOf(t.format to HlLayout.specLayout),
                // The dot already says which one is used by default.
                note = listOfNotNull(forced.takeIf { t.forced }, sdh.takeIf { t.hearingImpaired })
                    .joinToString(", ").ifEmpty { null },
            )
        }
    }
}

/**
 * One track: a tungsten dot when it is the one in use, its language, aligned format columns,
 * and its name or flags in the quietest tone.
 */
@Composable
private fun SpecRow(marked: Boolean, language: String, cells: List<Pair<String, androidx.compose.ui.unit.Dp>>, note: String?) {
    val c = Hl.colors
    Row(Modifier.fillMaxWidth().padding(vertical = HlSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(HlSize.dot).clip(HlShapes.avatar).background(if (marked) c.tungsten else Color.Transparent))
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
        media.bitrateKbps?.let { kbps ->
            Fact(stringResource(R.string.tech_bitrate), stringResource(R.string.mbps, String.format(Locale.getDefault(), "%.1f", kbps / 1000.0)))
        }
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
