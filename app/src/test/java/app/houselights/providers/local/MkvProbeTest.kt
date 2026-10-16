package app.houselights.providers.local

import app.houselights.core.Definition
import app.houselights.core.HdrFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class MkvProbeTest {
    // --- Minimal EBML writer, enough to build a Matroska header ------------------------------

    private fun id(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var started = false
        for (shift in 24 downTo 0 step 8) {
            val b = ((value shr shift) and 0xFF).toInt()
            if (b != 0 || started) { out.write(b); started = true }
        }
        return out.toByteArray()
    }

    private fun size(n: Int): ByteArray = byteArrayOf((0x40 or ((n shr 8) and 0x3F)).toByte(), (n and 0xFF).toByte())

    private fun el(idValue: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return id(idValue) + size(body.size) + body
    }

    private fun uint(idValue: Long, v: Long): ByteArray = el(idValue, byteArrayOf(v.toByte()))
    private fun str(idValue: Long, s: String): ByteArray = el(idValue, s.toByteArray())
    private fun raw(idValue: Long, b: ByteArray): ByteArray = el(idValue, b)

    /** A 4K Dolby Vision file with TrueHD Atmos; scanner tests put it on their NAS. */
    internal fun sampleFile(): ByteArray {
        val video = el(
            0xAE,
            uint(0x83, 1), str(0x86, "V_MPEGH/ISO/HEVC"),
            // DefaultDuration: 41 708 333 ns a frame, 24000/1001 frames a second.
            raw(0x23E383, byteArrayOf(0x02, 0x7C, 0x6B, 0x2D)),
            el(0xE0, raw(0xB0, byteArrayOf(0x0F, 0x00)), raw(0xBA, byteArrayOf(0x08, 0x70)), el(0x55B0, uint(0x55BA, 16), uint(0x55B2, 10))),
            el(0x41E4, raw(0x41E7, byteArrayOf(0x64, 0x76, 0x63, 0x43)), raw(0x41ED, byteArrayOf(1, 0, (8 shl 1).toByte(), 0x35))),
        )
        val atmos = el(0xAE, uint(0x83, 2), str(0x86, "A_TRUEHD"), str(0x22B59C, "eng"), str(0x536E, "Dolby Atmos 7.1 Mix"), el(0xE1, uint(0x9F, 8)))
        val french = el(0xAE, uint(0x83, 2), str(0x86, "A_AC3"), uint(0x88, 0), str(0x22B59C, "fre"), el(0xE1, uint(0x9F, 6)))
        val forced = el(0xAE, uint(0x83, 17), str(0x86, "S_TEXT/UTF8"), str(0x22B59C, "fre"), str(0x536E, "Forced"))
        val info = el(0x1549A966, raw(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40)), raw(0x4489, java.nio.ByteBuffer.allocate(8).putDouble(7_094_000.0).array()))
        val segment = el(0x18538067, info, el(0x1654AE6B, video, atmos, french, forced))
        return el(0x1A45DFA3, str(0x4282, "matroska")) + segment
    }

    @Test
    fun readsTracks() {
        val result = MkvProbe.probe(sampleFile())
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
        assertEquals(listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1"), info.badges())
    }

    @Test
    fun aFrameNoReadingExpectedLeavesTheHeadersSaid() {
        // An HEVC frame: a 300-byte NAL unit, then one whose length (2^31 - 256) passes the end
        // of an Int once added to where it starts.
        val frame = byteArrayOf(0, 0, 1, 0x2C) + ByteArray(300) { 2 } +
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0x00, 2, 1) + ByteArray(8)
        val video = el(
            0xAE,
            uint(0xD7, 1), uint(0x83, 1), str(0x86, "V_MPEGH/ISO/HEVC"), str(0x536E, "HDR10+"),
            el(0xE0, raw(0xB0, byteArrayOf(0x0F, 0x00)), raw(0xBA, byteArrayOf(0x08, 0x70)), el(0x55B0, uint(0x55BA, 16), uint(0x55B2, 10))),
        )
        val audio = el(0xAE, uint(0xD7, 2), uint(0x83, 2), str(0x86, "A_AC3"), el(0xE1, uint(0x9F, 6)))
        // Track 1, timestamp 0, a keyframe without lacing.
        val block = raw(0xA3, byteArrayOf(0x81.toByte(), 0, 0, 0x80.toByte()) + frame)
        val segment = el(0x18538067, el(0x1654AE6B, video, audio), el(0x1F43B675, uint(0xE7, 0), block))
        val result = MkvProbe.probe(el(0x1A45DFA3, str(0x4282, "matroska")) + segment)
        assertTrue(result is MkvProbe.Result.Found)
        val info = (result as MkvProbe.Result.Found).info
        // Its frames read, HDR10+ is only what they say, not its name.
        assertEquals(listOf(HdrFormat.HDR10), info.video!!.hdr)
        assertEquals(3840, info.video!!.width)
        assertEquals(10, info.video!!.bitDepth)
        assertEquals(6, info.audio.single().channels)
    }

    @Test
    fun rejectsOtherFormats() {
        assertEquals(MkvProbe.Result.NotMatroska, MkvProbe.probe("not a video".toByteArray()))
    }

    @Test
    fun bitratesAreTheOnesTheTagsMeasured() {
        val tag = el(
            0x7373,
            el(0x63C0, raw(0x63C5, byteArrayOf(0x12, 0x34))),
            el(0x67C8, str(0x45A3, "BPS"), str(0x4487, "768000")),
            el(0x67C8, str(0x45A3, "DURATION"), str(0x4487, "01:53:11.168000000")),
        )
        assertEquals(mapOf(0x1234L to 768_000L), MkvProbe.bitrates(el(0x1254C367, tag)))
        assertEquals(emptyMap<Long, Long>(), MkvProbe.bitrates("not tags".toByteArray()))
    }

    /**
     * The first megabyte of real files, kept out of the repository (build/probe/frames-*.mkv) and
     * checked when present: what their frames say, whatever their tracks are named.
     */
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
