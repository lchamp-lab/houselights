package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails on a public component of `ui/design/components` that the catalogue never shows (DESIGN.md, Catalogue). */
class CatalogCoverageTest {
    private val components = File("src/main/java/app/houselights/ui/design/components")
    private val catalog = File("src/main/java/app/houselights/ui/catalog")

    /** Components the catalogue cannot hold, and why. Keep it short. */
    private val elsewhere = mapOf(
        "HlTwoPane" to "fills the screen: every settings and form screen is its specimen",
        "HlFormScaffold" to "fills the screen: Add a folder, Jellyfin and Identify are its specimens",
        "PosterWall" to "the welcome screen's moving wall, full screen",
        "PosterWallScrim" to "the welcome screen's veil, full screen",
        "HouseLights" to "the playback cue, over the whole app",
        "LeavingBackdrop" to "the picture of a screen left going out, under the whole app",
    )

    @Test
    fun theCatalogueShowsEveryComponent() {
        assertTrue("Sources not found from ${File(".").absolutePath}", components.isDirectory && catalog.isDirectory)
        val publicFun = Regex("""^fun ([A-Z]\w*)""", RegexOption.MULTILINE)
        val declared = components.listFiles { f -> f.extension == "kt" }.orEmpty()
            .flatMap { file -> publicFun.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSortedSet()
        val shown = kotlinSources(catalog).joinToString("\n") { it.readText() }
        val missing = declared.filter { it !in elsewhere && !Regex("""\b$it\s*[({]""").containsMatchIn(shown) }
        assertTrue("Not in the catalogue: ${missing.joinToString()}", missing.isEmpty())
        val stale = elsewhere.keys - declared
        assertTrue("Exceptions for components that no longer exist: ${stale.joinToString()}", stale.isEmpty())
    }

    /** Every glyph of `HlIcons` is on the catalogue's icon sheet, so a new one is seen at its sizes. */
    @Test
    fun theCatalogueShowsEveryIcon() {
        val icons = File("src/main/java/app/houselights/ui/design/Icons.kt").readText()
        val declared = Regex("""^\s*val (\w+) = icon\(""", RegexOption.MULTILINE).findAll(icons).map { it.groupValues[1] }.toList()
        assertTrue("No icons found in Icons.kt", declared.isNotEmpty())
        val sheet = catalog.resolve("CatalogIcons.kt").readText()
        val missing = declared.filter { !Regex("""\bHlIcons\.$it\b""").containsMatchIn(sheet) }
        assertTrue("Not on the catalogue's icon sheet: ${missing.joinToString()}", missing.isEmpty())
    }
}
