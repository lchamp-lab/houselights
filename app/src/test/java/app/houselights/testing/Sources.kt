package app.houselights.testing

import org.junit.Assert.assertTrue
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/** A Kotlin file, read once per run: sources do not change while the tests run. */
class Source(val file: File) {
    /** The file as written, comments included. */
    val text: String = file.readText()

    /** [text] with its comments blanked to spaces (line, block, KDoc), so offsets and lines hold: what a guard reads. */
    val code: String by lazy { text.withoutComments() }

    /** [code]'s lines, from line 1, blank ones left out, and imports unless [imports]. */
    fun codeLines(imports: Boolean = true): List<CodeLine> = lines.filter { (_, text) ->
        text.isNotBlank() && (imports || !text.trimStart().startsWith("import "))
    }

    /** The line, from 1, that holds [index] of [code]. */
    fun lineOf(index: Int): Int = code.substring(0, index).count { it == '\n' } + 1

    /** This file's path from [root]. */
    fun pathFrom(root: File): String = file.relativeTo(root).path

    val name: String get() = file.name

    private val lines by lazy { code.lines().mapIndexed { i, line -> CodeLine(i + 1, line) } }
}

/** A line of a [Source]'s code: its [number] from 1, its [text] without comments. */
data class CodeLine(val number: Int, val text: String)

private val sourcesByDir = ConcurrentHashMap<File, List<Source>>()
private val sourcesByFile = ConcurrentHashMap<File, Source>()

/** The Kotlin files under [dir], a path from the app module where Gradle runs tests; fails when [dir] is not there. */
fun kotlinSources(dir: File): List<Source> {
    assertTrue("${dir.path} not found from ${File(".").absolutePath}", dir.isDirectory)
    return sourcesByDir.getOrPut(dir) {
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.map(::source).toList()
    }
}

/** The Kotlin file at [file], a path from the app module; fails when it is not there. */
fun source(file: File): Source {
    assertTrue("${file.path} not found from ${File(".").absolutePath}", file.isFile)
    return sourcesByFile.getOrPut(file) { Source(file) }
}

/** The code lines of these files that [flagged] picks, as "path:line: text", each path from [root]; imports unless [imports]. */
fun List<Source>.linesWhere(root: File, imports: Boolean = true, flagged: (String) -> Boolean): List<String> = flatMap { source ->
    source.codeLines(imports).filter { flagged(it.text) }.map { "${source.pathFrom(root)}:${it.number}: ${it.text.trim()}" }
}

/**
 * This Kotlin code with its comments blanked to spaces, nested ones and KDoc included, lines and offsets kept; what
 * strings hold (a `//` in an address) stays, unless [strings]: then it is blanked too, quotes kept.
 */
fun String.withoutComments(strings: Boolean = false): String = Blanker(this, strings).blanked()

/** Reads Kotlin code once, character by character, blanking its comments, and its strings when [strings]. */
private class Blanker(private val text: String, private val strings: Boolean) {
    private val out = StringBuilder(text)

    /** What encloses the character read: a string, a raw string, or a template's code with its open braces. */
    private val enclosing = ArrayDeque<Any>()

    /** Where the outermost string open began its content. */
    private var opened = 0
    private var i = 0

    fun blanked(): String {
        while (i < text.length) {
            when (val inside = enclosing.lastOrNull()) {
                QUOTE -> inQuote()
                RAW -> inRaw()
                else -> inCode(inside as? Template)
            }
            i++
        }
        return out.toString()
    }

    private fun inQuote() {
        when {
            text[i] == '\\' -> i++
            text[i] == '"' || text[i] == '\n' -> close(QUOTE)
            text.startsWith(TEMPLATE, i) -> openTemplate()
        }
    }

    private fun inRaw() {
        when {
            text.startsWith(RAW, i) && !text.startsWith("$RAW\"", i) -> close(RAW)
            text.startsWith(TEMPLATE, i) -> openTemplate()
        }
    }

