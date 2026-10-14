package app.houselights.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN.md says the catalogue shows every component in its states. This test lists the public
 * components of `ui/design/components` and fails on any the catalogue never calls, so a new
 * component cannot skip review on a real screen.
 */
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
        "CollectionLight" to "the room's light behind a screen, full screen",
    )

    @Test
    fun theCatalogueShowsEveryComponent() {
        assertTrue("Sources not found from ${File(".").absolutePath}", components.isDirectory && catalog.isDirectory)
        val publicFun = Regex("""^fun ([A-Z]\w*)""", RegexOption.MULTILINE)
        val declared = components.listFiles { f -> f.extension == "kt" }.orEmpty()
            .flatMap { file -> publicFun.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSortedSet()
        val shown = catalog.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
        val missing = declared.filter { it !in elsewhere && !Regex("""\b$it\s*[({]""").containsMatchIn(shown) }
        assertTrue("Not in the catalogue: ${missing.joinToString()}", missing.isEmpty())
        val stale = elsewhere.keys - declared
        assertTrue("Exceptions for components that no longer exist: ${stale.joinToString()}", stale.isEmpty())
    }
}
