package app.houselights

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails when a layer under the screens (core, player, providers, settings) depends on the UI. */
class LayersGuardTest {
    private val sources = File("src/main/java/app/houselights")

    private val layers = listOf("core", "player", "providers", "settings")

    /** Deliberate exceptions, as "path:import": keep it short; a new one usually belongs in the domain instead. */
    private val allowed = emptyList<String>()

    @Test
    fun theLayersUnderTheScreensNeverLeanOnThem() {
        val found = layers.flatMap { layer ->
            kotlinSources(File(sources, layer)).flatMap { file ->
                val path = file.relativeTo(sources).path
                file.readLines().filter { it.startsWith("import app.houselights.ui.") }
                    .filterNot { line -> "$path:${line.trim()}" in allowed }
                    .map { "$path: $it" }
            }
        }
        assertTrue("A layer under the screens imports the interface:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theDomainLeansOnNoOtherLayer() {
        val above = layers.filter { it != "core" }.map { "import app.houselights.$it." }
        val found = kotlinSources(File(sources, "core")).linesWhere(sources) { line -> above.any { line.startsWith(it) } }
        assertTrue("The domain imports another layer:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
