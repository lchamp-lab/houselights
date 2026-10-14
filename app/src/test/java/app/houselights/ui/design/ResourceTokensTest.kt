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
