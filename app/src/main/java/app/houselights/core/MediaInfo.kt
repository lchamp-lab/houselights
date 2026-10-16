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

/** What a format speaks of. */
enum class FormatFamily { PICTURE, SOUND }

/** A format as a page shows it: its [label], and what it speaks of. */
data class FormatMark(val label: String, val family: FormatFamily)

/**
 * The formats a title's page shows, as a Blu-ray case prints them: the picture (its definition, its
 * range, its codec, its frame rate), then the sound (its best track by the name on the case, Dolby
 * Digital Plus rather than E-AC3, then its channels). Nothing is said twice: a Dolby Vision file
 * carries its HDR10 base, a name's "TrueHD" and "Atmos" are one format.
 */
object Formats {
    /**
     * A title's formats from the badges stored for it (which filters and searches read as they
     * are), for a page whose file was not read: its definition and best range, its sound and its
     * channels.
     */
    fun fromBadges(badges: List<String>): List<FormatMark> {
        val tokens = badges.distinct()
        val definition = tokens.firstNotNullOfOrNull { t -> Definition.entries.firstOrNull { it.token == t } }
        val range = RANGES.firstOrNull { it in tokens }
        val channels = tokens.firstOrNull { CHANNELS.matches(it) }
        val sound = soundToken(
            tokens.filter { it !in RANGES && it != channels && it !in NOT_FORMATS && Definition.entries.none { d -> d.token == it } },
        )
        return listOfNotNull(
            definition?.let { FormatMark(it.label, FormatFamily.PICTURE) },
            range?.let { FormatMark(it, FormatFamily.PICTURE) },
            sound?.let { FormatMark(soundName(it), FormatFamily.SOUND) },
            channels?.let { FormatMark(it, FormatFamily.SOUND) },
        )
    }

    /**
     * What a page says of a file's picture: its definition, its range (Dolby Vision with HDR10+ when
     * it has both, its HDR10 or HLG base unsaid), its codec. The frame rate is the screen's to write.
     */
    fun picture(video: VideoInfo): List<String> =
        listOfNotNull(video.definition?.label) + ranges(video) + listOfNotNull(video.codec.takeIf { it != Codecs.UNKNOWN })

    /**
     * A picture's ranges as a case names them: Dolby Vision, with HDR10+ when it has both, its
     * HDR10 or HLG base unsaid. Empty for SDR.
     */
    fun ranges(video: VideoInfo): List<String> {
        val dolbyVision = HdrFormat.DOLBY_VISION in video.hdr
        return video.hdr.map(MediaInfo::hdrLabel).filterNot { dolbyVision && (it == HDR10 || it == HLG) }
    }

    /** What a page says of a file's sound: its best track by its full name, then its channels. */
    fun sound(audio: List<AudioTrack>): List<String> {
        val best = bestTrack(audio) ?: return emptyList()
        return listOfNotNull(soundName(best), best.layout)
    }

    /** The track a home cinema plays best: object-based, then lossless, DTS, Dolby Digital Plus; more channels first. */
    fun bestTrack(audio: List<AudioTrack>): AudioTrack? = audio.maxByOrNull { track ->
        val codec = when {
            track.atmos || track.dtsX -> RANK_OBJECTS
            Codecs.isLossless(track.codec) -> RANK_LOSSLESS
            track.codec.startsWith(Codecs.DTS) -> RANK_DTS
            track.codec == Codecs.EAC3 -> RANK_DOLBY_DIGITAL_PLUS
            else -> RANK_OTHER
        }
        codec + (track.channels ?: 0)
    }

    /** How a track ranks by its kind, before its channels count: far apart, so a kind always wins. */
    private const val RANK_OBJECTS = 100
    private const val RANK_LOSSLESS = 80
    private const val RANK_DTS = 60
    private const val RANK_DOLBY_DIGITAL_PLUS = 50
    private const val RANK_OTHER = 30

    /** A track's format as a case names it. */
    fun soundName(track: AudioTrack): String = soundName(track.format)

