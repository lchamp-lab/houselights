package app.houselights.providers.local

import app.houselights.core.HdrFormat

/**
 * What a track's first frames say of their format where a Matroska header does not: Atmos in a
 * TrueHD or Dolby Digital Plus stream, the layers of a DTS one, HDR10+ and HDR10 in an HEVC one.
 * Read from the frames themselves, never from a track's name. Each reading is of a byte range
 * ([start] to [end]) of what was read of the file, and gives null when the range does not say.
 */
@Suppress("MagicNumber") // Byte masks and field widths, as the formats' specifications write them.
internal object Bitstreams {
    /** The layers a DTS frame carries, the richest first. */
    enum class Dts { X, MASTER_AUDIO, HIGH_RESOLUTION, EXPRESS, CORE }

    /**
     * A TrueHD access unit with a major sync: Atmos when it carries a fourth substream, the
     * 16-channel presentation objects ride on (a TrueHD stream without them has three at most).
     * Null when the unit has no major sync.
     */
    fun trueHdAtmos(b: ByteArray, start: Int, end: Int): Boolean? {
        if (end - start < TRUEHD_HEADER || int32(b, start + TRUEHD_SYNC_AT) != TRUEHD_SYNC) return null
        if (int16(b, start + TRUEHD_SIGNATURE_AT) != TRUEHD_SIGNATURE) return null
        val substreams = (b[start + TRUEHD_SUBSTREAMS_AT].toInt() and 0xF0) shr 4
        return substreams >= TRUEHD_ATMOS_SUBSTREAMS
    }

    /**
     * Dolby Digital and Dolby Digital Plus syncframes, one after another (a Blu-ray's 7.1 is a
     * Dolby Digital core and a Dolby Digital Plus extension): Atmos when a Dolby Digital Plus frame
     * flags its JOC extension (flag_ec3_extension_type_a). Null when no frame could be read.
     */
    fun dolbyAtmos(b: ByteArray, start: Int, end: Int): Boolean? = runCatching {
        var at = start
        var read = false
        while (at + AC3_HEADER <= end && int16(b, at) == AC3_SYNC) {
            val bsid = (b[at + BSID_AT].toInt() and 0xFF) shr 3
            val size = if (bsid <= AC3_MAX_BSID) ac3FrameSize(b, at) else ((int16(b, at + 2) and 0x07FF) + 1) * 2
            if (size <= 0) break
            if (bsid > AC3_MAX_BSID) {
                read = true
                if (eac3Joc(b, at, minOf(at + size, end))) return@runCatching true
            }
            at += size
        }
        if (read) false else null
    }.getOrNull()

    /** What a DTS frame carries, by the sync words of its layers; null when it is not DTS. */
    fun dts(b: ByteArray, start: Int, end: Int): Dts? {
        val found = DTS_SYNCS.filterValues { contains(b, start, end, it) }.keys
        return when {
            DTS_XLL_X in found || DTS_XLL_X_IMAX in found -> Dts.X
            DTS_XLL in found -> Dts.MASTER_AUDIO
            DTS_XBR in found || (DTS_X96 in found && DTS_SUBSTREAM in found) -> Dts.HIGH_RESOLUTION
            DTS_LBR in found -> Dts.EXPRESS
            DTS_CORE in found || DTS_SUBSTREAM in found -> Dts.CORE
            else -> null
        }
    }

    /**
     * The ranges an HEVC stream signals in the SEI messages of its first access units ([frames]:
     * NAL units each after its length in [lengthSize] bytes): HDR10+ by its dynamic metadata (SMPTE
     * 2094-40), HDR10 by its mastering display. Empty for SDR. A length that runs past its frame
     * ends the frame's reading: up to 2^32 - 1 in four bytes, it is read and compared as a Long.
     */
    fun hevcRanges(b: ByteArray, frames: List<IntRange>, lengthSize: Int): Set<HdrFormat> = buildSet {
        for (frame in frames) {
            var at = frame.first
            while (at + lengthSize <= frame.last + 1) {
                var size = 0L
                repeat(lengthSize) { size = (size shl 8) or (b[at + it].toLong() and 0xFF) }
                val nal = at + lengthSize
                if (size < 2 || size > frame.last + 1 - nal) break
                at = nal + size.toInt()
                val type = (b[nal].toInt() shr 1) and 0x3F
                if (type == NAL_SEI_PREFIX || type == NAL_SEI_SUFFIX) {
                    addAll(runCatching { seiRanges(unescape(b, nal + 2, at)) }.getOrDefault(emptySet()))
                }
            }
        }
    }

