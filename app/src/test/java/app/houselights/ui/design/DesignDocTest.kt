package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every token, component, function, test or catalogue section DESIGN.md cites is still declared in the code. */
class DesignDocTest {
    private val doc = File("../DESIGN.md")
    private val sources = listOf(File("src/main/java"), File("src/test/java"))

    /**
     * What the doc names in code: types and components (`PosterCard`, `HlLayout.hero`, `CatalogStress`), functions
     * (`emberLit`, `Modifier.houseLit`) and constants (`PLATE_MAX`, `N0`).
     */
    private val named = Regex("""^([A-Z][a-z]\w*|[a-z]+[A-Z]\w*|[A-Z][A-Z0-9_]+)(\.\w+)*$""")

    /** Compose's own names, cited to say how the system uses them or that it never does. */
    private val platform = setOf(
        "Modifier", "CircleShape", "ColorProducer", "LineBreak", "Heading", "Paragraph", "LineHeightStyle", "Trim", "Both",
        "contentPadding", "drawBehind", "drawWithCache", "focusProperties", "enter", "focusRestorer", "graphicsLayer", "heightIn",
    )

    @Test
    fun everyNameTheDocCitesExists() {
        assertTrue("DESIGN.md not found from ${File(".").absolutePath}", doc.isFile)
        val files = sources.flatMap(::kotlinSources)
        val declared = declarations(files.joinToString("\n") { it.text }) + files.map { it.file.nameWithoutExtension }
        val cited = Regex("""`([A-Za-z][\w.]*)(?:\([^`]*\))?`""").findAll(doc.readText())
            .map { it.groupValues[1] }.filter { named.matches(it) }.toSortedSet()
        assertTrue("The doc cites names of the system", cited.size > 300)
        val missing = cited.filter { name -> name.split('.').any { part -> part !in declared && part !in platform } }
        assertTrue("Cited in DESIGN.md, declared nowhere:\n" + missing.joinToString("\n"), missing.isEmpty())
        val unused = platform.filter { name -> cited.none { name in it.split('.') } }
        assertTrue("Platform names the doc no longer cites: ${unused.joinToString()}", unused.isEmpty())
    }

    /** Every name [code] declares: a type, function or property (generic or extension), a parameter, an enum entry. */
    private fun declarations(code: String): Set<String> {
        val names = mutableSetOf<String>()
        val declaration = Regex("""\b(?:val|var|fun|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?(?:[\w<>?,* ]+\.)?(\w+)""")
        declaration.findAll(code).forEach { names += it.groupValues[1] }
        Regex("""\b(\w+)\s*:(?!:)""").findAll(code).forEach { names += it.groupValues[1] }
        Regex("""^\s*(\w+)\s*[(,]""", RegexOption.MULTILINE).findAll(code).forEach { names += it.groupValues[1] }
        Regex("""\benum class \w+[^{]*\{([^}]*)\}""").findAll(code).forEach { entries ->
            Regex("""\b\w+\b""").findAll(entries.groupValues[1]).forEach { names += it.value }
        }
        return names
    }
}
