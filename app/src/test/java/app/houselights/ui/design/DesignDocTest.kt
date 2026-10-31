package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every token, component, function, test or catalogue section DESIGN.md cites is still declared: in the app's code,
 * or a test's helper; a test it cites by its file, a test case in it; a file by its name. A name only called, or only
 * written in a comment or a string, is not declared.
 */
class DesignDocTest {
    private val doc = File("../DESIGN.md")
    private val main = File("src/main/java")
    private val tests = File("src/test/java")

    /**
     * What the doc names in code: types and components (`PosterCard`, `HlLayout.hero`, `CatalogStress`), functions
     * (`emberLit`, `Modifier.houseLit`), constants (`PLATE_MAX`, `N0`) and files (`Type.kt`).
     */
    private val named = Regex("""^([A-Z][a-z]\w*|[a-z]+[A-Z]\w*|[A-Z][A-Z0-9_]+)(\.\w+)*$""")

    /** Compose's and Kotlin's own names, cited to say how the system uses them or that it never does. */
    private val platform = setOf(
        "Modifier", "CircleShape", "ColorProducer", "LineBreak", "Heading", "Paragraph", "LineHeightStyle", "Trim", "Both",
        "contentPadding", "drawBehind", "drawWithCache", "focusProperties", "enter", "focusRestorer", "graphicsLayer", "heightIn",
        "runCatching",
    )

    @Test
    fun everyNameTheDocCitesExists() {
        assertTrue("DESIGN.md not found from ${File(".").absolutePath}", doc.isFile)
        val testFiles = kotlinSources(tests).associate { it.file.nameWithoutExtension to declarations(it.text) }
        val files = kotlinSources(main).map { it.name }.toSet() + testFiles.keys.map { "$it.kt" }
        val declared = kotlinSources(main).flatMap { declarations(it.text) }.toSet() + testFiles.keys +
            kotlinSources(main).map { it.file.nameWithoutExtension } + testHelpers()
        val cited = Regex("""`([A-Za-z][\w.]*)(?:\([^`]*\))?`""").findAll(doc.readText())
            .map { it.groupValues[1] }.filter { named.matches(it) }.toSortedSet()
        assertTrue("The doc cites names of the system", cited.size > CITED_AT_LEAST)
        val missing = cited.filter { name ->
            val parts = name.split('.')
            when {
                name.endsWith(".kt") -> name !in files
                parts.size == 2 && parts[0] in testFiles -> parts[1] !in testFiles.getValue(parts[0])
                else -> parts.any { part -> part !in declared && part !in platform }
            }
        }
        assertTrue("Cited in DESIGN.md, declared nowhere:\n" + missing.joinToString("\n"), missing.isEmpty())
        val unused = platform.filter { name -> cited.none { name in it.split('.') } }
        assertTrue("Platform names the doc no longer cites: ${unused.joinToString()}", unused.isEmpty())
    }

    /** The test helpers' names (`pressOnRemote`), declared in the shared test packages, not in a test of its own. */
    private fun testHelpers(): Set<String> = listOf(tests.resolve("app/houselights/testing"), tests.resolve("app/houselights/ui/testing"))
        .flatMap { kotlinSources(it) }.flatMap { declarations(it.text) }.toSet()

    @Test
    fun aNameOnlyCalledOrWrittenIsNotDeclared() {
        val code = """
            /** PosterCard: a poster. */
            enum class Side(val label: String) { Left, Right }
            fun <T> List<T>.lit(level: Int): String = "Cut: no"
            Volets(films, onOpen = {})
        """.trimIndent()
        assertEquals(setOf("Side", "label", "Left", "Right", "lit", "level"), declarations(code))
    }

    /** Every name [text] declares, its comments and strings aside: a type, function or property, a parameter, an enum entry. */
    private fun declarations(text: String): Set<String> {
        val code = text.withoutComments(strings = true)
        val names = mutableSetOf<String>()
        declaration.findAll(code).forEach { names += it.groupValues[1] }
        // A parameter or a typed property: a name before its type.
        typed.findAll(code).forEach { names += it.groupValues[1] }
        enum.findAll(code).forEach { entries -> Regex("""\b\w+\b""").findAll(entries.groupValues[1]).forEach { names += it.value } }
        return names
    }

    private val declaration = Regex("""\b(?:val|var|fun|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[\w<>?,* ]+\.)?(\w+)""")
    private val typed = Regex("""\b(\w+)\s*:(?!:)""")
    private val enum = Regex("""\benum class \w+[^{]*\{([^}]*)\}""")

    private companion object {
        /** Fewer than the doc cites, so a citation pattern gone wrong fails. */
        const val CITED_AT_LEAST = 300
    }
}
