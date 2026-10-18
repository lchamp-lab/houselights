package app.houselights.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The About support code (`support_qr.xml`) still opens the project's Ko-fi page, decoded as a phone would:
 * version 3, level M, its format checked and its error correction recomputed.
 */
class SupportCodeTest {
    private val drawable = File("src/main/res/drawable/support_qr.xml")

    @Test
    fun theCodeOpensTheKoFiPage() {
        val code = modules()
        val (level, mask) = format(code)
        assertEquals("error correction level M", LEVEL_M, level)
        val codewords = codewords(code, mask)
        assertEquals(TOTAL_CODEWORDS, codewords.size)
        val data = codewords.take(DATA_CODEWORDS)
        assertEquals("its error correction, as a reader checks it", errorCorrection(data), codewords.drop(DATA_CODEWORDS))
        assertEquals(URL, text(data))
    }

    @Test
    fun oneModuleChangedFailsTheCheck() {
        // The first module of the data, at the bottom right: the error correction no longer matches what is read.
        val code = modules()
        val (_, mask) = format(code)
        code[SIZE - 1][SIZE - 1] = !code[SIZE - 1][SIZE - 1]
        val codewords = codewords(code, mask)
        assertTrue(errorCorrection(codewords.take(DATA_CODEWORDS)) != codewords.drop(DATA_CODEWORDS))
    }

    /** The dark modules of the drawable, row by row: one path of runs, each "Mx,yhNv1h-Nz". */
    private fun modules(): Array<BooleanArray> {
        assertTrue("Drawable not found from ${File(".").absolutePath}", drawable.isFile)
        val vector = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(drawable).documentElement
        assertEquals("$SIZE", vector.getAttribute("android:viewportWidth"))
        assertEquals("$SIZE", vector.getAttribute("android:viewportHeight"))
        val paths = vector.getElementsByTagName("path")
        assertEquals(1, paths.length)
        val pathData = (paths.item(0) as Element).getAttribute("android:pathData")
        val code = Array(SIZE) { BooleanArray(SIZE) }
        var end = 0
        RUN.findAll(pathData).forEach { run ->
            assertEquals("a path of runs only, at ${run.range.first}", end, run.range.first)
            end = run.range.last + 1
            val (x, y, length) = run.groupValues.drop(1).take(RUN_FIGURES).map(String::toInt)
            assertEquals("a run goes back by its length", run.groupValues[RUN_FIGURES + 1].toInt(), length)
            repeat(length) { code[y][x + it] = true }
        }
        assertEquals("a path of runs only", pathData.length, end)
        return code
    }

