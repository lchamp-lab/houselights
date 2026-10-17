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
    fun frenchFormatsWhatEnglishDoes() {
        // A string read with getString(id, args) or getQuantityString(id, n, args) gets the same
        // arguments in both languages: one that French reads but English does not pass, or reads as
        // another type, throws for French users only. Plurals by quantity: French "many" says what
        // English "other" does.
        val englishFormats = resources(english).associate { it.key to formats(it) }
        val found = resources(french).flatMap { resource ->
            val expected = englishFormats[resource.key].orEmpty()
            formats(resource).mapNotNull { (quantity, specifiers) ->
                val wanted = expected[quantity] ?: expected["other"].orEmpty()
                "${resource.key} ($quantity): ${specifiers.values} instead of ${wanted.values}".takeIf { specifiers != wanted }
            }
        }
        assertTrue("Format specifiers:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun formatSpecifiersAreReadAsFormatterReadsThem() {
        assertEquals(mapOf(1 to "%s", 2 to "%d"), specifiers("%s, le %d"))
        assertEquals(mapOf(1 to "%d", 2 to "%s"), specifiers("%2\$s : %1\$d"))
        assertEquals(mapOf(1 to "%d"), specifiers("en cours, %d %%"))
        assertEquals(mapOf(1 to "%f"), specifiers("%1\$.1f GB"))
        assertEquals(emptyMap<Int, String>(), specifiers("100 %% sûr"))
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

    /** A player that changed gets no token either, Jellyfin's, which opens the account: what says so names both, in each language. */
    @Test
    fun aChangedPlayerIsToldItGetsNeitherPasswordsNorAccess() {
        val french = string(french, "player_changed")
        val english = string(english, "player_changed")
        assertTrue(french, "vos mots de passe ni vos accès" in french)
        assertTrue(english, "your passwords or your server access" in english)
    }

    private fun string(file: File, name: String) =
        resources(file).single { it.tagName == "string" && it.getAttribute("name") == name }.textContent

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

    /** The format specifiers of each text of a string or plural (by quantity), by the argument they read. */
    private fun formats(resource: Element): Map<String, Map<Int, String>> =
        if (resource.tagName == "plurals") {
            resource.getElementsByTagName("item").let { items ->
                (0 until items.length).map { items.item(it) as Element }
                    .associate { it.getAttribute("quantity") to specifiers(it.textContent) }
            }
        } else {
            texts(resource).withIndex().associate { (i, text) -> "$i" to specifiers(text) }
        }

    /** What java.util.Formatter reads in [text]: each argument's number (from 1) and the conversion reading it. */
    private fun specifiers(text: String): Map<Int, String> {
        var next = 1
        return SPECIFIER.findAll(text).filter { it.groupValues[2] !in setOf("%", "n") }.associate { match ->
            val index = match.groupValues[1].toIntOrNull() ?: next++
            index to "%" + match.groupValues[2]
        }
    }

    private fun texts(resource: Element): List<String> =
        if (resource.tagName == "string") {
            listOf(resource.textContent)
        } else {
            resource.getElementsByTagName("item").let { items -> (0 until items.length).map { items.item(it).textContent } }
        }

    private companion object {
        const val NB = Typography.nbsp

        /** %[argument$][flags][width][.precision]conversion, as java.util.Formatter writes it. */
        val SPECIFIER = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
    }
}
