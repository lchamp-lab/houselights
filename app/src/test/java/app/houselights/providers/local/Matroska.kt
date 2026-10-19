package app.houselights.providers.local

import java.nio.ByteBuffer

/** Matroska files for probe and scan tests: an EBML writer, a sample file and its badges, and how far the probe reads. */
object Matroska {
    /** What the probe reads of a file first. */
    const val FIRST_READ = 256 shl 10

    /** Where it reads on to when the first frames are further in. */
    const val MEGABYTE = 1 shl 20

    /** How far a file is read for its tracks' first frames. */
    const val FRAMES_AT_MOST = 4 shl 20

    /** What the probe makes of [sample]. */
    val SAMPLE_BADGES = listOf("4K", "Dolby Vision", "HDR10", "TrueHD Atmos", "7.1")

    /** An element: its ID without leading zero bytes, its size in four bytes, then [children] one after another. */
    fun element(id: Long, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { all, child -> all + child }
        val n = body.size
        val size = byteArrayOf((0x10 or (n shr 24)).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        return bigEndian(id, from = 24) + size + body
    }

    /** An unsigned integer element, in as few bytes as hold [value]. */
    fun uint(id: Long, value: Long): ByteArray = element(id, bigEndian(value, from = 56).takeIf { it.isNotEmpty() } ?: byteArrayOf(0))

    /** A string element, in UTF-8. */
    fun str(id: Long, value: String): ByteArray = element(id, value.toByteArray())

    /** A whole file: the EBML header, then a segment holding [children]. */
    fun file(vararg children: ByteArray): ByteArray = element(0x1A45DFA3, str(0x4282, "matroska")) + element(0x18538067, *children)

    /**
     * A 4K HEVC file at 23.976 fps, 10-bit Dolby Vision profile 8 with HDR10, lasting 7,094 s: English TrueHD Atmos
     * 7.1, then French AC3 5.1 (not default), and forced French subtitles.
     */
    fun sample(): ByteArray {
        val video = element(
            0xAE,
            uint(0x83, 1), str(0x86, "V_MPEGH/ISO/HEVC"),
            // DefaultDuration: 41,708,333 ns per frame, 24000/1001 frames per second.
            element(0x23E383, byteArrayOf(0x02, 0x7C, 0x6B, 0x2D)),
            element(0xE0, uint(0xB0, 3840), uint(0xBA, 2160), element(0x55B0, uint(0x55BA, 16), uint(0x55B2, 10))),
            element(0x41E4, element(0x41E7, "dvcC".toByteArray()), element(0x41ED, byteArrayOf(1, 0, (8 shl 1).toByte(), 0x35))),
        )
        val atmos = element(
            0xAE,
            uint(0x83, 2), str(0x86, "A_TRUEHD"), str(0x22B59C, "eng"), str(0x536E, "Dolby Atmos 7.1 Mix"), element(0xE1, uint(0x9F, 8)),
        )
        val french = element(0xAE, uint(0x83, 2), str(0x86, "A_AC3"), uint(0x88, 0), str(0x22B59C, "fre"), element(0xE1, uint(0x9F, 6)))
        val forced = element(0xAE, uint(0x83, 17), str(0x86, "S_TEXT/UTF8"), str(0x22B59C, "fre"), str(0x536E, "Forced"))
        // TimecodeScale 1 ms, Duration 7,094,000 as a double.
        val duration = ByteBuffer.allocate(8).putDouble(7_094_000.0).array()
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000), element(0x4489, duration))
        return file(info, element(0x1654AE6B, video, atmos, french, forced))
    }

    /** [value]'s bytes from bit [from] down, without leading zero bytes. */
    private fun bigEndian(value: Long, from: Int): ByteArray =
        (from downTo 0 step 8).map { (value shr it).toByte() }.dropWhile { it == 0.toByte() }.toByteArray()
}