    /** Its level and mask, from both copies of the format information, each one of the 32 valid codes. */
    private fun format(code: Array<BooleanArray>): Pair<Int, Int> {
        fun bits(cells: List<Pair<Int, Int>>) = cells.fold(0) { bits, (row, col) -> bits shl 1 or (if (code[row][col]) 1 else 0) }
        val nearTopLeft = bits(
            (0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (5 downTo 0).map { it to 8 },
        )
        val split = bits((SIZE - 1 downTo SIZE - 7).map { it to 8 } + (SIZE - 8 until SIZE).map { 8 to it })
        assertEquals("both copies of the format", nearTopLeft, split)
        val formats = (0 until FORMATS).associateBy { formatCode(it) }
        val format = checkNotNull(formats[nearTopLeft]) { "Not a format: ${nearTopLeft.toString(2)}" }
        return (format shr MASK_BITS) to (format and MASK)
    }

    /** The 15 bits a format of 5 bits is written as: its BCH (15, 5) bits, then the format mask. */
    private fun formatCode(format: Int): Int {
        var rest = format shl BCH_BITS
        for (bit in FORMAT_BITS - 1 downTo BCH_BITS) if (rest shr bit and 1 == 1) rest = rest xor (BCH shl (bit - BCH_BITS))
        return (format shl BCH_BITS or rest) xor FORMAT_MASK
    }

    /** The codewords in reading order, each module unmasked. */
    private fun codewords(code: Array<BooleanArray>, mask: Int): List<Int> {
        val bits = readingOrder().map { (row, col) -> (if (code[row][col]) 1 else 0) xor (if (masked(mask, row, col)) 1 else 0) }
        assertEquals(TOTAL_CODEWORDS * BYTE + REMAINDER_BITS, bits.size)
        return bits.chunked(BYTE).take(TOTAL_CODEWORDS).map { byte -> byte.fold(0) { b, bit -> b shl 1 or bit } }
    }

    /** The data modules in a reader's order: pairs of columns from the right, past the timing column. */
    private fun readingOrder(): List<Pair<Int, Int>> =
        ((SIZE - 1 downTo TIMING + 2 step 2) + (TIMING - 1 downTo 1 step 2)).flatMapIndexed { pair, col ->
            val rows = if (pair % 2 == 0) SIZE - 1 downTo 0 else 0 until SIZE
            rows.flatMap { row -> listOf(row to col, row to col - 1) }
        }.filterNot { (row, col) -> function(row, col) }

    /** What is not data in a version 3 symbol: finders, separators and format, timing, the one alignment pattern. */
    private fun function(row: Int, col: Int): Boolean {
        val far = SIZE - FINDER_ZONE + 1
        return (row < FINDER_ZONE && (col < FINDER_ZONE || col >= far)) || (row >= far && col < FINDER_ZONE) ||
            row == TIMING || col == TIMING || (row in ALIGNMENT && col in ALIGNMENT)
    }

    /** The eight masks of the standard, by row and column. */
    @Suppress("MagicNumber")
    private fun masked(mask: Int, i: Int, j: Int): Boolean = when (mask) {
        0 -> (i + j) % 2 == 0
        1 -> i % 2 == 0
        2 -> j % 3 == 0
        3 -> (i + j) % 3 == 0
        4 -> (i / 2 + j / 3) % 2 == 0
        5 -> (i * j) % 2 + (i * j) % 3 == 0
        6 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0
        else -> ((i + j) % 2 + (i * j) % 3) % 2 == 0
    }

    /** The text of the data codewords: in byte mode, its length on 8 bits, then its bytes. */
    private fun text(data: List<Int>): String {
        val bits = data.joinToString("") { it.toString(2).padStart(BYTE, '0') }
        assertEquals("byte mode", BYTE_MODE, bits.take(MODE_BITS))
        val length = bits.substring(MODE_BITS, MODE_BITS + BYTE).toInt(2)
        val start = MODE_BITS + BYTE
        val bytes = ByteArray(length) { bits.substring(start + it * BYTE, start + (it + 1) * BYTE).toInt(2).toByte() }
        return bytes.toString(Charsets.ISO_8859_1)
    }

    /** The Reed–Solomon codewords of [data] over GF(256), as the encoder computed them. */
    private fun errorCorrection(data: List<Int>): List<Int> {
        val exp = IntArray(FIELD * 2)
        val log = IntArray(FIELD + 1)
        var x = 1
        for (i in 0 until FIELD) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x > FIELD) x = x xor PRIMITIVE
        }
        for (i in FIELD until FIELD * 2) exp[i] = exp[i - FIELD]
        fun times(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
        // The generator: the product of (x - α^i) for i below the number of codewords.
        var generator = listOf(1)
        repeat(EC_CODEWORDS) { i ->
            generator = (0..generator.size).map { k ->
                (if (k < generator.size) generator[k] else 0) xor (if (k > 0) times(generator[k - 1], exp[i]) else 0)
            }
        }
        val rest = IntArray(EC_CODEWORDS)
        data.forEach { codeword ->
            val factor = codeword xor rest[0]
            for (k in 0 until EC_CODEWORDS - 1) rest[k] = rest[k + 1] xor times(generator[k + 1], factor)
            rest[EC_CODEWORDS - 1] = times(generator[EC_CODEWORDS], factor)
        }
        return rest.toList()
    }

    private companion object {
        const val URL = "https://ko-fi.com/lchamp_lab"
        val RUN = Regex("""M(\d+),(\d+)h(\d+)v1h-(\d+)z""")
        /** Where a run starts and how long it is: the figures before the one it goes back by. */
        const val RUN_FIGURES = 3

        // Version 3: 29 modules, 70 codewords and 7 remainder bits; level M: one block, 44 of data.
        const val SIZE = 29
        const val TOTAL_CODEWORDS = 70
        const val DATA_CODEWORDS = 44
        const val EC_CODEWORDS = TOTAL_CODEWORDS - DATA_CODEWORDS
        const val REMAINDER_BITS = 7
        val ALIGNMENT = 20..24
        const val TIMING = 6
        const val FINDER_ZONE = 9

        // Format information: 2 bits of level (M is 00), 3 of mask, BCH (15, 5), masked.
        const val LEVEL_M = 0
        const val MASK_BITS = 3
        const val MASK = 0b111
        const val FORMATS = 32
        const val FORMAT_BITS = 15
        const val BCH_BITS = 10
        const val BCH = 0b10100110111
        const val FORMAT_MASK = 0b101010000010010

        const val BYTE = 8
        const val MODE_BITS = 4
        const val BYTE_MODE = "0100"
        const val FIELD = 255
        const val PRIMITIVE = 0x11D
    }
}
