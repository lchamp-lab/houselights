package app.houselights.testing

import org.junit.Assert.assertTrue
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The Kotlin files under [dir], a path from the app module where Gradle runs tests; fails when [dir] is not there. */
fun kotlinSources(dir: File): List<File> {
    assertTrue("${dir.path} not found from ${File(".").absolutePath}", dir.isDirectory)
    return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}

/** The lines of these files that [flagged] picks, as "path:line: text", each path from [root]. */
fun List<File>.linesWhere(root: File, flagged: (String) -> Boolean): List<String> = flatMap { file ->
    file.readLines().mapIndexedNotNull { i, line ->
        if (flagged(line)) "${file.relativeTo(root).path}:${i + 1}: ${line.trim()}" else null
    }
}

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

/** Whether the line holding [index] is a comment or an import, so a guard reads code only. */
fun String.inCommentAt(index: Int): Boolean {
    val line = substring(lastIndexOf('\n', index) + 1).substringBefore('\n').trimStart()
    return line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") || line.startsWith("import ")
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
