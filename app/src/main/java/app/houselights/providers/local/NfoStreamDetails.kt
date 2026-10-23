package app.houselights.providers.local

import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
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
                codec = Codecs.video(codec),
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
                codec = Codecs.audio(codec),
                channels = channels,
                language = language,
                atmos = c.contains("atmos"),
                // "dtshd_ma_x", "dtshd_ma_x_imax": Kodi 21's spelling of DTS:X.
                dtsX = c.contains("dtsx") || c.contains("dts:x") || c.startsWith("dtshd_ma_x"),
            )
        }
        val subtitles = streams.subtitleLanguages.map { SubtitleTrack(language = it, format = Codecs.UNKNOWN) }
        return (base ?: MediaInfo()).copy(
            video = video,
            audio = audio.ifEmpty { base?.audio.orEmpty() },
            subtitles = subtitles.ifEmpty { base?.subtitles.orEmpty() },
            // An NFO lists tracks in an order of its own.
            tracksInOrder = base?.tracksInOrder == true && audio.isEmpty() && subtitles.isEmpty(),
        )
    }
}
