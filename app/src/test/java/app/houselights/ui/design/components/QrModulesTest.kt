package app.houselights.ui.design.components

import app.houselights.testing.descendants
import app.houselights.testing.xmlRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * A QR code encoded at run time reads as a phone reads it: decoded here independently of the encoder, its format
 * checked, its blocks' error correction recomputed, its text read back; and for the Ko-fi address, the same codewords
 * as `qrencode` drew in `support_qr.xml`.
 */
class QrModulesTest {
    @Test
    fun theLongestJournalAddressFitsTheBoardsSize() {
        val address = "HTTP://192.168.100.200:65535/JOURNAL/K7F2M4Q9X3W8R5T6Y2ZB"
        val code = QrModules.encode(address)
        assertEquals("version 3, 29 modules across, as the board draws it", 29, code.size)
        val read = decode(grid(code))
        assertEquals("alphanumeric", 0b0010, read.mode)
        assertEquals(address, read.text)
    }

    @Test
    fun aShortTextTakesTheSmallestVersion() {
        val code = QrModules.encode("HOUSELIGHTS")
        assertEquals(21, code.size)
        assertEquals("HOUSELIGHTS", decode(grid(code)).text)
    }

    @Test
    fun aTextOutsideTheAlphanumericSetIsCodedAsUtf8() {
        val text = "http://nas.local/journal/é" + "x".repeat(50)
        val read = decode(grid(QrModules.encode(text)))
        assertEquals("bytes", 0b0100, read.mode)
        assertEquals(text, read.text)
    }

    /** Versions 4 to 6 split their data into blocks, read interleaved: each block's correction holds. */
    @Test
    fun aLongerTextIsReadAcrossItsBlocks() {
        listOf(60 to 33, 80 to 37, 100 to 41).forEach { (length, size) ->
            val text = "abcdefghijklmnopqrstuvwxyz".repeat(4).take(length)
            val code = QrModules.encode(text)
            assertEquals("$length bytes", size, code.size)
            assertEquals(text, decode(grid(code)).text)
        }
    }

    @Test
    fun theKoFiAddressHasTheCodewordsQrencodeDrew() {
        val drawn = decode(drawable(File("src/main/res/drawable/support_qr.xml")))
        val encoded = decode(grid(QrModules.encode("https://ko-fi.com/lchamp_lab")))
        assertEquals("https://ko-fi.com/lchamp_lab", drawn.text)
        assertEquals(drawn.codewords, encoded.codewords)
    }

