package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A component reads what it lies on rather than being told: the grounds are provided in a few
 * places only (glass by sheets and raised panels, a picture by artwork surfaces and `OnArtwork`),
 * and no component takes a flag for it.
 */
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

    /**
     * A thing one step up from its ground and the edge of a container are each written once, in
     * Color.kt (`groundStepFill()`, `HlColors.edge`), so a panel, a field and an area to read
     * cannot drift apart.
     */
    @Test
    fun theStepAndTheEdgeAreWrittenOnce() {
        val recipe = Regex("""\.copy\(alpha = (groundStep\(\)|HlAlpha\.EDGE)\)""")
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
