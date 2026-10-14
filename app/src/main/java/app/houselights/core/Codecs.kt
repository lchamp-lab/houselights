package app.houselights.core

/**
 * The names formats are shown under, whatever told them: the MKV probe, an NFO, a Jellyfin server.
 * [video], [audio] and [subtitle] read the names ffmpeg gives (what servers and NFO files use).
 */
object Codecs {
    const val HEVC = "HEVC"
    const val AVC = "AVC"
    const val AV1 = "AV1"
    const val VP9 = "VP9"
    const val VC1 = "VC-1"
    const val MPEG2 = "MPEG-2"

    const val TRUEHD = "TrueHD"
    const val DTS_HD_MA = "DTS-HD MA"
    const val DTS_HD_HRA = "DTS-HD HRA"
    const val DTS = "DTS"
    const val EAC3 = "E-AC3"
    const val AC3 = "AC3"
    const val AAC = "AAC"
    const val FLAC = "FLAC"
    const val OPUS = "Opus"
    const val PCM = "PCM"
    const val MP3 = "MP3"

    const val SRT = "SRT"
    const val PGS = "PGS"
    const val ASS = "ASS"
    const val VOBSUB = "VobSub"
    const val WEBVTT = "WebVTT"

    /** Shown when a source names no format at all. */
    const val UNKNOWN = "?"

    fun video(name: String?): String = when (name?.lowercase()) {
        null -> UNKNOWN
        "hevc", "h265", "x265" -> HEVC
        "h264", "avc", "avc1", "x264" -> AVC
        "av1" -> AV1
        "vp9" -> VP9
        "vc1", "vc-1" -> VC1
        "mpeg2video", "mpeg2" -> MPEG2
        else -> name.uppercase()
    }

    /** [profile]: what else the source says of the track ("DTS-HD MA", "Dolby TrueHD + Atmos"…). */
    fun audio(name: String?, profile: String = ""): String {
        val c = name?.lowercase() ?: return UNKNOWN
        return when {
            c.contains("truehd") -> TRUEHD
            c.startsWith("dts") -> dts(c, profile)
            c == "eac3" || c == "e-ac-3" -> EAC3
            c == "ac3" -> AC3
            c == "aac" -> AAC
            c == "flac" -> FLAC
            c == "opus" -> OPUS
            c.startsWith("pcm") -> PCM
            c == "mp3" -> MP3
            else -> name.uppercase()
        }
    }

    private fun dts(c: String, profile: String) = when {
        c.contains("dtshd_ma") || c.contains("dts-hd") || profile.contains("MA") -> DTS_HD_MA
        profile.contains("HRA") -> DTS_HD_HRA
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
