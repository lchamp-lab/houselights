package app.houselights.providers.local

/** EBML variable-length integers, which Matroska uses for element sizes and laced frame sizes. */
internal object Ebml {
    private const val MAX_LENGTH = 8

    /** Value bits per byte; the remaining bits of the first byte encode the length. */
    private const val VALUE_BITS = 7

    private const val BYTE_MASK = 0xFF

    /**
     * The unsigned variable-length integer at [at]: its value (length marker removed) and its length in
     * bytes, 1 to 8 from the first byte's leading zeros. Throws on an invalid length or if it runs past [end].
     */
    fun vint(b: ByteArray, at: Int, end: Int = b.size): Pair<Long, Int> {
        val first = byteAt(b, at, end)
        val length = Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS - 1)
        require(length in 1..MAX_LENGTH) { "Bad EBML integer length" }
        var value = (first and (BYTE_MASK shr length)).toLong()
        for (i in 1 until length) value = (value shl Byte.SIZE_BITS) or byteAt(b, at + i, end).toLong()
        return value to length
    }

    /** True when all value bits are set, which marks an element size as "unknown". */
    fun isUnknown(value: Long, length: Int): Boolean = value == (1L shl (VALUE_BITS * length)) - 1

    private fun byteAt(b: ByteArray, i: Int, end: Int): Int {
        if (i < 0 || i >= end) throw IndexOutOfBoundsException("EBML integer past its end, at $i of $end")
        return b[i].toInt() and BYTE_MASK
    }
}
