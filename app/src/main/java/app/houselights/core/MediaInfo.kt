package app.houselights.core

import kotlinx.serialization.Serializable

/** High dynamic range formats, best first. */
@Serializable
enum class HdrFormat { DOLBY_VISION, HDR10_PLUS, HDR10, HLG }

@Serializable
data class VideoInfo(
    /** HEVC, AVC, AV1, VP9, VC-1, MPEG-2… */
    val codec: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val hdr: List<HdrFormat> = emptyList(),
    val dolbyVisionProfile: Int? = null,
) {
    /** Width over height of the picture: 2.39 for a scope film, 1.78 for 16:9. */
    val aspectRatio: Float?
        get() {
            val w = width ?: return null
            val h = height?.takeIf { it > 0 } ?: return null
            return w.toFloat() / h
        }

    /** 4K, 1080p, 720p or SD, from the frame size (wide films are shorter than 2160). */
    val resolution: String?
        get() {
            val w = width ?: return null
            return when {
                w >= 3200 -> "4K"
                w >= 1800 -> "1080p"
                w >= 1200 -> "720p"
                else -> "SD"
            }
        }
}

@Serializable
data class AudioTrack(
    /** TrueHD, DTS-HD MA, DTS, E-AC3, AC3, AAC, FLAC, PCM, Opus… */
    val codec: String,
    val channels: Int? = null,
    /** ISO 639 code as stored in the file. */
    val language: String? = null,
    val title: String? = null,
    val atmos: Boolean = false,
    val dtsX: Boolean = false,
    val default: Boolean = false,
) {
    /** 7.1, 5.1, 2.0… */
    val layout: String?
        get() = when (channels) {
            null -> null
            1 -> "1.0"
            2 -> "2.0"
            3 -> "2.1"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            else -> "$channels ch"
        }

    /** "TrueHD Atmos", "DTS:X", "DTS-HD MA"… */
    val format: String
        get() = when {
            atmos -> "$codec Atmos"
            dtsX -> "DTS:X"
            else -> codec
        }
}

@Serializable
data class SubtitleTrack(
    val language: String? = null,
    val title: String? = null,
    /** SRT, PGS, ASS, VobSub, WebVTT */
    val format: String,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    val default: Boolean = false,
)

/** What is inside a video file. Read from the file itself, or reported by a media server. */
@Serializable
data class MediaInfo(
    /** MKV, MP4, TS, ISO… */
    val container: String? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val video: VideoInfo? = null,
    val audio: List<AudioTrack> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
) {
    val bitrateKbps: Long?
        get() {
            val size = sizeBytes ?: return null
            val ms = durationMs?.takeIf { it > 0 } ?: return null
            return size * 8 / ms
        }

    /** The track the player starts with: the default one, else the first. */
    val mainAudio: AudioTrack? get() = audio.firstOrNull { it.default } ?: audio.firstOrNull()

    /** Short format marks for badges and filters: 4K, Dolby Vision, HDR10, TrueHD Atmos, 7.1. */
    fun badges(): List<String> = buildList {
        video?.resolution?.let(::add)
        video?.hdr?.forEach { add(hdrLabel(it)) }
        // Show the best audio of the file, which is what a home cinema will pick.
        val best = audio.maxByOrNull { audioRank(it) }
        best?.let { add(it.format) }
        best?.layout?.let(::add)
    }.distinct()

    companion object {
        fun hdrLabel(format: HdrFormat) = when (format) {
            HdrFormat.DOLBY_VISION -> "Dolby Vision"
            HdrFormat.HDR10_PLUS -> "HDR10+"
            HdrFormat.HDR10 -> "HDR10"
            HdrFormat.HLG -> "HLG"
        }

        private fun audioRank(track: AudioTrack): Int {
            val codec = when {
                track.atmos || track.dtsX -> 100
                track.codec == "TrueHD" || track.codec == "DTS-HD MA" || track.codec == "PCM" || track.codec == "FLAC" -> 80
                track.codec.startsWith("DTS") -> 60
                track.codec == "E-AC3" -> 50
                else -> 30
            }
            return codec + (track.channels ?: 0)
        }
    }
}
