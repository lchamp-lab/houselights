package app.houselights.ui.design.components

import kotlin.math.abs

/**
 * The modules of a QR code for a text known only at run time (an address on the home network), as ISO/IEC 18004
 * encodes it: level M, the smallest version from 1 to [MAX_VERSION] that holds it, in alphanumeric mode when the text
 * keeps to its characters (an address in capitals), else its UTF-8 bytes; the mask that scores best.
 */
internal class QrModules private constructor(val size: Int, private val dark: BooleanArray) {
    operator fun get(row: Int, col: Int): Boolean = dark[row * size + col]

    companion object {
        const val MAX_VERSION = 6

        fun encode(text: String): QrModules {
            val data = QrData.of(text)
            return QrSymbol(data.version).apply { draw(data.codewords()) }.let { QrModules(it.size, it.dark) }
        }
    }
}

/** A text's bits and the version they fit in; its codewords, error correction blocks interleaved. */
@Suppress("MagicNumber") // The standard's numbers: modes, counts, padding.
internal class QrData private constructor(val version: Int, private val bits: List<Boolean>) {
    fun codewords(): List<Int> {
        val capacity = dataCodewords(version)
        val all = bits.toMutableList()
        // The terminator, then whole bytes, then the pad bytes in turn.
        repeat(minOf(TERMINATOR_BITS, capacity * BYTE - all.size)) { all += false }
        while (all.size % BYTE != 0) all += false
        val bytes = all.chunked(BYTE).map { byte -> byte.fold(0) { b, bit -> b shl 1 or (if (bit) 1 else 0) } }.toMutableList()
        var pad = 0
        while (bytes.size < capacity) bytes += PAD[pad++ % PAD.size]
        return interleave(bytes, version)
    }

    companion object {
        private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        private const val BYTE = 8
        private const val TERMINATOR_BITS = 4
        private val PAD = listOf(0xEC, 0x11)

        // Level M, versions 1 to 6: codewords in all, error correction codewords per block, blocks of equal size.
        private val TOTAL = intArrayOf(26, 44, 70, 100, 134, 172)
        private val EC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16)
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4)

        fun dataCodewords(version: Int) = TOTAL[version - 1] - EC_PER_BLOCK[version - 1] * BLOCKS[version - 1]

        fun of(text: String): QrData {
            val alphanumeric = text.all { it in ALPHANUMERIC }
            val bytes = text.toByteArray(Charsets.UTF_8)
            val bits = mutableListOf<Boolean>()
            fun add(value: Int, count: Int) {
                for (bit in count - 1 downTo 0) bits += (value shr bit and 1) == 1
            }
            if (alphanumeric) {
                add(0b0010, 4)
                add(text.length, 9)
                text.chunked(2).forEach { pair ->
                    val values = pair.map(ALPHANUMERIC::indexOf)
                    if (values.size == 2) add(values[0] * 45 + values[1], 11) else add(values[0], 6)
                }
            } else {
                add(0b0100, 4)
                add(bytes.size, 8)
                bytes.forEach { add(it.toInt() and 0xFF, 8) }
            }
            val version = (1..QrModules.MAX_VERSION).firstOrNull { bits.size <= dataCodewords(it) * BYTE }
            requireNotNull(version) { "Too long for a QR code of version ${QrModules.MAX_VERSION}: ${text.length} characters" }
            return QrData(version, bits)
        }

        /** [data]'s blocks, each followed by its error correction, read column by column: data first, then correction. */
        internal fun interleave(data: List<Int>, version: Int): List<Int> {
            val blocks = data.chunked(data.size / BLOCKS[version - 1])
            val corrections = blocks.map { ReedSolomon.remainder(it, EC_PER_BLOCK[version - 1]) }
            return columns(blocks) + columns(corrections)
        }

        private fun columns(blocks: List<List<Int>>): List<Int> =
            (0 until blocks.maxOf { it.size }).flatMap { i -> blocks.mapNotNull { it.getOrNull(i) } }
    }
}

