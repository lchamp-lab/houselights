package app.houselights.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN.md names the design system as the code has it: every token, component, test or catalogue
 * section it cites is still declared somewhere, so a renamed or removed piece cannot linger there.
 */
class DesignDocTest {
    private val doc = File("../DESIGN.md")
    private val sources = listOf(File("src/main/java"), File("src/test/java"))

    /** What the system names: `HlLayout.hero`, `HlIconButtonDefaults`, `FocusStyleGuardTest`, `CatalogStress`. */
    private val ours = Regex("""^(Hl\w*|\w+Defaults|\w+Test|Catalog\w*|FocusStyle|Ground|Lift)(\.\w+)*$""")

    @Test
    fun everyNameTheDocCitesExists() {
        assertTrue("DESIGN.md not found from ${File(".").absolutePath}", doc.isFile)
        val files = sources.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val code = files.joinToString("\n") { it.readText() }
        val fileNames = files.map { it.nameWithoutExtension }.toSet()
        val cited = Regex("""`([A-Za-z][\w.]*)(?:\([^`]*\))?`""").findAll(doc.readText())
            .map { it.groupValues[1] }.filter { ours.matches(it) }.toSortedSet()
        assertTrue("The doc cites names of the system", cited.size > 50)
        val missing = cited.filter { name ->
            name.split('.').any { part -> part !in fileNames && !declared(part, code) }
        }
        assertTrue("Cited in DESIGN.md, declared nowhere:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** [name] as a declaration, a parameter or an enum entry. */
    private fun declared(name: String, code: String): Boolean = Regex(
        """\b(val|var|fun|class|object|interface|typealias)\s+(?:\w+\.)?$name\b|\b$name\s*:|^\s*$name\s*[(,]|""" +
            """\benum class \w+\s*\{[^}]*\b$name\b""",
        RegexOption.MULTILINE,
    ).containsMatchIn(code)
}
