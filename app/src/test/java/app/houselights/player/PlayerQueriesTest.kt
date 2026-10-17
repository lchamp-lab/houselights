package app.houselights.player

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * From Android 11, an app sees only the apps its manifest's <queries> declares: an intent [ZidooPlayer]
 * asks that is not declared there finds no app on the device, whatever Robolectric, which shows every
 * app, says. Read from the manifest as written.
 */
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

    /** No more is declared than is asked: an intent declared and never asked would show this app others for nothing. */
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

    private fun queries(): Element {
        assertTrue("Not found from ${File(".").absolutePath}", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return root.children("queries").single()
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
}
