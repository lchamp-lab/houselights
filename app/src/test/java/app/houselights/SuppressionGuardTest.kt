package app.houselights

import app.houselights.testing.kotlinSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails on a detekt finding or a compiler warning set aside with no reason: as CONTRIBUTING says, a `@Suppress` gives
 * its reason in a line, a comment after it or a comment line just above it, in the code, its tests and the build scripts.
 */
class SuppressionGuardTest {
    /** The app, its tests and the baseline profile's journeys, from the app module where Gradle runs tests. */
    private val roots = listOf(File("src/main/java"), File("src/test/java"), File("../baselineprofile/src/main/java"))

    /** The build scripts, which a compiler warning fails as well. */
    private val scripts =
        listOf("build.gradle.kts", "../build.gradle.kts", "../settings.gradle.kts", "../baselineprofile/build.gradle.kts")

    @Test
    fun everySuppressionSaysWhy() {
        // Their text as written: a reason is a comment.
        val texts = roots.flatMap(::kotlinSources).map { it.file to it.text } + scripts.map(::File).map { it to it.readText() }
        val found = texts.flatMap { (file, text) ->
            val lines = text.lines()
            bare(lines).map { "${file.path}:${it + 1}: ${lines[it].trim()}" }
        }
        assertTrue("A @Suppress with no reason in a line (CONTRIBUTING, Checks):\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A KDoc says what the code does, not why its rule is set aside; an empty comment says nothing. */
    @Test
    fun theGuardCatchesASuppressionWithNoReason() {
        assertEquals(listOf(0), bare(listOf("@Suppress(\"MagicNumber\")", "fun mask() = 3")))
        assertEquals(listOf(1), bare(listOf("/** Makes the default app. */", "    @Suppress(\"DEPRECATION\")", "    fun make() {}")))
        assertEquals(listOf(0), bare(listOf("@file:Suppress(\"TooManyFunctions\") //", "package app")))
        assertEquals(listOf(2), bare(listOf("//", "", "@Suppress(\"ReturnCount\")", "fun walk() {}")))
    }

    @Test
    fun aReasonAfterItOrJustAboveItPasses() {
        assertEquals(emptyList<Int>(), bare(listOf("@Suppress(\"MagicNumber\") // The standard's numbers.", "fun mask() = 3")))
        assertEquals(
            emptyList<Int>(),
            bare(listOf("    // An EBML walk: each element it cannot read stops it.", "    @Suppress(\"ReturnCount\")", "    fun walk()")),
        )
        assertEquals(emptyList<Int>(), bare(listOf("@SuppressLint(\"RestrictedApi\")", "val text = \"@Suppress(\"")))
    }

    /** The indices of [lines] where a `@Suppress` gives no reason, neither after it nor on a comment line just above. */
    private fun bare(lines: List<String>): List<Int> = lines.indices.filter { i ->
        val annotation = suppression.find(lines[i]) ?: return@filter false
        val after = lines[i].substring(annotation.range.last + 1).substringAfter("//", "")
        val above = lines.getOrElse(i - 1) { "" }.trimStart()
        after.isBlank() && !(above.startsWith("//") && above.removePrefix("//").isNotBlank())
    }

    private val suppression = Regex("""^\s*@(file:)?Suppress\(""")
}
