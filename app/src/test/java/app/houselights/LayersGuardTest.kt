package app.houselights

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
        assertTrue("Sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val found = layers.flatMap { layer ->
            File(sources, layer).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
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
        assertTrue("Sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val above = layers.filter { it != "core" }.map { "import app.houselights.$it." }
        val found = File(sources, "core").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().filter { line -> above.any { line.startsWith(it) } }.map { "${file.relativeTo(sources).path}: $it" }
        }.toList()
        assertTrue("The domain imports another layer:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
