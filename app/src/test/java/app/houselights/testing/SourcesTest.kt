package app.houselights.testing

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The guards read code only: every comment blanked, what strings hold kept, lines and offsets unchanged. */
class SourcesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun commentsAreBlankedWhereverTheyStand() {
        val text = """
            /** A KDoc, CircleShape(),
             * on two lines. */
            val a = 1 // 12.dp
            /* a block /* nested */ still */ val b = 2
        """.trimIndent()
        val code = text.withoutComments()
        assertEquals(text.length, code.length)
        assertEquals(listOf("", "", "val a = 1", "val b = 2"), code.lines().map { it.trim() })
    }

    @Test
    fun stringsKeepWhatLooksLikeAComment() {
        listOf(
            """val url = "http://host/*" + x""",
            """val raw = ""${'"'}a // b /* c""${'"'}""",
            """val quote = '"' + "// still a string"""",
            """val template = "${'$'}{ "}" } // kept" """,
        ).forEach { line -> assertEquals(line, line, line.withoutComments()) }
        assertEquals("val a = '\\''", "val a = '\\'' // a quote".withoutComments().trimEnd())
    }

    @Test
    fun stringsAreBlankedOnRequest() {
        val line = """val a = "PosterCard: ${'$'}{b("c")}" + ""${'"'}d: e""${'"'} // f"""
        val blanked = "val a = \"" + " ".repeat("PosterCard: \${b(\"c\")}".length) + "\" + \"\"\"    \"\"\""
        assertEquals(blanked, line.withoutComments(strings = true).trimEnd())
    }

    @Test
    fun aFilesCodeLinesKeepTheirNumbers() {
        val file = folder.newFile("A.kt").apply { writeText("import a.B\n\n// B\nval c = B() // a B\n") }
        val source = source(file)
        assertEquals(listOf(1 to "import a.B", 4 to "val c = B()"), source.codeLines().map { it.number to it.text.trim() })
        assertEquals(listOf(4), source.codeLines(imports = false).map { it.number })
        assertEquals(4, source.lineOf(source.code.indexOf("val")))
    }
}
