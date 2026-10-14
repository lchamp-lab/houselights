package app.houselights.ui.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DESIGN.md › Writing, checked on the resources: the French interface uses typographic apostrophes,
 * one-character ellipses, words instead of arrows and no-break spaces where French puts them; and
 * it says everything the English one does.
 */
class StringsTest {
    private val english = File("src/main/res/values/strings.xml")
    private val french = File("src/main/res/values-fr/strings.xml")

    @Test
    fun frenchSaysEverythingEnglishDoes() {
        assertTrue("Resources not found from ${File(".").absolutePath}", english.isFile && french.isFile)
        val translatable = resources(english).filter { it.getAttribute("translatable") != "false" }.map { it.key }.toSet()
        val translated = resources(french).map { it.key }.toSet()
        assertEquals("Missing in values-fr", emptySet<String>(), translatable - translated)
        assertEquals("Only in values-fr", emptySet<String>(), translated - translatable)
    }

    @Test
    fun frenchIsTypeset() {
        val found = resources(french).flatMap { resource ->
            texts(resource).flatMap { text -> problems(text).map { "${resource.key}: $it\n    $text" } }
        }
        assertTrue("French typography:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theRulesCatchWhatTheyShould() {
        listOf("l'instant", "Lecture...", "aucune → flux", "Attention : danger", "Prêt!", "« fixer »", "«${NB}fixer »").forEach {
            assertTrue(it, problems(it).isNotEmpty())
        }
        listOf("l’instant", "Lecture…", "Attention${NB}: danger${NB}!", "«${NB}fixer${NB}»", "2,39:1", "https://themoviedb.org").forEach {
            assertEquals(it, emptyList<String>(), problems(it))
        }
    }

    /** What breaks French typography in [text]. */
    private fun problems(text: String): List<String> = buildList {
        if ('\'' in text) add("a straight apostrophe: ’")
        if ("..." in text) add("three dots: …")
        if ('→' in text) add("an arrow in a sentence: words")
        text.forEachIndexed { i, ch ->
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + 1)
            when {
                ch in ":;!?" && before != NB && !ratio(text, i) && !text.startsWith("://", i) -> add("no no-break space before $ch")
                ch == '«' && after != NB -> add("no no-break space after «")
                ch == '»' && before != NB -> add("no no-break space before »")
            }
        }
    }

    /** "2,39:1" is a ratio, not a colon. */
    private fun ratio(text: String, i: Int) =
        text[i] == ':' && text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true

    private val Element.key get() = "${tagName}/${getAttribute("name")}"

    /** The strings, plurals and arrays of a resource file. */
    private fun resources(file: File): List<Element> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName in setOf("string", "plurals", "string-array") }
    }

    private fun texts(resource: Element): List<String> =
        if (resource.tagName == "string") {
            listOf(resource.textContent)
        } else {
            resource.getElementsByTagName("item").let { items -> (0 until items.length).map { items.item(it).textContent } }
        }

    private companion object {
        const val NB = Typography.nbsp
    }
}
