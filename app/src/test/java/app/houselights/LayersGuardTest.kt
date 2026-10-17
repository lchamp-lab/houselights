package app.houselights

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The layers under the screens (the domain, the player, the sources, the settings) raise and hold
 * facts; the screens say them in their words. This test reads every source of those layers and fails
 * on one that leans on the interface: a UiError kept by the player, a screen's state in a provider.
 */
class LayersGuardTest {
    private val sources = File("src/main/java/app/houselights")

    /** The layers under the screens, as packages. */
    private val layers = listOf("core", "player", "providers", "settings")

    /**
     * Deliberate exceptions, as "path:import". Keep this list short and say why; a new one is usually
     * a fact of the domain ([app.houselights.core.FailureKind]) the interface translates.
     */
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

    /**
     * The domain lies under every other layer: the exceptions it classifies ([app.houselights.core.FailureKind])
     * are its own, never a provider's or the player's.
     */
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
