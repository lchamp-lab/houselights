package app.houselights.core

/**
 * The names formats are shown under, whatever told them: the MKV probe, an NFO, a Jellyfin server.
 * [video], [audio] and [subtitle] read the names ffmpeg gives (what servers and NFO files use), and
 * Kodi's own: the FOURCC of a video that has one ("xvid", "hvc1"), the layers of DTS ("dtshd_hra").
 */
object Codecs {
    const val HEVC = "HEVC"
    const val AVC = "AVC"
    const val AV1 = "AV1"
    const val VP9 = "VP9"
    const val VC1 = "VC-1"
    const val MPEG2 = "MPEG-2"

    /** MPEG-4 Part 2, as XviD and DivX encode it. */
    const val MPEG4 = "MPEG-4"

    const val TRUEHD = "TrueHD"
    const val DTS_HD_MA = "DTS-HD MA"
    const val DTS_HD_HRA = "DTS-HD HRA"
    const val DTS_EXPRESS = "DTS Express"
    const val DTS = "DTS"
    const val EAC3 = "E-AC3"
    const val AC3 = "AC3"
    const val AAC = "AAC"
    const val FLAC = "FLAC"
    const val OPUS = "Opus"
    const val PCM = "PCM"
    const val MP3 = "MP3"
    const val VORBIS = "Vorbis"

    const val SRT = "SRT"
    const val PGS = "PGS"
    const val ASS = "ASS"
    const val VOBSUB = "VobSub"
    const val WEBVTT = "WebVTT"

    /** Shown when a source names no format at all. */
    const val UNKNOWN = "?"

    /** A FOURCC no name is known for ("DIV3") is shown as it is. */
    fun video(name: String?): String = when (name?.lowercase()) {
        null -> UNKNOWN
        "hevc", "h265", "x265", "hvc1", "hev1" -> HEVC
        "h264", "avc", "avc1", "x264" -> AVC
        "av1", "av01" -> AV1
        "vp9", "vp09" -> VP9
        // Windows Media Video 9 is VC-1 too.
        "vc1", "vc-1", "wvc1", "wmv3", "wmva" -> VC1
        "mpeg2video", "mpeg2", "mpg2" -> MPEG2
        "mpeg4", "xvid", "divx", "dx50", "fmp4", "mp4v", "3iv2", "m4s2" -> MPEG4
        else -> name.uppercase()
    }

    /** [profile]: what else the source says of the track ("DTS-HD MA", "Dolby TrueHD + Atmos"…). */
    fun audio(name: String?, profile: String = ""): String {
        val c = name?.lowercase() ?: return UNKNOWN
        return when {
            c.contains("truehd") -> TRUEHD
            // "dca": DTS as Kodi writes it, after ffmpeg's decoder.
            c.startsWith("dts") || c == "dca" -> dts(c, profile)
            c.startsWith("aac") -> AAC
            // "eac3_ddp_atmos": Kodi 21 names Atmos over Dolby Digital Plus in the codec.
            c.startsWith("eac3_") -> EAC3
            c.startsWith("pcm") -> PCM
            else -> AUDIO_NAMES[c] ?: name.uppercase()
        }
    }

    private val AUDIO_NAMES = mapOf(
        "eac3" to EAC3, "e-ac-3" to EAC3, "ec-3" to EAC3,
        "ac3" to AC3, "ac-3" to AC3,
        "flac" to FLAC, "opus" to OPUS, "mp3" to MP3, "lpcm" to PCM, "vorbis" to VORBIS,
    )

    /** High Resolution before Master Audio: "DTS-HD HRA" names DTS-HD too. */
    private fun dts(c: String, profile: String) = when {
        c.contains("hra") || profile.contains("HRA") -> DTS_HD_HRA
        c.contains("dtshd_ma") || c.contains("dts-hd") || profile.contains("MA") -> DTS_HD_MA
        else -> DTS
    }

    fun subtitle(name: String?): String = when (name?.lowercase()) {
        null -> UNKNOWN
        "subrip", "srt" -> SRT
        "pgssub", "pgs", "hdmv_pgs_subtitle" -> PGS
        "ass", "ssa" -> ASS
        "dvdsub", "vobsub", "dvd_subtitle" -> VOBSUB
        "webvtt" -> WEBVTT
        else -> name.uppercase()
    }

    /** Lossless tracks, which a home cinema plays best. */
    fun isLossless(codec: String): Boolean = codec == TRUEHD || codec == DTS_HD_MA || codec == PCM || codec == FLAC
}
