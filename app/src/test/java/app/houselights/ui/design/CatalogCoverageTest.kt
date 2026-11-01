package app.houselights.ui.design

import androidx.compose.ui.graphics.Shape
import app.houselights.testing.kotlinSources
import app.houselights.testing.source
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails on a component, shape, glyph, colour role or type style the catalogue never shows (DESIGN.md, Catalogue). */
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
        "DoorGrid" to "home's whole screen: home is its specimen, its doors each in the catalogue",
    )

    /** What the catalogue's sections draw, their comments aside. */
    private val shown by lazy { kotlinSources(catalog).joinToString("\n") { it.code } }

    @Test
    fun theCatalogueShowsEveryComponent() {
        val publicFun = Regex("""^fun ([A-Z]\w*)""", RegexOption.MULTILINE)
        val declared = kotlinSources(components).flatMap { file -> publicFun.findAll(file.code).map { it.groupValues[1] }.toList() }
            .toSortedSet()
        assertTrue("No components found", declared.isNotEmpty())
        val missing = declared.filter { it !in elsewhere && !Regex("""\b$it\s*[({]""").containsMatchIn(shown) }
        assertTrue("Not in the catalogue: ${missing.joinToString()}", missing.isEmpty())
        val stale = elsewhere.keys - declared
        assertTrue("Exceptions for components that no longer exist: ${stale.joinToString()}", stale.isEmpty())
    }

    /** Every shape of `HlShapes` is on the catalogue's shape sheet, with its radii. */
    @Test
    fun theCatalogueShowsEveryShape() {
        val declared = propertiesOf(HlShapes, Shape::class.java).keys
        assertTrue("No shapes found", declared.isNotEmpty())
        val sheet = source(catalog.resolve("CatalogFocus.kt")).code
        val missing = declared.filter { !Regex("""\bHlShapes\.$it\b""").containsMatchIn(sheet) }
        assertTrue("Not on the catalogue's shape sheet: ${missing.joinToString()}", missing.isEmpty())
    }

    /** Every colour role is on the catalogue's sheets; the ember light's two ends in its swatch. */
    @Test
    fun theCatalogueShowsEveryColourRole() {
        val roles = propertyNames(HlColors::class.java)
        assertTrue("No colour roles found", roles.isNotEmpty())
        val inTheLight = setOf("emberFrom", "emberTo")
        val missing = roles.filter { role ->
            !Regex("""\b(c|Hl\.colors)\.$role\b""").containsMatchIn(shown) && !(role in inTheLight && "c.emberLight()" in shown)
        }
        assertTrue("Colour roles not in the catalogue: ${missing.joinToString()}", missing.isEmpty())
    }

    /** Every style of `HlType` is on the catalogue's type sheet. */
    @Test
    fun theCatalogueShowsEveryTypeStyle() {
        val sheet = source(catalog.resolve("CatalogScreen.kt")).code
        val styles = propertyNames(HlType::class.java)
        assertTrue("No type styles found", styles.isNotEmpty())
        val missing = styles.filter { !Regex("""\bt\.$it\b""").containsMatchIn(sheet) }
        assertTrue("Not on the catalogue's type sheet: ${missing.joinToString()}", missing.isEmpty())
    }

    /** Every glyph of `HlIcons` is on the catalogue's icon sheet, so a new one is seen at its sizes. */
    @Test
    fun theCatalogueShowsEveryIcon() {
        val icons = source(File("src/main/java/app/houselights/ui/design/Icons.kt")).code
        val declared = Regex("""^\s*val (\w+) = icon\(""", RegexOption.MULTILINE).findAll(icons).map { it.groupValues[1] }.toList()
        assertTrue("No icons found in Icons.kt", declared.isNotEmpty())
        val sheet = source(catalog.resolve("CatalogIcons.kt")).code
        val missing = declared.filter { !Regex("""\bHlIcons\.$it\b""").containsMatchIn(sheet) }
        assertTrue("Not on the catalogue's icon sheet: ${missing.joinToString()}", missing.isEmpty())
    }
}
