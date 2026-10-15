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

    @Test
    fun noComponentIsToldItIsOnArtwork() {
        val told = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\bonArtwork\s*[:=]\s*(Boolean|true)""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("Read the ground (OnArtwork, groundPlate) instead of a flag: $told", told.isEmpty())
    }
}