    /**
     * A sound format ("TrueHD Atmos", "E-AC3", "DTS-HD MA", as tracks and badges store them) as a
     * case names it: Dolby Digital, Dolby Digital Plus, Dolby TrueHD, DTS-HD Master Audio, LPCM. With
     * Atmos, the track it rides on: "Dolby TrueHD Atmos" (lossless), "Dolby Digital Plus Atmos".
     */
    fun soundName(format: String): String {
        val atmos = format == ATMOS || format.endsWith(" $ATMOS")
        val codec = format.removeSuffix(ATMOS).trim()
        return when {
            atmos && codec.isEmpty() -> DOLBY_ATMOS
            atmos -> "${codecName(codec)} $ATMOS"
            else -> codecName(codec)
        }
    }

    private fun codecName(codec: String): String = when (codec) {
        Codecs.TRUEHD -> "Dolby TrueHD"
        Codecs.EAC3 -> "Dolby Digital Plus"
        Codecs.AC3 -> "Dolby Digital"
        Codecs.DTS_HD_MA -> "DTS-HD Master Audio"
        Codecs.DTS_HD_HRA -> "DTS-HD High Resolution"
        Codecs.PCM -> "LPCM"
        else -> codec
    }

    /** The sound among stored badges: a name's "TrueHD" and "Atmos" read as one format. */
    private fun soundToken(tokens: List<String>): String? = when {
        Codecs.TRUEHD in tokens && ATMOS in tokens -> "${Codecs.TRUEHD} $ATMOS"
        else -> tokens.firstOrNull()
    }

    /**
     * A frame rate as the ones films and broadcasts are made at, when within 0.1 % of one (a file
     * says 23.976023976 for 24000/1001): 23.976, 24, 25, 29.97… Else to the thousandth.
     */
    fun frameRate(fps: Float): Double {
        val standard = FRAME_RATES.minBy { kotlin.math.abs(it - fps) }
        return if (kotlin.math.abs(standard - fps) / standard < FRAME_RATE_TOLERANCE) standard
        else kotlin.math.round(fps * THOUSANDTHS) / THOUSANDTHS
    }

    private const val DOLBY_VISION = "Dolby Vision"
    private const val HDR10 = "HDR10"
    private const val HLG = "HLG"
    private const val ATMOS = "Atmos"
    private const val DOLBY_ATMOS = "Dolby Atmos"
    private const val FRAME_RATE_TOLERANCE = 0.001
    private const val THOUSANDTHS = 1000.0

    /** Ranges, best first: the one a line keeps. */
    private val RANGES = listOf(DOLBY_VISION, "HDR10+", HDR10, HLG, "HDR")

    /** What badges hold that is not a format: how a file was made. */
    private val NOT_FORMATS = setOf("Remux")
    private val CHANNELS = Regex("""\d\.\d|\d+ ch""")
    private val FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 47.952, 48.0, 50.0, 59.94, 60.0, 100.0, 119.88, 120.0)
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
    /** Frames per second, as the file says it: 23.976023976 for a film ([Formats.frameRate] names it). */
    val frameRate: Float? = null,
    /** What a Dolby Vision file's base layer plays as elsewhere: 1 HDR10, 2 SDR, 4 HLG ("8.1"). */
    val dolbyVisionCompatibility: Int? = null,
    /** Its bitrate in kb/s, as measured when the file was made (its tags) or as a server reports it. */
    val bitrateKbps: Int? = null,
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
    /** Its bitrate in kb/s, as measured when the file was made (its tags) or as a server reports it. */
    val bitrateKbps: Int? = null,
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
    /** Which version of the file probe read this: 0 for older readings, and for what servers report. */
    val probedWith: Int = 0,
) {
    val bitrateKbps: Long?
        get() {
            val size = sizeBytes ?: return null
            val ms = durationMs?.takeIf { it > 0 } ?: return null
            return size * 8 / ms
        }

    /** Short format marks for badges and filters: 4K, Dolby Vision, HDR10, TrueHD Atmos, 7.1. */
    fun badges(): List<String> = buildList {
        video?.definition?.let { add(it.token) }
        video?.hdr?.forEach { add(hdrLabel(it)) }
        // Show the best audio of the file, which is what a home cinema will pick.
        val best = Formats.bestTrack(audio)
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
    }
}
