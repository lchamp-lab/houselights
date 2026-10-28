package app.houselights.ui.design

import app.houselights.testing.children
import app.houselights.testing.xmlRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File

/**
 * DESIGN.md, Writing, checked on the resources: typography, and every language saying what English says, whichever of a
 * language's string files says it (strings.xml, a screen's own, as « Ajouter un dossier »'s strings_flow.xml).
 */
class StringsTest {
    private val english = File("src/main/res/values")
    private val french = File("src/main/res/values-fr")

    /** Every translation, by its folder ("values-de"). */
    private val translations = File("src/main/res").listFiles().orEmpty()
        .filter { it.name.startsWith("values-") && File(it, "strings.xml").isFile }
        .sortedBy { it.name }

    @Test
    fun everyLanguageSaysEverythingEnglishDoes() {
        assertTrue("Resources not found from ${File(".").absolutePath}", File(english, "strings.xml").isFile && french in translations)
        val translatable = resources(english).filter { it.getAttribute("translatable") != "false" }.map { it.key }.toSet()
        translations.forEach { file ->
            val translated = resources(file).map { it.key }.toSet()
            assertEquals("Missing in ${file.name}", emptySet<String>(), translatable - translated)
            assertEquals("Only in ${file.name}", emptySet<String>(), translated - translatable)
        }
    }

    @Test
    fun everyLanguageFormatsWhatEnglishDoes() {
        // Format arguments must match in every language, or only its users crash; a quantity English lacks reads as "other".
        val englishFormats = resources(english).associate { it.key to formats(it) }
        val found = translations.flatMap { file ->
            resources(file).flatMap { resource ->
                val expected = englishFormats[resource.key].orEmpty()
                formats(resource).mapNotNull { (quantity, specifiers) ->
                    val wanted = expected[quantity] ?: expected["other"].orEmpty()
                    "${file.name} ${resource.key} ($quantity): ${specifiers.values} instead of ${wanted.values}"
                        .takeIf { specifiers != wanted }
                }
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

    /** English and every translation by the rules of any language; French by its own as well. */
    @Test
    fun everyLanguageIsTypeset() {
        val found = (listOf(english) + translations).flatMap { file ->
            val rules: (String) -> List<String> = if (file == french) ::problems else ::commonProblems
            resources(file).flatMap { resource ->
                texts(resource).flatMap { text -> rules(text).map { "${file.name} ${resource.key}: $it\n    $text" } }
            }
        }
        assertTrue("Typography:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theRulesCatchWhatTheyShould() {
        listOf("l'instant", "Lecture...", "aucune → flux", "Attention : danger", "Prêt!", "« fixer »", "«${NB}fixer »").forEach {
            assertTrue(it, problems(it).isNotEmpty())
        }
        listOf("l’instant", "Lecture…", "Attention$NB: danger$NB!", "«${NB}fixer$NB»", "2,39:1", "https://themoviedb.org").forEach {
            assertEquals(it, emptyList<String>(), problems(it))
        }
    }

    /**
     * A changed player gets no token either: the message names both, in English and in French, the firmware's player
     * updated (player_changed) as an app in its place (player_changed_app); neither says to clear the app's data (#58).
     */
    @Test
    fun aChangedPlayerIsToldItGetsNeitherPasswordsNorAccess() {
        listOf("player_changed", "player_changed_app").forEach { name ->
            val french = string(french, name)
            val english = string(english, name)
            assertTrue(french, "vos mots de passe ni vos accès" in french && "données" !in french)
            assertTrue(english, "your passwords or your server access" in english && "data" !in english)
        }
    }

    private fun string(file: File, name: String) =
        resources(file).single { it.tagName == "string" && it.getAttribute("name") == name }.textContent

    /** What breaks typography in [text], in any language. */
    private fun commonProblems(text: String): List<String> = buildList {
        if ('\'' in text) add("a straight apostrophe: ’")
        if ("..." in text) add("three dots: …")
        if ('→' in text) add("an arrow in a sentence: words")
    }

    /** What breaks French typography in [text]. */
    private fun problems(text: String): List<String> = buildList {
        addAll(commonProblems(text))
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

    private val Element.key get() = "$tagName/${getAttribute("name")}"

    /** The strings, plurals and arrays of a resource folder, from each of its string files. */
    private fun resources(folder: File): List<Element> =
        folder.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty().sortedBy { it.name }
            .flatMap { xmlRoot(it).children("string", "plurals", "string-array") }

    /** The format specifiers of each text of a string or plural (by quantity), by argument. */
    private fun formats(resource: Element): Map<String, Map<Int, String>> =
        if (resource.tagName == "plurals") {
            resource.children("item").associate { it.getAttribute("quantity") to specifiers(it.textContent) }
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
            resource.children("item").map { it.textContent }
        }

    private companion object {
        const val NB = Typography.nbsp

        /** %[argument$][flags][width][.precision]conversion, as java.util.Formatter writes it. */
        val SPECIFIER = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])""")
    }
}
