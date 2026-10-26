package app.houselights

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails when a layer under the screens (core, player, providers, settings, journal, update) depends on the UI or the app's root. */
class LayersGuardTest {
    private val sources = File("src/main/java/app/houselights")

    private val layers = listOf("core", "player", "providers", "settings", "journal", "update")

    @Test
    fun theLayersUnderTheScreensNeverLeanOnThem() {
        val found = layers.flatMap { kotlinSources(File(sources, it)) }.linesWhere(sources) { it.startsWith("import app.houselights.ui.") }
        assertTrue("A layer under the screens imports the interface:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** The composition root builds the layers and reaches the screens: a layer leaning on it leans on them too. */
    @Test
    fun theLayersUnderTheScreensNeverLeanOnTheRoot() {
        val root = Regex("""\bapp\.houselights\.(AppContainer|HouselightsApp)\b""")
        val found = layers.flatMap { kotlinSources(File(sources, it)) }.linesWhere(sources) { root.containsMatchIn(it) }
        assertTrue("A layer under the screens imports the app's container:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theDomainLeansOnNoOtherLayer() {
        val above = layers.filter { it != "core" }.map { "import app.houselights.$it." }
        val found = kotlinSources(File(sources, "core")).linesWhere(sources) { line -> above.any { line.startsWith(it) } }
        assertTrue("The domain imports another layer:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
