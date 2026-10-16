package app.houselights.providers.local

/** EBML's variable-length integers, as Matroska writes the sizes of its elements and of laced frames. */
internal object Ebml {
    private const val MAX_LENGTH = 8

    /** Bits each byte of an integer gives its value: the rest of the first byte marks its length. */
    private const val VALUE_BITS = 7

    private const val BYTE_MASK = 0xFF

    /**
     * The unsigned variable-length integer at [at], which must end before [end]: its value bits, and
     * its length in bytes, 1 to 8 as its first byte's leading zeros tell. Throws when the first byte
     * tells no such length, or the integer runs past [end].
     */
    fun vint(b: ByteArray, at: Int, end: Int = b.size): Pair<Long, Int> {
        val first = byteAt(b, at, end)
        val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS - 1)
        require(length in 1..MAX_LENGTH) { "Bad EBML integer length" }
        var value = (first and (BYTE_MASK shr length)).toLong()
        for (i in 1 until length) value = (value shl Byte.SIZE_BITS) or byteAt(b, at + i, end).toLong()
        return value to length
    }

    /** Whether a [length]-byte integer's [value] has all its bits set: an element's size the file leaves "unknown". */
    fun isUnknown(value: Long, length: Int): Boolean = value == (1L shl (VALUE_BITS * length)) - 1

    private fun byteAt(b: ByteArray, i: Int, end: Int): Int {
        if (i < 0 || i >= end) throw IndexOutOfBoundsException("EBML integer past its end, at $i of $end")
        return b[i].toInt() and BYTE_MASK
    }
}
