package app.houselights.core

import kotlinx.serialization.Serializable

/**
 * Picture definition. [token] is stored in badges and matched by filters, so it never changes; [label] is
 * the display name, in the style of the other formats (Ultra HD Blu-ray, Dolby Vision, HDR10).
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

        /** Display text of a badge: definitions by label, other formats as stored. */
        fun label(badge: String): String = entries.firstOrNull { it.token == badge }?.label ?: badge
    }
}

/** Badge tokens from a file's name or sound, besides definitions, HDR formats and codecs; they never change. */
object BadgeTokens {
    /** High dynamic range of a kind the file name does not specify. */
    const val HDR = "HDR"

    /** Dolby's object-based sound, after the track's codec ("TrueHD Atmos") or alone in a file name. */
    const val ATMOS = "Atmos"

    /** DTS's object-based sound. */
    const val DTS_X = "DTS:X"

    /** A disc's streams copied unchanged: how the file was made, not a format. */
    const val REMUX = "Remux"
}

enum class FormatFamily { PICTURE, SOUND }

data class FormatMark(val label: String, val family: FormatFamily)

/**
 * The formats a title's page shows, named as a Blu-ray case prints them (Dolby Digital Plus, not E-AC3).
 * Nothing is listed twice: a Dolby Vision file's HDR10 base is implied, and "TrueHD" plus "Atmos" is one format.
 */
object Formats {
    /** A title's formats from its stored badges, for a page whose file was not probed. */
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

    /** A file's picture formats: definition, [ranges] and codec; the screen formats the frame rate itself. */
    fun picture(video: VideoInfo): List<String> =
        listOfNotNull(video.definition?.label) + ranges(video) + listOfNotNull(video.codec.takeIf { it != Codecs.UNKNOWN })

    /** Ranges as a case names them: Dolby Vision (plus HDR10+) without its HDR10 or HLG base; empty for SDR. */
    fun ranges(video: VideoInfo): List<String> {
        val dolbyVision = HdrFormat.DOLBY_VISION in video.hdr
        return video.hdr.filterNot { dolbyVision && (it == HdrFormat.HDR10 || it == HdrFormat.HLG) }.map(HdrFormat::token)
    }

    /** A file's sound: the best track's full name, then its channels. */
    fun sound(audio: List<AudioTrack>): List<String> {
        val best = bestTrack(audio) ?: return emptyList()
        return listOfNotNull(soundName(best), best.layout)
    }

    /** The best track for a home cinema: object-based, then lossless, DTS, Dolby Digital Plus; more channels first. */
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

    /** Rank of a track's kind, added to its channel count; spaced so the kind always wins. */
    private const val RANK_OBJECTS = 100
    private const val RANK_LOSSLESS = 80
    private const val RANK_DTS = 60
    private const val RANK_DOLBY_DIGITAL_PLUS = 50
    private const val RANK_OTHER = 30

    fun soundName(track: AudioTrack): String = soundName(track.format)

    /** A stored sound format ("TrueHD Atmos") as a case names it ("Dolby TrueHD Atmos", "Dolby Digital Plus"). */
    fun soundName(format: String): String {
        val atmos = format == BadgeTokens.ATMOS || format.endsWith(" ${BadgeTokens.ATMOS}")
        val codec = format.removeSuffix(BadgeTokens.ATMOS).trim()
        return when {
            atmos && codec.isEmpty() -> DOLBY_ATMOS
            atmos -> "${codecName(codec)} ${BadgeTokens.ATMOS}"
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
        Codecs.TRUEHD in tokens && BadgeTokens.ATMOS in tokens -> "${Codecs.TRUEHD} ${BadgeTokens.ATMOS}"
        else -> tokens.firstOrNull()
    }

    /** Snaps a frame rate to a film or broadcast standard within 0.1 % (23.976023976 → 23.976), else to 0.001. */
    fun frameRate(fps: Float): Double {
        val standard = FRAME_RATES.minBy { kotlin.math.abs(it - fps) }
        return if (kotlin.math.abs(standard - fps) / standard < FRAME_RATE_TOLERANCE) standard
        else kotlin.math.round(fps * THOUSANDTHS) / THOUSANDTHS
    }

    private const val DOLBY_ATMOS = "Dolby Atmos"
    private const val FRAME_RATE_TOLERANCE = 0.001
    private const val THOUSANDTHS = 1000.0

    /** Ranges, best first, so the first match is the one kept. A file name's unspecified HDR comes last. */
    private val RANGES = HdrFormat.entries.map(HdrFormat::token) + BadgeTokens.HDR

    /** Badge tokens that say how a file was made rather than a format. */
    private val NOT_FORMATS = setOf(BadgeTokens.REMUX)
    private val CHANNELS = Regex("""\d\.\d|\d+ ch""")
    private val FRAME_RATES = listOf(23.976, 24.0, 25.0, 29.97, 30.0, 47.952, 48.0, 50.0, 59.94, 60.0, 100.0, 119.88, 120.0)
}

/** HDR formats, best first; [token] is the name a case prints, stored in badges, so it never changes. */
@Serializable
enum class HdrFormat(val token: String) {
    DOLBY_VISION("Dolby Vision"),
    HDR10_PLUS("HDR10+"),
    HDR10("HDR10"),
    HLG("HLG"),
}

@Serializable
data class VideoInfo(
    /** HEVC, AVC, AV1, VP9, VC-1, MPEG-2… */
    val codec: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val hdr: List<HdrFormat> = emptyList(),
    val dolbyVisionProfile: Int? = null,
    /** Frames per second as the file states it: 23.976023976 for a film ([Formats.frameRate] snaps it). */
    val frameRate: Float? = null,
    /** Dolby Vision base-layer compatibility: 1 HDR10, 2 SDR, 4 HLG (the 1 of "8.1"). */
    val dolbyVisionCompatibility: Int? = null,
    /** Bitrate in kb/s, from the file's tags or as a server reports it. */
    val bitrateKbps: Int? = null,
) {
    val definition: Definition? get() = Definition.of(width)

    /** Width over height, snapped to a standard ratio within 1 %: 2.39 for a 3832 × 1600 scope film. */
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
    /** Bitrate in kb/s, from the file's tags or as a server reports it. */
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
            atmos -> "$codec ${BadgeTokens.ATMOS}"
            dtsX -> BadgeTokens.DTS_X
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
    /** Version of the file probe that read this; 0 for unversioned readings and server reports. */
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
        video?.hdr?.forEach { add(it.token) }
        // Only the best audio track: the one a home cinema picks.
        val best = Formats.bestTrack(audio)
        best?.let { add(it.format) }
        best?.layout?.let(::add)
    }.distinct()
}

/** Converts bit/s (file tags, servers) to the stored kb/s ([VideoInfo.bitrateKbps]), rounding half up. */
fun bpsToKbps(bps: Long): Int = ((bps + BITS_PER_KILOBIT / 2) / BITS_PER_KILOBIT).toInt()

private const val BITS_PER_KILOBIT = 1000L
