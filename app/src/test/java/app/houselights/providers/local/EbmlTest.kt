package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** EBML's variable-length integers, as element sizes and lace sizes read them: one decoder for both. */
class EbmlTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun anIntegerIsItsValueBitsAndItsLength() {
        assertEquals(1L to 1, Ebml.vint(bytes(0x81), 0))
        assertEquals(0x1234L to 2, Ebml.vint(bytes(0x52, 0x34), 0))
        assertEquals(0x0200L to 4, Ebml.vint(bytes(0x99, 0x10, 0x00, 0x02, 0x00), 1))
        assertEquals((1L shl 56) - 2 to 8, Ebml.vint(bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE), 0))
    }

    /** A segment written before its size was known (as it streams): all value bits set, whatever the length. */
    @Test
    fun allValueBitsSetIsAnUnknownSize() {
        assertTrue(Ebml.isUnknown(0x7F, 1))
        assertTrue(Ebml.isUnknown((1L shl 56) - 1, 8))
        assertFalse(Ebml.isUnknown(0x7E, 1))
        assertFalse("one byte longer, the same value is known", Ebml.isUnknown(0x7F, 2))
    }

    /** An integer running past what bounds it (an element's end, a block's), or marking no length, is no integer. */
    @Test
    fun anIntegerPastItsEndOrWithoutLengthThrows() {
        assertThrows(IndexOutOfBoundsException::class.java) { Ebml.vint(bytes(0x40, 0x02, 0x81), 0, end = 1) }
        assertThrows(IndexOutOfBoundsException::class.java) { Ebml.vint(bytes(0x81), 1) }
        assertThrows(IllegalArgumentException::class.java) { Ebml.vint(bytes(0x00, 0x81), 0) }
    }

    /** Most files stream their segment, of unknown size: its tracks are read up to the end of what was read. */
    @Test
    fun aSegmentOfUnknownSizeIsRead() {
        fun el(id: ByteArray, body: ByteArray) = id + bytes(0x10, 0, body.size shr 8, body.size and 0xFF) + body
        val header = el(bytes(0x1A, 0x45, 0xDF, 0xA3), el(bytes(0x42, 0x82), "matroska".toByteArray()))
        val track = el(bytes(0xAE), el(bytes(0x83), bytes(1)) + el(bytes(0x86), "V_MPEG4/ISO/AVC".toByteArray()))
        val unknownSize = bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        val segment = bytes(0x18, 0x53, 0x80, 0x67) + unknownSize + el(bytes(0x16, 0x54, 0xAE, 0x6B), track)
        val found = MkvProbe.probe(header + segment) as MkvProbe.Result.Found
        assertEquals("AVC", found.info.video?.codec)
    }
}