    private fun inCode(template: Template?) {
        when {
            text.startsWith("//", i) -> blankTo(text.indexOf('\n', i).let { if (it < 0) text.length else it })
            text.startsWith("/*", i) -> blankTo(text.blockCommentEnd(i))
            text.startsWith(RAW, i) -> open(RAW)
            text[i] == '"' -> open(QUOTE)
            // A character: '"' opens no string.
            text[i] == '\'' -> skipCharacter()
            template != null -> brace(template)
        }
    }

    /** Past a character literal, an escaped one included: its closing quote is after the backslash and what it escapes. */
    private fun skipCharacter() {
        val end = text.indexOf('\'', if (text.getOrNull(i + 1) == '\\') i + ESCAPED else i + 2)
        i = if (end < 0) text.length else end
    }

    /** A brace of a template's code: opening one, or closing one, the template itself at the last. */
    private fun brace(template: Template) {
        when {
            text[i] == '{' -> template.braces++
            text[i] == '}' && template.braces == 0 -> enclosing.removeLast()
            text[i] == '}' -> template.braces--
        }
    }

    private fun open(quotes: String) {
        if (enclosing.none { it is String }) opened = i + quotes.length
        enclosing.addLast(quotes)
        i += quotes.length - 1
    }

    private fun close(quotes: String) {
        enclosing.removeLast()
        if (strings && enclosing.none { it is String }) blank(opened, i)
        i += quotes.length - 1
    }

    private fun openTemplate() {
        enclosing.addLast(Template(0))
        i++
    }

    /** Blanks from here to [end], reading on from there. */
    private fun blankTo(end: Int) {
        blank(i, end)
        i = end - 1
    }

    private fun blank(from: Int, until: Int) {
        for (j in from until until) if (out[j] != '\n') out[j] = ' '
    }
}

/** The index just past the block comment opening at [open], comments nested in it included. */
private fun String.blockCommentEnd(open: Int): Int {
    var depth = 0
    var i = open
    while (i < length) {
        when {
            startsWith("/*", i) -> depth++.also { i++ }
            startsWith("*/", i) -> if (--depth == 0) return i + 2 else i++
        }
        i++
    }
    return length
}

private const val QUOTE = "\""
private const val RAW = "\"\"\""
private const val TEMPLATE = "\${"
private const val ESCAPED = 3

/** A template's code inside a string, and the braces open in it. */
private class Template(var braces: Int)

/** The call whose arguments open at [open]: its arguments, then its trailing lambda, brackets balanced. */
fun String.callFrom(open: Int): String {
    var end = closing(open, '(', ')')
    val lambda = (end + 1 until length).firstOrNull { !this[it].isWhitespace() } ?: -1
    if (lambda >= 0 && this[lambda] == '{') end = closing(lambda, '{', '}')
    return substring(open, end + 1)
}

/** The arguments of the call whose brackets open at [open], split at its own commas, each trimmed. */
fun String.argumentsFrom(open: Int): List<String> {
    val end = closing(open, '(', ')')
    val arguments = mutableListOf<String>()
    var depth = 0
    var quoted = false
    var start = open + 1
    for (i in open + 1 until end) {
        val char = this[i]
        if (char == '"' && this[i - 1] != '\\') quoted = !quoted
        if (quoted) continue
        when (char) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> depth--
            ',' -> if (depth == 0) {
                arguments += substring(start, i).trim()
                start = i + 1
            }
        }
    }
    arguments += substring(start, end).trim()
    return arguments.filter { it.isNotEmpty() }
}

private fun String.closing(open: Int, opens: Char, closes: Char): Int {
    var depth = 0
    for (i in open until length) {
        if (this[i] == opens) depth++
        if (this[i] == closes && --depth == 0) return i
    }
    return length - 1
}

/** The root element of the XML [file], a path from the app module; fails when it is not there. */
fun xmlRoot(file: File): Element {
    assertTrue("${file.path} not found from ${File(".").absolutePath}", file.isFile)
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
}

/** The child elements tagged one of [tags], in order. */
fun Element.children(vararg tags: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName in tags }

/** Every element under this one tagged [tag], at any depth, in order. */
fun Element.descendants(tag: String): List<Element> =
    getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
