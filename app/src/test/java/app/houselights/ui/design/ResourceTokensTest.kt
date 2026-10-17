package app.houselights.ui.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android draws the window, and the launcher its icon, before Compose does: in the colours of the
 * resources. They must be the design system's, or the room shows another black at launch.
 */
class ResourceTokensTest {
    private val colors = File("src/main/res/values/colors.xml")

    @Test
    fun theWindowIsTheRoom() {
        assertTrue("Resources not found from ${File(".").absolutePath}", colors.isFile)
        assertEquals("@color/room", argb(HouselightsColors.background.toArgb()), resourceColor("room"))
    }

    /**
     * The launch screen is Android's to draw, before the app runs: the room, the mark alone in it (one
     * file, `splash_mark`, which the new mark replaces), then the app's own theme, on the app's window.
     */
    @Test
    fun theLaunchScreenIsTheRoom() {
        val launch = style("Theme.Houselights.Launch")
        assertEquals("Theme.SplashScreen", launch.getAttribute("parent"))
        assertEquals("@color/room", item(launch, "windowSplashScreenBackground"))
        assertEquals("@drawable/splash_mark", item(launch, "windowSplashScreenAnimatedIcon"))
        assertEquals("@style/Theme.Houselights", item(launch, "postSplashScreenTheme"))
        assertTrue(File("src/main/res/drawable/splash_mark.xml").isFile)
        val activity = elements(File("src/main/AndroidManifest.xml"), "activity")
            .single { it.getAttribute("android:name") == ".MainActivity" }
        assertEquals("@style/Theme.Houselights.Launch", activity.getAttribute("android:theme"))
    }

    private fun style(name: String): Element =
        elements(File("src/main/res/values/themes.xml"), "style").single { it.getAttribute("name") == name }

    private fun item(style: Element, name: String): String {
        val nodes = style.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }.textContent.trim()
    }

    private fun elements(file: File, tag: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** A colour resource as #AARRGGBB, opaque when written #RRGGBB. */
    private fun resourceColor(name: String): String {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(colors).getElementsByTagName("color")
        val value = (0 until nodes.length).map { nodes.item(it) as Element }.single { it.getAttribute("name") == name }.textContent
        return "#" + value.trim().removePrefix("#").uppercase().padStart(ARGB_DIGITS, 'F')
    }

    private fun argb(color: Int) = "#%08X".format(color)

    private companion object {
        const val ARGB_DIGITS = 8
    }
}
