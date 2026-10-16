package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every source names formats its own way; the app shows one name for each. */
class CodecsTest {
    @Test
    fun video() {
        assertEquals("HEVC", Codecs.video("x265"))
        assertEquals("AVC", Codecs.video("h264"))
        assertEquals("MPEG-2", Codecs.video("mpeg2video"))
        assertEquals("?", Codecs.video(null))
        assertEquals("PRORES", Codecs.video("prores"))
    }

    @Test
    fun audio() {
        assertEquals("DTS-HD MA", Codecs.audio("dts", profile = "DTS-HD MA"))
        assertEquals("DTS-HD MA", Codecs.audio("DTSHD_MA"))
        assertEquals("DTS-HD HRA", Codecs.audio("dts", profile = "DTS-HD HRA"))
        assertEquals("DTS", Codecs.audio("dts"))
        assertEquals("TrueHD", Codecs.audio("truehd"))
        assertEquals("E-AC3", Codecs.audio("eac3"))
        assertEquals("PCM", Codecs.audio("pcm_s24le"))
        assertEquals("Opus", Codecs.audio("opus"))
    }

    /** What Kodi writes in an NFO's <streamdetails>: ffmpeg's names, a video's FOURCC, the layers of DTS. */
    @Test
    fun kodiNames() {
        val videos = mapOf(
            "h264" to "AVC", "avc1" to "AVC", "hevc" to "HEVC", "hvc1" to "HEVC", "mpeg2video" to "MPEG-2",
            "vc1" to "VC-1", "wvc1" to "VC-1", "wmv3" to "VC-1", "av1" to "AV1",
            "xvid" to "MPEG-4", "divx" to "MPEG-4", "dx50" to "MPEG-4", "mpeg4" to "MPEG-4",
        )
        videos.forEach { (kodi, shown) -> assertEquals(kodi, shown, Codecs.video(kodi)) }
        val sounds = mapOf(
            "dca" to "DTS", "dtshd_ma" to "DTS-HD MA", "dtshd_hra" to "DTS-HD HRA", "truehd" to "TrueHD",
            "eac3" to "E-AC3", "ac3" to "AC3", "aac" to "AAC", "flac" to "FLAC", "mp3" to "MP3", "vorbis" to "Vorbis",
            "pcm_s16le" to "PCM",
        )
        sounds.forEach { (kodi, shown) -> assertEquals(kodi, shown, Codecs.audio(kodi)) }
        // High Resolution says DTS-HD too: it is not Master Audio.
        assertEquals("DTS-HD HRA", Codecs.audio("DTS-HD HRA"))
    }

    @Test
    fun subtitles() {
        assertEquals("SRT", Codecs.subtitle("subrip"))
        assertEquals("PGS", Codecs.subtitle("hdmv_pgs_subtitle"))
        assertEquals("VobSub", Codecs.subtitle("dvd_subtitle"))
    }
}
