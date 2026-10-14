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

    @Test
    fun subtitles() {
        assertEquals("SRT", Codecs.subtitle("subrip"))
        assertEquals("PGS", Codecs.subtitle("hdmv_pgs_subtitle"))
        assertEquals("VobSub", Codecs.subtitle("dvd_subtitle"))
    }
}
