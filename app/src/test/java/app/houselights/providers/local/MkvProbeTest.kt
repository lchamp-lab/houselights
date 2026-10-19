package app.houselights.providers.local

import app.houselights.core.Definition
import app.houselights.core.HdrFormat
import app.houselights.providers.local.Matroska.element
import app.houselights.providers.local.Matroska.file
import app.houselights.providers.local.Matroska.str
import app.houselights.providers.local.Matroska.uint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What [MkvProbe] reads of a Matroska file's headers, tracks, first frames and tags. */
class MkvProbeTest {
    @Test
    fun readsTracks() {
        val result = MkvProbe.probe(Matroska.sample())
        assertTrue(result is MkvProbe.Result.Found)
        val info = (result as MkvProbe.Result.Found).info
        val v = info.video!!
        assertEquals("HEVC", v.codec)
        assertEquals(3840, v.width)
        assertEquals(Definition.ULTRA_HD, v.definition)
        assertEquals(10, v.bitDepth)
        assertEquals(listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10), v.hdr)
        assertEquals(8, v.dolbyVisionProfile)
        assertEquals(23.976, app.houselights.core.Formats.frameRate(v.frameRate!!), 0.0)
        assertEquals(7_094_000L, info.durationMs)

        assertEquals(2, info.audio.size)
        assertEquals("TrueHD Atmos", info.audio[0].format)
        assertEquals("7.1", info.audio[0].layout)
        assertEquals("fre", info.audio[1].language)
        assertEquals(false, info.audio[1].default)

