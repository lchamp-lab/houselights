package app.houselights.core

import kotlinx.serialization.Serializable

/**
 * Picture definition. [token] is what badges store and filters match, and never changes;
 * [label] is the name shown, from the same family as the other formats (Ultra HD Blu-ray,
 * Dolby Vision, HDR10).
 */
enum class Definition(val token: String, val label: String) {
    ULTRA_HD("4K", "Ultra HD"),
    FULL_HD("1080p", "Full HD"),
    HD("720p", "HD"),
    SD("SD", "SD"),
    ;

    companion object {
        /** From the frame width: wide films are shorter than their definition's height. */
        fun of(width: Int?): Definition? = when {
            width == null -> null
            width >= 3200 -> ULTRA_HD
            width >= 1800 -> FULL_HD
            width >= 1200 -> HD
            else -> SD
        }

        /** How a badge is shown: definitions by name, any other format as it is. */
        fun label(badge: String): String = entries.firstOrNull { it.token == badge }?.label ?: badge
    }
}

/**
 * The formats a title shows, as words, from the badges stored for it (which filters and searches
 * read as they are): definitions by name, nothing said twice (a Dolby Vision file carries its HDR10
 * base, and a name's "HDR" says no more), "TrueHD" and "Atmos" read in a name as one format, and
 * no more than [MAX]: past four, they say little a title's name has not.
 */
object Formats {
    const val MAX = 4

    fun shown(badges: List<String>, max: Int = MAX): List<String> {
        val labels = badges.map(Definition::label).distinct()
        val dolbyVision = DOLBY_VISION in labels
        val merged = if (TRUEHD in labels && ATMOS in labels) TRUEHD_ATMOS else null
        return labels
            .filterNot { dolbyVision && (it == HDR10 || it == HDR) }
            .mapNotNull { label ->
                when {
                    merged == null -> label
                    label == TRUEHD -> merged
                    label == ATMOS -> null
                    else -> label
                }
            }
            .take(max)
    }

    private const val DOLBY_VISION = "Dolby Vision"
    private const val HDR10 = "HDR10"
    private const val HDR = "HDR"
    private const val TRUEHD = "TrueHD"
    private const val ATMOS = "Atmos"
    private const val TRUEHD_ATMOS = "TrueHD Atmos"
}

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
    val definition: Definition? get() = Definition.of(width)

    /**
     * Width over height, snapped to the ratio films are made in when it is within 1 %: 2.39 for a
     * 3832 × 1600 scope film, 1.78 for 16:9.
     */
    val aspectRatio: Float?
        get() {
            val w = width ?: return null
            val h = height?.takeIf { it > 0 } ?: return null
            val raw = w.toFloat() / h
            return RATIOS.minBy { kotlin.math.abs(it - raw) }.takeIf { kotlin.math.abs(it - raw) / raw < 0.01f } ?: raw
        }

    private companion object {
        val RATIOS = floatArrayOf(1.33f, 1.37f, 1.66f, 1.78f, 1.85f, 1.9f, 2f, 2.2f, 2.35f, 2.39f, 2.55f, 2.76f).toList()
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
        video?.definition?.let { add(it.token) }
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
                Codecs.isLossless(track.codec) -> 80
                track.codec.startsWith(Codecs.DTS) -> 60
                track.codec == Codecs.EAC3 -> 50
                else -> 30
            }
            return codec + (track.channels ?: 0)
        }
    }
}
