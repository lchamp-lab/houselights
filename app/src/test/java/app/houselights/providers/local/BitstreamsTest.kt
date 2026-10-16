package app.houselights.providers.local

import app.houselights.core.HdrFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** What a track's first frames say, read from frames built bit by bit as their formats lay them out. */
class BitstreamsTest {
    /** Writes values MSB first, as bitstreams do. */
    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun put(value: Int, bits: Int) = apply {
            for (i in bits - 1 downTo 0) {
                current = (current shl 1) or ((value shr i) and 1)
                if (++count == 8) {
                    out.write(current)
                    current = 0
                    count = 0
                }
            }
        }

        fun bytes(size: Int): ByteArray {
            if (count > 0) put(0, 8 - count)
            return out.toByteArray().copyOf(size)
        }
    }

    // --- TrueHD ------------------------------------------------------------------------------

    private fun trueHd(substreams: Int) = BitWriter()
        .put(0, 32) // check nibble, access unit length, input timing
        .put(0xF8726FBA.toInt(), 32).put(0, 32) // format sync, format info
        .put(0xB752, 16).put(0, 16).put(0, 16) // signature, flags, reserved
        .put(0, 16) // variable rate, peak data rate
        .put(substreams shl 4, 8).put(0x80, 8)
        .bytes(32)

    @Test
    fun trueHdCarriesAtmosInAFourthSubstream() {
        assertEquals(true, trueHd(4).let { Bitstreams.trueHdAtmos(it, 0, it.size) })
        assertEquals(false, trueHd(3).let { Bitstreams.trueHdAtmos(it, 0, it.size) })
        // A unit without a major sync says nothing.
        assertNull(ByteArray(32).let { Bitstreams.trueHdAtmos(it, 0, it.size) })
    }

    // --- Dolby Digital (Plus) ----------------------------------------------------------------

    /** A Dolby Digital Plus syncframe of [size] bytes, 5.1, its extension flag (JOC) as given. */
    private fun eac3(size: Int, joc: Boolean?, streamType: Int = 0) = BitWriter()
        .put(0x0B77, 16)
        .put(streamType, 2).put(0, 3).put(size / 2 - 1, 11) // strmtyp, substreamid, frmsiz
        .put(0, 2).put(3, 2) // 48 kHz, six blocks
        .put(7, 3).put(1, 1).put(16, 5).put(27, 5) // 3/2, LFE, bsid 16, dialnorm
        .put(0, 1) // compre
        .apply { if (streamType == 1) put(0, 1) } // chanmape
        .put(0, 1).put(0, 1) // mixmdate, infomdate
        .apply { if (joc == null) put(0, 1) else put(1, 1).put(0, 6).put(if (joc) 1 else 0, 8) }
        .bytes(size)

    /** A Dolby Digital syncframe at 48 kHz, 448 kb/s: 1792 bytes. */
    private fun ac3() = BitWriter()
        .put(0x0B77, 16).put(0, 16) // syncword, crc1
        .put(0, 2).put(30, 6) // 48 kHz, 448 kb/s
        .put(6 shl 3, 8) // bsid 6
        .bytes(1792)

    @Test
    fun dolbyDigitalPlusSaysAtmosByItsExtension() {
        assertEquals(true, eac3(64, joc = true).let { Bitstreams.dolbyAtmos(it, 0, it.size) })
        assertEquals(false, eac3(64, joc = false).let { Bitstreams.dolbyAtmos(it, 0, it.size) })
        assertEquals(false, eac3(64, joc = null).let { Bitstreams.dolbyAtmos(it, 0, it.size) })
    }

    @Test
    fun aBluRayCoreIsFollowedToItsExtension() {
        // Dolby Digital 5.1, then the Dolby Digital Plus frame that makes it 7.1.
        val frame = ac3() + eac3(64, joc = true, streamType = 1)
        assertEquals(true, Bitstreams.dolbyAtmos(frame, 0, frame.size))
        // Dolby Digital alone says nothing of Atmos.
        assertNull(ac3().let { Bitstreams.dolbyAtmos(it, 0, it.size) })
    }

    // --- DTS ---------------------------------------------------------------------------------

    private fun dts(vararg syncs: Int): ByteArray {
        val out = ByteArrayOutputStream()
        syncs.forEach { sync ->
            out.write(byteArrayOf((sync ushr 24).toByte(), (sync ushr 16).toByte(), (sync ushr 8).toByte(), sync.toByte()))
            out.write(ByteArray(16))
        }
        return out.toByteArray()
    }

    @Test
    fun dtsSaysItsLayersByTheirSyncWords() {
        val core = 0x7FFE8001
        val substream = 0x64582025
        fun read(bytes: ByteArray) = Bitstreams.dts(bytes, 0, bytes.size)
        assertEquals(Bitstreams.Dts.CORE, read(dts(core)))
        assertEquals(Bitstreams.Dts.MASTER_AUDIO, read(dts(core, substream, 0x41A29547)))
        assertEquals(Bitstreams.Dts.X, read(dts(core, substream, 0x41A29547, 0x02000850)))
        assertEquals(Bitstreams.Dts.HIGH_RESOLUTION, read(dts(core, substream, 0x655E315E)))
        assertEquals(Bitstreams.Dts.EXPRESS, read(dts(substream, 0x0A801921)))
        assertNull(read(ByteArray(32)))
    }

    // --- HEVC --------------------------------------------------------------------------------

    /** One access unit: an SEI NAL unit holding [messages], each after its length in four bytes. */
    private fun hevcSei(vararg messages: ByteArray): ByteArray {
        val nal = byteArrayOf((39 shl 1).toByte(), 1) + messages.fold(ByteArray(0)) { acc, m -> acc + m } + byteArrayOf(0x80.toByte())
        return byteArrayOf(0, 0, (nal.size shr 8).toByte(), nal.size.toByte()) + nal
    }

    private val hdr10Plus = byteArrayOf(4, 8, 0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x40)
    private val masteringDisplay = byteArrayOf(0x89.toByte(), 24) + ByteArray(24) { 1 }

    @Test
    fun hevcSaysItsRangesInItsSei() {
        fun read(frame: ByteArray) = Bitstreams.hevcRanges(frame, listOf(frame.indices), lengthSize = 4)
        assertEquals(setOf(HdrFormat.HDR10_PLUS, HdrFormat.HDR10), read(hevcSei(masteringDisplay, hdr10Plus)))
        assertEquals(setOf(HdrFormat.HDR10), read(hevcSei(masteringDisplay)))
        assertEquals(emptySet<HdrFormat>(), read(hevcSei()))
    }

    @Test
    fun aLengthPastItsFrameEndsItsReading() {
        // After an HDR10 SEI, a NAL unit says 2^31 - 256 bytes: added to where it starts (past
        // byte 255), an Int would wrap below zero and read before the array.
        val frame = ByteArray(300) + hevcSei(masteringDisplay) +
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0x00, 2, 1) + ByteArray(16)
        assertEquals(setOf(HdrFormat.HDR10), Bitstreams.hevcRanges(frame, listOf(300 until frame.size), lengthSize = 4))
        // Four bytes say up to 2^32 - 1, which an Int would read as negative.
        val wide = hevcSei(masteringDisplay) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 2, 1)
        assertEquals(setOf(HdrFormat.HDR10), Bitstreams.hevcRanges(wide, listOf(wide.indices), lengthSize = 4))
    }

    // --- Lacing ------------------------------------------------------------------------------

    @Test
    fun lacedFramesAreFoundWhole() {
        val payload = ByteArray(30) { it.toByte() }
        // Xiph: three frames of 10, 5 and what is left.
        val xiph = byteArrayOf(2, 10, 5) + payload
        assertEquals(listOf(3..12, 13..17, 18..32), Bitstreams.laced(xiph, 0, xiph.size, 1))
        // Fixed: three frames alike.
        val fixed = byteArrayOf(2) + payload
        assertEquals(listOf(1..10, 11..20, 21..30), Bitstreams.laced(fixed, 0, fixed.size, 2))
        // EBML: 10, then 10 + 2, then what is left.
        val ebml = byteArrayOf(2, 0x8A.toByte(), (0xBF + 2).toByte()) + payload
        assertEquals(listOf(3..12, 13..24, 25..32), Bitstreams.laced(ebml, 0, ebml.size, 3))
        assertEquals(listOf(0..29), Bitstreams.laced(payload, 0, payload.size, Bitstreams.LACING_NONE))
    }

    @Test
    fun aLaceLargerThanAnIntIsNoFrame() {
        val payload = ByteArray(30)
        // EBML: two frames, the first of 2^32 + 10 bytes (eight bytes), which an Int would read as 10.
        val ebml = byteArrayOf(1, 0x01, 0, 0, 0x01, 0, 0, 0, 0x0A) + payload
        assertEquals(emptyList<IntRange>(), Bitstreams.laced(ebml, 0, ebml.size, 3))
    }
}
