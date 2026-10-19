package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Components read their ground; only sheets, raised panels and artwork surfaces provide one, never a flag. */
class GroundTest {
    private val ui = File("src/main/java/app/houselights/ui")

    @Test
    fun groundsAreProvidedInFewPlaces() {
        val providers = kotlinSources(ui)
            .filter { Regex("""LocalGround provides""").containsMatchIn(it.readText()) }
            .map { it.name }.toSortedSet()
        assertEquals(sortedSetOf("Cards.kt", "Color.kt", "Panel.kt", "Sheet.kt"), providers)
    }

    /** The step fill and the container edge are written once, in Color.kt, so panels and fields stay alike. */
    @Test
    fun theStepAndTheEdgeAreWrittenOnce() {
        // Elsewhere the step is only laid opaque and EDGE never read; a copy through a local is still a copy.
        val recipe = Regex("""(?<!groundFill\()groundStep\(\)|HlAlpha\.EDGE\b""")
        val found = kotlinSources(ui).filter { it.name != "Color.kt" }.linesWhere(ui) { recipe.containsMatchIn(it) }
        assertTrue("Use groundStepFill() and HlColors.edge:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun noComponentIsToldItIsOnArtwork() {
        val told = kotlinSources(ui)
            .filter { Regex("""\bonArtwork\s*[:=]\s*(Boolean|true)""").containsMatchIn(it.readText()) }
            .map { it.name }
        assertTrue("Read the ground (OnArtwork, groundPlate) instead of a flag: $told", told.isEmpty())
    }
}