    @Test
    fun aTextTooLongIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { QrModules.encode("x".repeat(200)) }
    }

    @Test
    fun oneModuleChangedFailsTheCheck() {
        val modules = grid(QrModules.encode("HOUSELIGHTS"))
        val size = modules.size
        modules[size - 1][size - 1] = !modules[size - 1][size - 1]
        assertThrows(AssertionError::class.java) { decode(modules) }
    }

    /** What a reader gets: the mode, the text, the codewords unmasked in reading order. */
    private class Read(val mode: Int, val text: String, val codewords: List<Int>)

    private fun grid(code: QrModules) = Array(code.size) { row -> BooleanArray(code.size) { col -> code[row, col] } }

    /** The modules of a `qrencode` vector: one path of runs, each "Mx,yhNv1h-Nz". */
    private fun drawable(file: File): Array<BooleanArray> {
        val vector = xmlRoot(file)
        val size = vector.getAttribute("android:viewportWidth").toInt()
        val code = Array(size) { BooleanArray(size) }
        RUN.findAll(vector.descendants("path").single().getAttribute("android:pathData")).forEach { run ->
            val (x, y, length) = run.groupValues.drop(1).map(String::toInt)
            repeat(length) { code[y][x + it] = true }
        }
        return code
    }

    private fun decode(code: Array<BooleanArray>): Read {
        val size = code.size
        val version = (size - 17) / 4
        assertTrue("versions 1 to 6", version in 1..6)
        val mask = format(code)
        val bits = readingOrder(size, version).map { (row, col) -> code[row][col] != masked(mask, row, col) }
        val total = TOTAL[version - 1]
        assertEquals(total * 8 + if (version == 1) 0 else REMAINDER_BITS, bits.size)
        val codewords = bits.chunked(8).take(total).map { byte -> byte.fold(0) { b, bit -> b shl 1 or (if (bit) 1 else 0) } }
        // Data first, then correction, each read across the blocks in turn.
        val blocks = BLOCKS[version - 1]
        val ec = EC[version - 1]
        val dataEach = (total - ec * blocks) / blocks
        val data = (0 until blocks).map { b -> (0 until dataEach).map { codewords[it * blocks + b] } }
        val correction = (0 until blocks).map { b -> (0 until ec).map { codewords[dataEach * blocks + it * blocks + b] } }
        data.zip(correction).forEach { (block, check) -> assertEquals("a block's correction", errorCorrection(block, ec), check) }
        val stream = data.flatten().joinToString("") { it.toString(2).padStart(8, '0') }
        val mode = stream.take(4).toInt(2)
        return Read(mode, text(mode, stream.drop(4)), codewords)
    }

    private fun text(mode: Int, bits: String): String = when (mode) {
        0b0010 -> {
            val count = bits.take(9).toInt(2)
            var at = 9
            buildString {
                while (length + 1 < count) {
                    val pair = bits.substring(at, at + 11).toInt(2)
                    append(ALPHANUMERIC[pair / 45]).append(ALPHANUMERIC[pair % 45])
                    at += 11
                }
                if (length < count) append(ALPHANUMERIC[bits.substring(at, at + 6).toInt(2)])
            }
        }
        0b0100 -> {
            val count = bits.take(8).toInt(2)
            ByteArray(count) { bits.substring(8 + it * 8, 16 + it * 8).toInt(2).toByte() }.toString(Charsets.UTF_8)
        }
        else -> error("Mode $mode")
    }

    /** Level M checked, and the mask, from both copies of the format, each one of the 32 valid codes. */
    private fun format(code: Array<BooleanArray>): Int {
        val size = code.size
        fun bits(cells: List<Pair<Int, Int>>) = cells.fold(0) { bits, (row, col) -> bits shl 1 or (if (code[row][col]) 1 else 0) }
        val nearTopLeft = bits((0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (5 downTo 0).map { it to 8 })
        val split = bits((size - 1 downTo size - 7).map { it to 8 } + (size - 8 until size).map { 8 to it })
        assertEquals("both copies of the format", nearTopLeft, split)
        val format = (0 until 32).firstOrNull { formatCode(it) == nearTopLeft } ?: throw AssertionError("Not a format")
        assertEquals("level M", 0, format shr 3)
        return format and 0b111
    }

    private fun formatCode(format: Int): Int {
        var rest = format shl 10
        for (bit in 14 downTo 10) if (rest shr bit and 1 == 1) rest = rest xor (0b10100110111 shl (bit - 10))
        return (format shl 10 or rest) xor 0b101010000010010
    }

    private fun readingOrder(size: Int, version: Int): List<Pair<Int, Int>> =
        ((size - 1 downTo 8 step 2) + (5 downTo 1 step 2)).flatMapIndexed { pair, col ->
            val rows = if (pair % 2 == 0) size - 1 downTo 0 else 0 until size
            rows.flatMap { row -> listOf(row to col, row to col - 1) }
        }.filterNot { (row, col) -> function(size, version, row, col) }

    /** Finders with their separators and format, timing, and from version 2 the one alignment pattern. */
    private fun function(size: Int, version: Int, row: Int, col: Int): Boolean {
        val far = size - 8
        val finder = (row < 9 && (col < 9 || col >= far)) || (row >= far && col < 9)
        val alignment = version > 1 && abs(row - (size - 7)) <= 2 && abs(col - (size - 7)) <= 2
        return finder || row == 6 || col == 6 || alignment
    }

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

    /** Reed–Solomon over GF(256) with x⁸ + x⁴ + x³ + x² + 1, computed here, not by the encoder. */
    private fun errorCorrection(data: List<Int>, degree: Int): List<Int> {
        val exp = IntArray(512)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x > 255) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        fun times(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
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

    private companion object {
        val RUN = Regex("""M(\d+),(\d+)h(\d+)v1h-\d+z""")
        const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        const val REMAINDER_BITS = 7

        // Level M, versions 1 to 6, from the standard's table.
        val TOTAL = intArrayOf(26, 44, 70, 100, 134, 172)
        val EC = intArrayOf(10, 16, 26, 18, 24, 16)
        val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4)
    }
}
