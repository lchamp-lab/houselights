package app.houselights

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails when the baseline profile no longer matches the code: it names a file's class (`…Kt`) whose file is gone, or
 * lacks home's entry points. Regenerated as CONTRIBUTING.md says (Baseline profile).
 */
class BaselineProfileGuardTest {
    private val profile = File("src/main/generated/baselineProfiles/baseline-prof.txt")
    private val sources = File("src/main/java")

    private val lines by lazy {
        assertTrue("${profile.path} not found from ${File(".").absolutePath}", profile.isFile)
        profile.readLines()
    }

    @Test
    fun everyFileClassItNamesHasItsFile() {
        val files = lines.flatMap { line -> FILE_CLASS.findAll(line).map { it.groupValues[1] + it.groupValues[2] + ".kt" } }.toSortedSet()
        val gone = files.filterNot { File(sources, it).isFile }
        assertTrue("The baseline profile names files since renamed or removed:\n" + gone.joinToString("\n"), gone.isEmpty())
    }

    @Test
    fun itHoldsHomesEntryPoints() {
        val named = { (file, function): Pair<String, String> -> "$file.kt: $function" }
        val renamed = ENTRY_POINTS.filterNot { (file, function) -> File(sources, "$file.kt").readText().contains("fun $function(") }
        assertTrue("Home's entry points moved; list them again here:\n" + renamed.joinToString("\n", transform = named), renamed.isEmpty())
        val missing = ENTRY_POINTS.filterNot { (file, function) -> lines.any { it.contains("L${file}Kt;->$function(") } }
        assertTrue("The baseline profile lacks home's entry points:\n" + missing.joinToString("\n", transform = named), missing.isEmpty())
    }

    private companion object {
        /** A file's class, its lambdas and its composable singletons: the file's directory, then its name. */
        val FILE_CLASS = Regex("""L(app/houselights/(?:\w+/)*)(?:ComposableSingletons\$)?(\w+)Kt[;$]""")

        /** Home's grid, its doors' layout and a door: what every start runs first. */
        val ENTRY_POINTS = listOf(
            "app/houselights/ui/home/HomeScreen" to "HomeGrid",
            "app/houselights/ui/design/components/DoorGrid" to "DoorGrid",
            "app/houselights/ui/design/components/HomeDoor" to "HomeDoor",
        )
    }
}
