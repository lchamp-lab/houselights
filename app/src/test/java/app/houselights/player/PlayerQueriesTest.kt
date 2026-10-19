package app.houselights.player

import app.houselights.testing.children
import app.houselights.testing.xmlRoot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File

/** On Android 11+, an intent [ZidooPlayer] sends finds no app unless the manifest's <queries> declares it. */
@RunWith(RobolectricTestRunner::class)
class PlayerQueriesTest {
    private val file = File("src/main/AndroidManifest.xml")

    /** An intent of <queries>: its action, its data's scheme, and its type. */
    private data class Query(val action: String?, val scheme: String?, val type: String?)

    @Test
    fun everyVideoThePlayerIsLookedForByIsDeclared() {
        val declared = declared()
        asked().forEach { assertTrue("$it is not in <queries>: $declared", it in declared) }
    }

    /** Nothing is declared beyond what is queried: an unused declaration would expose other apps for nothing. */
    @Test
    fun everyVideoDeclaredIsOneThePlayerIsLookedForBy() {
        val asked = asked()
        declared().forEach { assertTrue("$it is declared, never asked: $asked", it in asked) }
    }

    private fun asked() = ZidooPlayer.probes().map { Query(it.action, it.scheme, it.type) }

    private fun declared() = queries().children("intent").map { intent ->
        val data = intent.children("data").singleOrNull()
        Query(
            intent.children("action").singleOrNull()?.getAttribute("android:name"),
            data?.getAttribute("android:scheme")?.ifEmpty { null },
            data?.getAttribute("android:mimeType")?.ifEmpty { null },
        )
    }

    @Test
    fun theZ1000ProsPlayerIsDeclared() {
        val packages = queries().children("package").map { it.getAttribute("android:name") }
        assertTrue(packages.toString(), ZidooPlayer.KNOWN_PACKAGE in packages)
    }

    private fun queries(): Element = xmlRoot(file).children("queries").single()
}
