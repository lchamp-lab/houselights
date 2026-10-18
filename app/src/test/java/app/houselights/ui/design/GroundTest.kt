package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Components read their ground; only sheets, raised panels and artwork surfaces provide one, never a flag. */
class GroundTest {
    private val ui = File("src/main/java/app/houselights/ui")

    @Test
    fun groundsAreProvidedInFewPlaces() {
        assertTrue("Sources not found from ${File(".").absolutePath}", ui.isDirectory)
        val providers = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""LocalGround provides""").containsMatchIn(it.readText()) }
            .map { it.name }.toSortedSet()
        assertEquals(sortedSetOf("Cards.kt", "Color.kt", "Panel.kt", "Sheet.kt"), providers)
    }

    /** The step fill and the container edge are written once, in Color.kt, so panels and fields stay alike. */
    @Test
    fun theStepAndTheEdgeAreWrittenOnce() {
        // Elsewhere the step is only laid opaque and EDGE never read; a copy through a local is still a copy.
        val recipe = Regex("""(?<!groundFill\()groundStep\(\)|HlAlpha\.EDGE\b""")
        val found = ui.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Color.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (recipe.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue("Use groundStepFill() and HlColors.edge:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun noComponentIsToldItIsOnArtwork() {
        val told = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\bonArtwork\s*[:=]\s*(Boolean|true)""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Read the ground (OnArtwork, groundPlate) instead of a flag: $told", told.isEmpty())
    }
}
