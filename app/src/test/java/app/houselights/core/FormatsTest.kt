package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a title's formats say on screen: the picture, then the sound, by the names a case prints. */
class FormatsTest {
    private fun labels(badges: List<String>) = Formats.fromBadges(badges).map { it.label }

    @Test
    fun badgesSayThePictureThenTheSound() {
        assertEquals(
            listOf("Ultra HD", "Dolby Vision", "Dolby TrueHD Atmos", "7.1"),
            labels(listOf(Definition.ULTRA_HD.token, "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1")),
        )
        assertEquals(listOf("Full HD", "Dolby Digital Plus", "5.1"), labels(listOf(Definition.FULL_HD.token, "E-AC3", "5.1")))
        assertEquals(listOf("Full HD", "Dolby Digital", "5.1"), labels(listOf(Definition.FULL_HD.token, "AC3", "5.1")))
    }

    @Test
    fun badgesKeepTheBestRangeOnly() {
        assertEquals(listOf("Dolby Vision"), labels(listOf("Dolby Vision", "HDR")))
        assertEquals(listOf("Dolby Vision"), labels(listOf("Dolby Vision", "HDR10+")))
        assertEquals(listOf("Ultra HD", "HDR10"), labels(listOf(Definition.ULTRA_HD.token, "HDR10")))
    }

    @Test
    fun aNameReadAsTrueHdAndAtmosIsOneFormat() {
        assertEquals(listOf("Ultra HD", "Dolby TrueHD Atmos"), labels(listOf(Definition.ULTRA_HD.token, "Atmos", "TrueHD")))
        assertEquals(listOf("Dolby Atmos"), labels(listOf("Atmos")))
        assertEquals(listOf("Dolby TrueHD"), labels(listOf("TrueHD")))
    }

    @Test
    fun howAFileWasMadeIsNotAFormat() {
        assertEquals(listOf("Ultra HD", "DTS-HD Master Audio"), labels(listOf(Definition.ULTRA_HD.token, "DTS-HD MA", "Remux")))
    }

    @Test
    fun soundsGoByTheNamesOnTheCase() {
        assertEquals("Dolby TrueHD Atmos", Formats.soundName("TrueHD Atmos"))
        assertEquals("Dolby Digital Plus Atmos", Formats.soundName("E-AC3 Atmos"))
        assertEquals("Dolby Digital Plus", Formats.soundName(Codecs.EAC3))
        assertEquals("Dolby Digital", Formats.soundName(Codecs.AC3))
        assertEquals("Dolby TrueHD", Formats.soundName(Codecs.TRUEHD))
        assertEquals("DTS-HD Master Audio", Formats.soundName(Codecs.DTS_HD_MA))
        assertEquals("DTS-HD High Resolution", Formats.soundName(Codecs.DTS_HD_HRA))
        assertEquals("DTS:X", Formats.soundName("DTS:X"))
        // A name read "Atmos" alone, its track unsaid.
        assertEquals("Dolby Atmos", Formats.soundName("Atmos"))
        assertEquals("LPCM", Formats.soundName(Codecs.PCM))
        assertEquals("AAC", Formats.soundName(Codecs.AAC))
    }

    @Test
    fun aPageSaysThePictureWithItsCodec() {
        val dolbyVision = VideoInfo(Codecs.HEVC, 3840, 1608, 10, listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10))
        assertEquals(listOf("Ultra HD", "Dolby Vision", "HEVC"), Formats.picture(dolbyVision))
        val dual = dolbyVision.copy(hdr = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS, HdrFormat.HDR10))
        assertEquals(listOf("Ultra HD", "Dolby Vision", "HDR10+", "HEVC"), Formats.picture(dual))
        assertEquals(listOf("Full HD", "AVC"), Formats.picture(VideoInfo(Codecs.AVC, 1920, 1080)))
        assertEquals(listOf("Full HD"), Formats.picture(VideoInfo(Codecs.UNKNOWN, 1920, 1080)))
    }

    @Test
    fun aPageSaysTheBestTrackByItsFullName() {
        val bugonia = listOf(
            AudioTrack(Codecs.TRUEHD, 8, "en", atmos = true),
            AudioTrack(Codecs.AC3, 6, "fr-FR", default = true),
        )
        assertEquals(listOf("Dolby TrueHD Atmos", "7.1"), Formats.sound(bugonia))
        val noWayHome = listOf(AudioTrack(Codecs.DTS_HD_MA, 6, "fr-FR", default = true), AudioTrack(Codecs.TRUEHD, 8, "en", atmos = true))
        assertEquals(listOf("Dolby TrueHD Atmos", "7.1"), Formats.sound(noWayHome))
        assertEquals(listOf("Dolby Digital Plus", "5.1"), Formats.sound(listOf(AudioTrack(Codecs.EAC3, 2), AudioTrack(Codecs.EAC3, 6))))
        assertEquals(emptyList<String>(), Formats.sound(emptyList()))
    }

    @Test
    fun frameRatesAreTheOnesFilmsAreMadeAt() {
        assertEquals(23.976, Formats.frameRate(24000f / 1001f), 0.0)
        assertEquals(24.0, Formats.frameRate(24f), 0.0)
        assertEquals(25.0, Formats.frameRate(25.0001f), 0.0)
        assertEquals(29.97, Formats.frameRate(30000f / 1001f), 0.0)
        assertEquals(59.94, Formats.frameRate(60000f / 1001f), 0.0)
        // A rate no standard is near is said to the thousandth.
        assertEquals(12.5, Formats.frameRate(12.5f), 0.0)
    }

    @Test
    fun rangesSayDolbyVisionWithoutItsBase() {
        fun ranges(vararg hdr: HdrFormat) = Formats.ranges(VideoInfo(Codecs.HEVC, hdr = hdr.toList()))
        assertEquals(listOf("Dolby Vision"), ranges(HdrFormat.DOLBY_VISION, HdrFormat.HDR10))
        assertEquals(listOf("Dolby Vision"), ranges(HdrFormat.DOLBY_VISION, HdrFormat.HLG))
        assertEquals(listOf("Dolby Vision", "HDR10+"), ranges(HdrFormat.DOLBY_VISION, HdrFormat.HDR10_PLUS, HdrFormat.HDR10))
        assertEquals(listOf("HDR10+", "HDR10"), ranges(HdrFormat.HDR10_PLUS, HdrFormat.HDR10))
        assertEquals(listOf("HLG"), ranges(HdrFormat.HLG))
        assertEquals(emptyList<String>(), ranges())
    }

    /** A file's tags and a server give bits a second; a track keeps the nearest kilobit, half up. */
    @Test
    fun aBitrateKeepsTheNearestKilobit() {
        assertEquals(0, bpsToKbps(0))
        assertEquals(0, bpsToKbps(499))
        assertEquals(1, bpsToKbps(500))
        assertEquals(640, bpsToKbps(640_499))
        assertEquals(641, bpsToKbps(640_500))
        assertEquals(48_000, bpsToKbps(47_999_600))
    }
}
