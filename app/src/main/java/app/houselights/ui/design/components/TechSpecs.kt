package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.MediaInfo
import app.houselights.core.SubtitleTrack
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.languageName
import java.util.Locale

private const val MAX_AUDIO = 4
private const val MAX_SUBTITLE_LANGUAGES = 4

/**
 * Spec sheet of a file: video, audio tracks and subtitles side by side, then the file
 * itself. Focusable so the remote can stop on it while scrolling a detail page.
 */
@Composable
fun TechSpecs(media: MediaInfo, modifier: Modifier = Modifier) {
    val c = Hl.colors
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(HlShapes.panel),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(HlSize.hairline, c.outline.copy(alpha = 0.6f)), shape = HlShapes.panel),
            focusedBorder = Border(BorderStroke(HlSize.focusBorder, c.focus), shape = HlShapes.panel),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface.copy(alpha = 0.75f),
            focusedContainerColor = c.surface.copy(alpha = 0.9f),
            contentColor = c.content,
            focusedContentColor = c.content,
        ),
    ) {
        Column(Modifier.padding(HlSpace.l)) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xl)) {
                media.video?.let { VideoColumn(media, Modifier.weight(1f)) }
                if (media.audio.isNotEmpty()) AudioColumn(media.audio, Modifier.weight(1.3f))
                if (media.subtitles.isNotEmpty()) SubtitleColumn(media.subtitles, Modifier.weight(1f))
            }
            Spacer(Modifier.height(HlSpace.l))
            Box(Modifier.fillMaxWidth().height(HlSize.hairline).background(c.outline.copy(alpha = 0.5f)))
            Spacer(Modifier.height(HlSpace.m))
            FileFacts(media)
        }
    }
}

@Composable
private fun VideoColumn(media: MediaInfo, modifier: Modifier) {
    val v = media.video ?: return
    Column(modifier) {
        ColumnTitle(stringResource(R.string.tech_video))
        Text(
            listOfNotNull(v.resolution, v.codec, v.bitDepth?.let { stringResource(R.string.bit_depth, it) }).joinToString("   "),
            style = Hl.type.label,
            color = Hl.colors.content,
        )
        Spacer(Modifier.height(HlSpace.xxs))
        val dynamic = v.hdr.map { format ->
            if (format == app.houselights.core.HdrFormat.DOLBY_VISION && v.dolbyVisionProfile != null) {
                stringResource(R.string.dv_profile, v.dolbyVisionProfile)
            } else MediaInfo.hdrLabel(format)
        }.ifEmpty { listOf(stringResource(R.string.sdr)) }
        dynamic.forEach { Text(it, style = Hl.type.bodySmall, color = Hl.colors.contentSecondary) }
        if (v.width != null && v.height != null) {
            Text("${v.width} × ${v.height}", style = Hl.type.caption, color = Hl.colors.contentTertiary)
        }
    }
}

@Composable
private fun AudioColumn(tracks: List<AudioTrack>, modifier: Modifier) {
    Column(modifier) {
        ColumnTitle(stringResource(R.string.tech_audio))
        // Default track first: it is what plays.
        val ordered = tracks.sortedByDescending { it.default }
        ordered.take(MAX_AUDIO).forEach { t ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    languageName(t.language) ?: stringResource(R.string.unknown_language),
                    style = Hl.type.label,
                    color = Hl.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    listOfNotNull(t.format, t.layout).joinToString("  "),
                    style = Hl.type.bodySmall,
                    color = Hl.colors.contentSecondary,
                    maxLines = 1,
                )
                if (t.default) DefaultMark()
            }
        }
        if (ordered.size > MAX_AUDIO) More(ordered.size - MAX_AUDIO)
    }
}

@Composable
private fun SubtitleColumn(tracks: List<SubtitleTrack>, modifier: Modifier) {
    val unknown = stringResource(R.string.unknown_language)
    val forced = stringResource(R.string.forced_subs)
    val sdh = stringResource(R.string.sdh_subs)
    val byLanguage = tracks.groupBy { languageName(it.language) ?: unknown }
    Column(modifier) {
        ColumnTitle(stringResource(R.string.tech_subtitles))
        byLanguage.entries.take(MAX_SUBTITLE_LANGUAGES).forEach { (language, subs) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(language, style = Hl.type.label, color = Hl.colors.content, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = subs.map { it.format }.distinct() +
                    listOfNotNull(forced.takeIf { subs.any { it.forced } }, sdh.takeIf { subs.any { it.hearingImpaired } })
                Text(details.joinToString(", "), style = Hl.type.bodySmall, color = Hl.colors.contentSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (byLanguage.size > MAX_SUBTITLE_LANGUAGES) More(byLanguage.size - MAX_SUBTITLE_LANGUAGES)
    }
}

@Composable
private fun FileFacts(media: MediaInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xxl)) {
        media.container?.let { Fact(stringResource(R.string.tech_container), it) }
        media.sizeBytes?.let { Fact(stringResource(R.string.tech_size), fileSize(it)) }
        media.bitrateKbps?.let { kbps ->
            Fact(stringResource(R.string.tech_bitrate), stringResource(R.string.mbps, String.format(Locale.getDefault(), "%.1f", kbps / 1000.0)))
        }
        media.durationMs?.let { Fact(stringResource(R.string.tech_duration), formatDuration(it)) }
    }
}

/** Decimal units, as drives and NAS report them; "Go" in French. */
@Composable
private fun fileSize(bytes: Long): String = if (bytes >= 1_000_000_000L) {
    stringResource(R.string.gigabytes, String.format(Locale.getDefault(), "%.2f", bytes / 1e9))
} else {
    stringResource(R.string.megabytes, String.format(Locale.getDefault(), "%.0f", bytes / 1e6))
}

@Composable
private fun RowScope.Fact(label: String, value: String) {
    Column {
        Text(label, style = Hl.type.caption, color = Hl.colors.contentTertiary)
        Text(value, style = Hl.type.label, color = Hl.colors.content)
    }
}

@Composable
private fun ColumnTitle(text: String) {
    Text(text, style = Hl.type.section, color = Hl.colors.contentTertiary, modifier = Modifier.padding(bottom = HlSpace.s))
}

/** The default track, marked with a small tungsten light. */
@Composable
private fun DefaultMark() {
    Spacer(Modifier.width(HlSpace.s))
    Box(Modifier.size(6.dp).clip(HlShapes.avatar).background(Hl.colors.tungsten))
}

@Composable
private fun More(count: Int) {
    Text(pluralStringResource(R.plurals.more_tracks, count, count), style = Hl.type.caption, color = Hl.colors.contentTertiary, modifier = Modifier.padding(top = HlSpace.xxs))
}
