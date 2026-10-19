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