    /**
     * The frames of a Block's data ([start] to [end], after its header) as its lacing packs them:
     * none, Xiph, fixed-size or EBML. Only those whole in what was read. Sizes are added up as
     * Longs: an EBML lace can say up to 2^56, which no frame of what was read can be.
     */
    fun laced(b: ByteArray, start: Int, end: Int, lacing: Int): List<IntRange> = runCatching {
        if (lacing == LACING_NONE) return@runCatching listOf(start until end)
        val count = (b[start].toInt() and 0xFF) + 1
        var at = start + 1
        val sizes = mutableListOf<Long>()
        when (lacing) {
            LACING_XIPH -> repeat(count - 1) {
                var size = 0L
                do {
                    val v = b[at++].toInt() and 0xFF
                    size += v
                } while (v == XIPH_MORE)
                sizes += size
            }
            LACING_EBML -> {
                var (size, length) = vint(b, at)
                at += length
                sizes += size
                repeat(count - 2) {
                    val (raw, n) = vint(b, at)
                    at += n
                    size += raw - ((1L shl (7 * n - 1)) - 1)
                    sizes += size
                }
            }
            else -> repeat(count - 1) { sizes += ((end - at) / count).toLong() }
        }
        sizes += end - at - sizes.sum()
        var next = at.toLong()
        sizes.mapNotNull { size ->
            val first = next
            next += size
            if (size >= 0 && first >= start && next <= end) first.toInt() until next.toInt() else null
        }
    }.getOrDefault(emptyList())

