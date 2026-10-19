package app.houselights.ui.design

import app.houselights.testing.children
import app.houselights.testing.descendants
import app.houselights.testing.xmlRoot
import app.houselights.ui.testing.argb
import app.houselights.ui.testing.resourceArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/** Android draws the window and icon before Compose: resource colours must match the design system's. */
class ResourceTokensTest {
    private val colors = File("src/main/res/values/colors.xml")

    @Test
    fun theWindowIsTheRoom() {
        assertEquals("@color/room", argb(HouselightsColors.background), resourceColor("room"))
    }

    /** Before the app runs, the launch screen is the room with the mark alone in it (`splash_mark`). */
    @Test
    fun theLaunchScreenIsTheRoom() {
        val launch = style("Theme.Houselights.Launch")
        assertEquals("Theme.SplashScreen", launch.getAttribute("parent"))
        assertEquals("@color/room", item(launch, "windowSplashScreenBackground"))
        assertEquals("@drawable/splash_mark", item(launch, "windowSplashScreenAnimatedIcon"))
        assertEquals("@style/Theme.Houselights", item(launch, "postSplashScreenTheme"))
        val activity = xmlRoot(File("src/main/AndroidManifest.xml")).descendants("activity")
            .single { it.getAttribute("android:name") == ".MainActivity" }
        assertEquals("@style/Theme.Houselights.Launch", activity.getAttribute("android:theme"))
    }

    private fun style(name: String): Element =
        xmlRoot(File("src/main/res/values/themes.xml")).children("style").single { it.getAttribute("name") == name }

    private fun item(style: Element, name: String): String =
        style.children("item").single { it.getAttribute("name") == name }.textContent.trim()

    /** A colour resource as #AARRGGBB. */
    private fun resourceColor(name: String): String =
        resourceArgb(xmlRoot(colors).children("color").single { it.getAttribute("name") == name }.textContent)
}
