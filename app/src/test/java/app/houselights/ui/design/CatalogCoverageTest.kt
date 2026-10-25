package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

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
        "PosterWallGrid" to "fills the screen: a library's wall and a person's page are its specimens",
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

    /** Every shape of `HlShapes` is on the catalogue's shape sheet, with its radii. */
    @Test
    fun theCatalogueShowsEveryShape() {
        val dimens = File("src/main/java/app/houselights/ui/design/Dimens.kt").readText()
        val start = dimens.indexOf("object HlShapes")
        val shapes = dimens.substring(start, dimens.indexOf("\n}\n", start))
        val declared = Regex("""^\s{4}val (\w+) =""", RegexOption.MULTILINE).findAll(shapes).map { it.groupValues[1] }.toList()
        assertTrue("No shapes found in Dimens.kt", declared.isNotEmpty())
        val sheet = catalog.resolve("CatalogFocus.kt").readText()
        val missing = declared.filter { !Regex("""\bHlShapes\.$it\b""").containsMatchIn(sheet) }
        assertTrue("Not on the catalogue's shape sheet: ${missing.joinToString()}", missing.isEmpty())
    }

    /** Every colour role is on the catalogue's sheets; the ember light's two ends in its swatch. */
    @Test
    fun theCatalogueShowsEveryColourRole() {
        val shown = kotlinSources(catalog).joinToString("\n") { it.readText() }
        val roles = fieldsOf(HlColors::class.java)
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
        val sheet = catalog.resolve("CatalogScreen.kt").readText()
        val styles = fieldsOf(HlType::class.java)
        assertTrue("No type styles found", styles.isNotEmpty())
        val missing = styles.filter { !Regex("""\bt\.$it\b""").containsMatchIn(sheet) }
        assertTrue("Not on the catalogue's type sheet: ${missing.joinToString()}", missing.isEmpty())
    }

    /** The properties a token class declares, in order. */
    private fun fieldsOf(type: Class<*>): List<String> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }

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