/** Error correction over GF(256), the field QR codes use (x⁸ + x⁴ + x³ + x² + 1). */
@Suppress("MagicNumber") // The field's size and its polynomial.
internal object ReedSolomon {
    private const val FIELD = 255
    private const val PRIMITIVE = 0x11D
    private val exp = IntArray(FIELD * 2)
    private val log = IntArray(FIELD + 1)

    init {
        var x = 1
        for (i in 0 until FIELD) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x > FIELD) x = x xor PRIMITIVE
        }
        for (i in FIELD until FIELD * 2) exp[i] = exp[i - FIELD]
    }

    private fun times(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    /** The [degree] codewords that correct [data]: its remainder by the product of (x − αⁱ), i below [degree]. */
    fun remainder(data: List<Int>, degree: Int): List<Int> {
        var generator = listOf(1)
        repeat(degree) { i ->
            generator = (0..generator.size).map { k ->
                (if (k < generator.size) generator[k] else 0) xor (if (k > 0) times(generator[k - 1], exp[i]) else 0)
            }
        }
        val rest = IntArray(degree)
        data.forEach { codeword ->
            val factor = codeword xor rest[0]
            for (k in 0 until degree - 1) rest[k] = rest[k + 1] xor times(generator[k + 1], factor)
            rest[degree - 1] = times(generator[degree], factor)
        }
        return rest.toList()
    }
}

/** A symbol of [version] being drawn: its function patterns, then its codewords, masked by the best of eight masks. */
@Suppress("MagicNumber") // The standard's geometry: finders, timing, alignment, format.
internal class QrSymbol(private val version: Int) {
    val size = 17 + 4 * version
    var dark = BooleanArray(size * size)
        private set
    private val function = BooleanArray(size * size)

    private fun set(row: Int, col: Int, on: Boolean) {
        dark[row * size + col] = on
        function[row * size + col] = true
    }

    fun draw(codewords: List<Int>) {
        drawFunctions()
        place(codewords)
        val placed = dark
        dark = (0 until MASKS).map { mask -> masked(placed, mask) }.minBy(::penalty)
    }

    private fun drawFunctions() {
        finder(0, 0)
        finder(0, size - 7)
        finder(size - 7, 0)
        for (i in 8 until size - 8) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        // Versions 2 to 6 have one alignment pattern, the others' places being the finders'.
        if (version > 1) {
            val centre = size - 7
            for (dr in -2..2) for (dc in -2..2) set(centre + dr, centre + dc, maxOf(abs(dr), abs(dc)) != 1)
        }
        // Reserved for the format, drawn with each mask.
        format(0)
    }

    /** A finder at its corner, with its light separator where it lies inside the symbol. */
    private fun finder(top: Int, left: Int) {
        for (dr in -1..7) for (dc in -1..7) {
            val row = top + dr
            val col = left + dc
            if (row !in 0 until size || col !in 0 until size) continue
            val ring = maxOf(abs(dr - 3), abs(dc - 3))
            set(row, col, ring != 2 && ring != 4)
        }
    }

    /** Both copies of the format: level M and [mask], BCH (15, 5), masked; and the dark module. */
    private fun format(mask: Int) {
        val data = LEVEL_M shl 3 or mask
        var rest = data
        repeat(10) { rest = (rest shl 1) xor ((rest ushr 9) * FORMAT_GENERATOR) }
        val bits = (data shl 10 or rest) xor FORMAT_MASK
        fun bit(i: Int) = (bits shr i and 1) == 1
        for (i in 0..5) set(i, 8, bit(i))
        set(7, 8, bit(6))
        set(8, 8, bit(7))
        set(8, 7, bit(8))
        for (i in 9 until 15) set(8, 14 - i, bit(i))
        for (i in 0 until 8) set(8, size - 1 - i, bit(i))
        for (i in 8 until 15) set(size - 15 + i, 8, bit(i))
        set(size - 8, 8, true)
    }

