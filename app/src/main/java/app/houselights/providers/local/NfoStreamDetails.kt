package app.houselights.providers.local

import app.houselights.core.AudioTrack
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.NfoStreams
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo

/** Turns the <streamdetails> of an NFO into [MediaInfo], for files the probe cannot read. */
object NfoStreamDetails {
    fun toMediaInfo(streams: NfoStreams, base: MediaInfo?): MediaInfo {
        val video = streams.videoCodec?.let { codec ->
            VideoInfo(
                codec = when (codec.lowercase()) {
                    "hevc", "h265", "x265" -> "HEVC"
                    "h264", "avc", "avc1", "x264" -> "AVC"
                    "av1" -> "AV1"
                    "vc1", "vc-1" -> "VC-1"
                    "mpeg2video", "mpeg2" -> "MPEG-2"
                    else -> codec.uppercase()
                },
                width = streams.width,
                height = streams.height,
                hdr = when (streams.hdrType?.lowercase()) {
                    "dolbyvision" -> listOf(HdrFormat.DOLBY_VISION)
                    "hdr10" -> listOf(HdrFormat.HDR10)
                    "hdr10plus", "hdr10+" -> listOf(HdrFormat.HDR10_PLUS)
                    "hlg" -> listOf(HdrFormat.HLG)
                    else -> emptyList()
                },
            )
        }
        val audio = streams.audio.map { (codec, language, channels) ->
            val c = codec?.lowercase().orEmpty()
            AudioTrack(
                codec = when {
                    c.contains("truehd") -> "TrueHD"
                    c.contains("dtshd_ma") || c.contains("dts-hd") -> "DTS-HD MA"
                    c.startsWith("dts") -> "DTS"
                    c == "eac3" -> "E-AC3"
                    c == "ac3" -> "AC3"
                    c == "aac" -> "AAC"
                    c == "flac" -> "FLAC"
                    else -> codec?.uppercase() ?: "?"
                },
                channels = channels,
                language = language,
                atmos = c.contains("atmos"),
                dtsX = c.contains("dtsx") || c.contains("dts:x"),
            )
        }
        val subtitles = streams.subtitleLanguages.map { SubtitleTrack(language = it, format = "?") }
        return (base ?: MediaInfo()).copy(
            video = video,
            audio = audio.ifEmpty { base?.audio.orEmpty() },
            subtitles = subtitles.ifEmpty { base?.subtitles.orEmpty() },
        )
    }
}
