package app.houselights.providers.local

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

    private fun sampleFile(): ByteArray {
        val video = el(
            0xAE,
            uint(0x83, 1), str(0x86, "V_MPEGH/ISO/HEVC"),
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
        assertEquals("4K", v.resolution)
        assertEquals(10, v.bitDepth)
        assertEquals(listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10), v.hdr)
        assertEquals(8, v.dolbyVisionProfile)
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
    fun rejectsOtherFormats() {
        assertEquals(MkvProbe.Result.NotMatroska, MkvProbe.probe("not a video".toByteArray()))
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
