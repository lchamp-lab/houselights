package app.houselights

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails when a layer under the screens (core, player, providers, settings, journal) depends on the UI. */
class LayersGuardTest {
    private val sources = File("src/main/java/app/houselights")

    private val layers = listOf("core", "player", "providers", "settings", "journal")

    @Test
    fun theLayersUnderTheScreensNeverLeanOnThem() {
        val found = layers.flatMap { kotlinSources(File(sources, it)) }.linesWhere(sources) { it.startsWith("import app.houselights.ui.") }
        assertTrue("A layer under the screens imports the interface:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theDomainLeansOnNoOtherLayer() {
        val above = layers.filter { it != "core" }.map { "import app.houselights.$it." }
        val found = kotlinSources(File(sources, "core")).linesWhere(sources) { line -> above.any { line.startsWith(it) } }
        assertTrue("The domain imports another layer:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