        assertTrue(info.subtitles.single().forced)
        assertEquals(Matroska.SAMPLE_BADGES, info.badges())
    }

    @Test
    fun aFrameNoReadingExpectedLeavesTheHeadersSaid() {
        // A 300-byte NAL unit, then one whose length (2^31 - 256) overflows an Int once added to its offset.
        val frame = byteArrayOf(0, 0, 1, 0x2C) + ByteArray(300) { 2 } +
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0x00, 2, 1) + ByteArray(8)
        val video = element(
            0xAE,
            uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MPEGH/ISO/HEVC"), str(0x536E, "HDR10+"),
            element(0xE0, uint(0xB0, 3840), uint(0xBA, 2160), element(0x55B0, uint(0x55BA, 16), uint(0x55B2, 10))),
        )
        val audio = element(0xAE, uint(0xD7, 2), uint(0x83, 2), str(0x86, "A_AC3"), element(0xE1, uint(0x9F, 6)))
        // Track 1, timestamp 0, a keyframe without lacing.
        val block = element(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + frame)
        val result = MkvProbe.probe(file(element(0x1654AE6B, video, audio), element(0x1F43B675, uint(0xE7, 0), block)))
        assertTrue(result is MkvProbe.Result.Found)
        val info = (result as MkvProbe.Result.Found).info
        // With its frames read, HDR10+ comes only from their metadata, not from the track's name.
        assertEquals(listOf(HdrFormat.HDR10), info.video!!.hdr)
        assertEquals(3840, info.video!!.width)
        assertEquals(10, info.video!!.bitDepth)
        assertEquals(6, info.audio.single().channels)
    }

    /** A file holding only [tracks]: its video track's codec and its audio track's. */
    private fun codecs(vararg tracks: ByteArray): Pair<String?, String?> {
        val info = (MkvProbe.probe(file(element(0x1654AE6B, *tracks))) as MkvProbe.Result.Found).info
        return info.video?.codec to info.audio.firstOrNull()?.codec
    }

    /** A BITMAPINFOHEADER, as an AVI holds it, for a picture compressed as [fourcc] says. */
    private fun bitmapInfo(fourcc: String): ByteArray = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(40).putInt(720).putInt(304).putShort(1).putShort(24).put(fourcc.toByteArray(Charsets.US_ASCII)).array()

    /** A WAVEFORMATEX, as an AVI holds it, for a sound of format [tag]; 6 channels at 48 kHz. */
    private fun waveFormat(tag: Int): ByteArray = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(tag.toShort()).putShort(6).putInt(48_000).putInt(56_000).putShort(1).putShort(0).putShort(0).array()

    private fun vfw(fourcc: String) =
        element(0xAE, uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MS/VFW/FOURCC"), element(0x63A2, bitmapInfo(fourcc)))

    private fun acm(tag: Int) =
        element(0xAE, uint(0xD7, 2), uint(0x83, 2), str(0x86, "A_MS/ACM"), element(0x63A2, waveFormat(tag)), element(0xE1, uint(0x9F, 6)))

    /** An AVI remuxed as is by mkvmerge: tracks are named by their FOURCC and format tag, not codec ids. */
    @Test
    fun tracksFromAnAviAreNamedByTheirFourccAndFormatTag() {
        assertEquals("MPEG-4" to "MP3", codecs(vfw("XVID"), acm(0x0055)))
        assertEquals("MPEG-4" to "AC3", codecs(vfw("DX50"), acm(0x2000)))
        assertEquals("MPEG-4" to "DTS", codecs(vfw("DIVX"), acm(0x2001)))
        assertEquals("VC-1" to "PCM", codecs(vfw("WVC1"), acm(0x0001)))
        assertEquals("VC-1" to "AAC", codecs(vfw("WMV3"), acm(0x00FF)))
        assertEquals("AVC" to "?", codecs(vfw("H264"), acm(0x0161)))
        assertEquals("DIV3", codecs(vfw("DIV3")).first)
        // WAVE_FORMAT_EXTENSIBLE: 24 bits, 5.1, then the format its SubFormat starts with.
        val extensible = waveFormat(0xFFFE) + byteArrayOf(24, 0, 0x3F, 0, 0, 0, 0x01, 0x00)
        assertEquals("PCM", codecs(element(0xAE, uint(0xD7, 2), uint(0x83, 2), str(0x86, "A_MS/ACM"), element(0x63A2, extensible))).second)
    }

    @Test
    fun avcAndMpeg4Part2AreTwoFormats() {
        val asp = element(0xAE, uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MPEG4/ISO/ASP"))
        assertEquals("MPEG-4", codecs(asp).first)
        val avc = element(0xAE, uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MPEG4/ISO/AVC"))
        assertEquals("AVC", codecs(avc).first)
        // No header to read: no name, rather than a wrong one.
        assertEquals("?", codecs(element(0xAE, uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MS/VFW/FOURCC"))).first)
    }

    @Test
    fun rejectsOtherFormats() {
        assertEquals(MkvProbe.Result.NotMatroska, MkvProbe.probe("not a video".toByteArray()))
    }

    /** Most files stream their segment, of unknown size: its tracks are read up to the end of what was read. */
    @Test
    fun aSegmentOfUnknownSizeIsRead() {
        val header = element(0x1A45DFA3, str(0x4282, "matroska"))
        val track = element(0xAE, uint(0x83, 1), str(0x86, "V_MPEG4/ISO/AVC"))
        // The Segment's ID, then a size with all its value bits set.
        val segmentOfUnknownSize = byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01) + ByteArray(7) { 0xFF.toByte() }
        val found = MkvProbe.probe(header + segmentOfUnknownSize + element(0x1654AE6B, track)) as MkvProbe.Result.Found
        assertEquals("AVC", found.info.video?.codec)
    }

    @Test
    fun bitratesAreTheOnesTheTagsMeasured() {
        val tag = element(
            0x7373,
            element(0x63C0, uint(0x63C5, 0x1234)),
            element(0x67C8, str(0x45A3, "BPS"), str(0x4487, "768000")),
            element(0x67C8, str(0x45A3, "DURATION"), str(0x4487, "01:53:11.168000000")),
        )
        assertEquals(mapOf(0x1234L to 768_000L), MkvProbe.bitrates(element(0x1254C367, tag)))
        assertEquals(emptyMap<Long, Long>(), MkvProbe.bitrates("not tags".toByteArray()))
    }

    /** The first megabyte of real files kept out of the repository (build/probe/frames-*.mkv), when present. */
    @Test
    fun realFramesSayTheirFormats() {
        val dir = File("../build/probe").takeIf { it.isDirectory } ?: File("build/probe")
        fun read(name: String) = File(dir, "frames-$name.mkv").takeIf { it.isFile }
            ?.let { (MkvProbe.probe(it.readBytes()) as MkvProbe.Result.Found).info }
        val expected = mapOf(
            // Dolby Digital, then Dolby Digital Plus Atmos named "VO DDP 5.1 Atmos".
            "furious" to Triple(listOf(false, true), true, null),
            // Dolby Digital Plus Atmos named only "VO : English".
            "bnd" to Triple(listOf(false, true), true, null),
            "colony" to Triple(listOf(false, false), false, null),
            // A Blu-ray's Dolby Digital 7.1 (a core and its extension), then TrueHD Atmos.
            "disclosure" to Triple(listOf(false, true), true, null),
            "evildead" to Triple(listOf(false, true, false), false, null),
            // DTS-HD Master Audio by its lossless layer, not DTS:X.
            "nwh" to Triple(listOf(true, false), false, "DTS-HD MA"),
        )
        val found = expected.filterKeys { read(it) != null }
        assumeTrue(found.isNotEmpty())
        found.forEach { (name, expect) ->
            val info = read(name)!!
            val (atmos, hdr10Plus, dts) = expect
            assertEquals(name, atmos, info.audio.map { it.atmos })
            assertEquals(name, hdr10Plus, HdrFormat.HDR10_PLUS in info.video!!.hdr)
            dts?.let { codec -> assertEquals(name, codec, info.audio.single { it.codec.startsWith("DTS") }.codec) }
            assertTrue(name, info.audio.none { it.dtsX })
        }
    }

    /** Real headers kept out of the repository (build/probe), checked when present. */
    @Test
    fun realHeaders() {
        val dir = File("../build/probe").takeIf { it.isDirectory } ?: File("build/probe")
        val files = dir.listFiles { f -> f.extension == "mkv" }.orEmpty()
        assumeTrue(files.isNotEmpty())
        files.forEach { f ->
            val result = MkvProbe.probe(f.readBytes())
            println("${f.name}: $result")
            assertTrue(result is MkvProbe.Result.Found)
        }
    }
}