    /** An unsigned EBML variable-length integer at [at]: its value and its length in bytes. */
    private fun vint(b: ByteArray, at: Int): Pair<Long, Int> {
        val first = b[at].toInt() and 0xFF
        val length = Integer.numberOfLeadingZeros(first) - 23
        require(length in 1..8)
        var value = (first and (0xFF shr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (b[at + i].toLong() and 0xFF)
        return value to length
    }

    /** An AC-3 frame's size in bytes, from its sample rate and size codes. */
    private fun ac3FrameSize(b: ByteArray, at: Int): Int {
        val code = b[at + 4].toInt() and 0xFF
        val rate = code shr 6
        val size = code and 0x3F
        if (rate > 2 || size >= AC3_KBPS.size * 2) return 0
        val kbps = AC3_KBPS[size / 2]
        val words = when (rate) {
            0 -> kbps * 2 // 48 kHz
            1 -> kbps * 320 / 147 + (size and 1) // 44.1 kHz
            else -> kbps * 3 // 32 kHz
        }
        return words * 2
    }

    /** Reads a Dolby Digital Plus frame's bit stream information as far as its extension flag. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth") // The bitstream syntax, field by field.
    private fun eac3Joc(b: ByteArray, at: Int, end: Int): Boolean {
        val r = Bits(b, at + 2, end)
        val streamType = r.get(2)
        r.skip(3 + 11) // substreamid, frmsiz
        val fscod = r.get(2)
        val numblkscod = if (fscod == 3) r.get(2).let { 3 } else r.get(2)
        val blocks = intArrayOf(1, 2, 3, 6)[numblkscod]
        val acmod = r.get(3)
        val lfeon = r.get(1)
        r.skip(5 + 5) // bsid, dialnorm
        if (r.flag()) r.skip(8) // compr
        if (acmod == 0) {
            r.skip(5)
            if (r.flag()) r.skip(8)
        }
        if (streamType == 1 && r.flag()) r.skip(16) // chanmap
        if (r.flag()) { // mixmdate
            if (acmod > 2) r.skip(2)
            if (acmod and 1 == 1 && acmod > 2) r.skip(6)
            if (acmod and 4 == 4) r.skip(6)
            if (lfeon == 1 && r.flag()) r.skip(5)
            if (streamType == 0) {
                if (r.flag()) r.skip(6)
                if (acmod == 0 && r.flag()) r.skip(6)
                if (r.flag()) r.skip(6)
                when (r.get(2)) { // mixdef
                    1 -> r.skip(5)
                    2 -> r.skip(12)
                    3 -> r.skip(8 * (r.get(5) + 2))
                }
                if (acmod < 2) {
                    if (r.flag()) r.skip(14)
                    if (acmod == 0 && r.flag()) r.skip(14)
                }
                if (r.flag()) { // frmmixcfginfoe
                    if (numblkscod == 0) r.skip(5) else repeat(blocks) { if (r.flag()) r.skip(5) }
                }
            }
        }
        if (r.flag()) { // infomdate
            r.skip(5)
            if (acmod == 2) r.skip(4)
            if (acmod >= 6) r.skip(2)
            if (r.flag()) r.skip(8)
            if (acmod == 0 && r.flag()) r.skip(8)
            if (fscod < 3) r.skip(1)
        }
        if (streamType == 0 && numblkscod != 3) r.skip(1)
        if (streamType == 2) {
            val blkid = if (numblkscod == 3) 1 else r.get(1)
            if (blkid == 1) r.skip(6)
        }
        if (!r.flag()) return false // addbsie
        r.skip(6) // addbsil
        return r.get(8) and 1 == 1
    }

    /**
     * The ranges SEI messages signal, from a NAL unit's payload, emulation bytes removed: message
     * after message, up to its last byte, which holds the stop bit.
     */
    private fun seiRanges(p: ByteArray): Set<HdrFormat> = buildSet {
        var at = 0
        while (at < p.size - 1) {
            var type = 0
            while (p[at] == 0xFF.toByte()) { type += 0xFF; at++ }
            type += p[at++].toInt() and 0xFF
            var size = 0
            while (p[at] == 0xFF.toByte()) { size += 0xFF; at++ }
            size += p[at++].toInt() and 0xFF
            val whole = size <= p.size - at
            when {
                whole && type == SEI_T35 && size >= HDR10_PLUS_HEADER.size &&
                    HDR10_PLUS_HEADER.indices.all { p[at + it] == HDR10_PLUS_HEADER[it] } -> add(HdrFormat.HDR10_PLUS)
                whole && type == SEI_MASTERING_DISPLAY -> add(HdrFormat.HDR10)
            }
            at += size
        }
    }

    /** A NAL unit's bytes without their emulation-prevention bytes (the 3 in 00 00 03). */
    private fun unescape(b: ByteArray, start: Int, end: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(end - start)
        var zeros = 0
        for (i in start until end) {
            val v = b[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun contains(b: ByteArray, start: Int, end: Int, sync: Int): Boolean {
        for (i in start..end - 4) if (int32(b, i) == sync) return true
        return false
    }

    private fun int16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun int32(b: ByteArray, at: Int) = (int16(b, at) shl 16) or int16(b, at + 2)

    /** A bit cursor over [start] to [end]; reading past the end throws. */
    private class Bits(val b: ByteArray, start: Int, val end: Int) {
        private var bit = start * 8L

        fun get(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = (bit shr 3).toInt()
                if (byte >= end) throw IndexOutOfBoundsException("Past the frame")
                v = (v shl 1) or ((b[byte].toInt() shr (7 - (bit and 7).toInt())) and 1)
                bit++
            }
            return v
        }

        fun flag() = get(1) == 1

        fun skip(n: Int) {
            bit += n
        }
    }

    const val LACING_NONE = 0
    private const val LACING_XIPH = 1
    private const val LACING_EBML = 3
    private const val XIPH_MORE = 0xFF

    private const val TRUEHD_SYNC = 0xF8726FBA.toInt()
    private const val TRUEHD_SIGNATURE = 0xB752
    private const val TRUEHD_SYNC_AT = 4
    private const val TRUEHD_SIGNATURE_AT = 12
    private const val TRUEHD_SUBSTREAMS_AT = 20
    private const val TRUEHD_HEADER = 22
    private const val TRUEHD_ATMOS_SUBSTREAMS = 4

    private const val AC3_SYNC = 0x0B77
    private const val AC3_HEADER = 6
    private const val BSID_AT = 5
    private const val AC3_MAX_BSID = 10

    /** AC-3 bit rates by frame size code pair, in kb/s. */
    private val AC3_KBPS = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)

    private const val DTS_CORE = "core"
    private const val DTS_SUBSTREAM = "substream"
    private const val DTS_XLL = "XLL"
    private const val DTS_XLL_X = "XLL X"
    private const val DTS_XLL_X_IMAX = "XLL X IMAX"
    private const val DTS_XBR = "XBR"
    private const val DTS_X96 = "X96"
    private const val DTS_LBR = "LBR"

    /** The sync words of a DTS frame's layers. */
    private val DTS_SYNCS = mapOf(
        DTS_CORE to 0x7FFE8001,
        DTS_SUBSTREAM to 0x64582025,
        DTS_XLL to 0x41A29547,
        DTS_XLL_X to 0x02000850,
        DTS_XLL_X_IMAX to 0xF14000D0.toInt(),
        DTS_XBR to 0x655E315E,
        DTS_X96 to 0x1D95F262,
        DTS_LBR to 0x0A801921,
    )

    private const val NAL_SEI_PREFIX = 39
    private const val NAL_SEI_SUFFIX = 40
    private const val SEI_T35 = 4
    private const val SEI_MASTERING_DISPLAY = 137

    /** ITU-T T.35 for the United States, Samsung, HDR10+ (SMPTE 2094-40) application 4. */
    private val HDR10_PLUS_HEADER = byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04)
}