    /** The codewords' bits in pairs of columns from the bottom right, up then down, past the timing column. */
    private fun place(codewords: List<Int>) {
        val total = codewords.size * 8
        dataModules().take(total).forEachIndexed { i, at -> dark[at] = (codewords[i ushr 3] shr (7 - (i and 7)) and 1) == 1 }
    }

    /** The modules left for data, in the order their bits are placed. */
    private fun dataModules(): List<Int> {
        val order = mutableListOf<Int>()
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            val upward = ((right + 1) and 2) == 0
            // Two modules a row, right then left, going up or down the pair of columns.
            for (k in 0 until size * 2) {
                val row = if (upward) size - 1 - k / 2 else k / 2
                val at = row * size + right - k % 2
                if (!function[at]) order += at
            }
            right -= 2
        }
        return order
    }

    /** [placed] with [mask] over its data and its format drawn for it. */
    private fun masked(placed: BooleanArray, mask: Int): BooleanArray {
        dark = placed.copyOf()
        for (row in 0 until size) for (col in 0 until size) {
            if (!function[row * size + col] && flips(mask, row, col)) dark[row * size + col] = !dark[row * size + col]
        }
        format(mask)
        return dark
    }

    private fun flips(mask: Int, row: Int, col: Int): Boolean = when (mask) {
        0 -> (row + col) % 2 == 0
        1 -> row % 2 == 0
        2 -> col % 3 == 0
        3 -> (row + col) % 3 == 0
        4 -> (row / 2 + col / 3) % 2 == 0
        5 -> row * col % 2 + row * col % 3 == 0
        6 -> (row * col % 2 + row * col % 3) % 2 == 0
        else -> ((row + col) % 2 + row * col % 3) % 2 == 0
    }

    /** The standard's penalty: long runs, 2 × 2 blocks, finder-like patterns, and dark far from half. */
    private fun penalty(modules: BooleanArray): Int {
        fun at(row: Int, col: Int) = modules[row * size + col]
        val rows = (0 until size).map { r -> (0 until size).map { at(r, it) } }
        val lines = rows + (0 until size).map { c -> (0 until size).map { at(it, c) } }
        var score = lines.sumOf { runs(it) + FINDER_PENALTY * finderLike(it) }
        for (row in 0 until size - 1) for (col in 0 until size - 1) {
            val c = at(row, col)
            if (at(row, col + 1) == c && at(row + 1, col) == c && at(row + 1, col + 1) == c) score += BLOCK_PENALTY
        }
        val darkCount = modules.count { it }
        val total = size * size
        score += DARK_PENALTY * ((abs(darkCount * 20 - total * 10) + total - 1) / total - 1)
        return score
    }

    private fun runs(line: List<Boolean>): Int {
        var score = 0
        var length = 1
        for (i in 1..line.size) {
            if (i < line.size && line[i] == line[i - 1]) {
                length++
            } else {
                if (length >= RUN) score += RUN_PENALTY + length - RUN
                length = 1
            }
        }
        return score
    }

    private fun finderLike(line: List<Boolean>): Int =
        (0..line.size - FINDER_LIKE.size).count { start -> FINDER_LIKE.indices.all { line[start + it] == FINDER_LIKE[it] } } +
            (0..line.size - FINDER_LIKE.size).count { start -> FINDER_LIKE.indices.all { line[start + it] == FINDER_LIKE_BEFORE[it] } }

    private companion object {
        const val MASKS = 8
        const val LEVEL_M = 0
        const val FORMAT_GENERATOR = 0x537
        const val FORMAT_MASK = 0x5412
        const val RUN = 5
        const val RUN_PENALTY = 3
        const val BLOCK_PENALTY = 3
        const val FINDER_PENALTY = 40
        const val DARK_PENALTY = 10

        /** 1:1:3:1:1 dark and light, then four light modules; and the same after four light ones. */
        val FINDER_LIKE = listOf(true, false, true, true, true, false, true, false, false, false, false)
        val FINDER_LIKE_BEFORE = FINDER_LIKE.reversed()
    }
}
