package app.houselights.core

import app.houselights.testing.kotlinSources
import app.houselights.testing.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails on the standard library's catch-all wrapper in suspending code, where it swallows cancellation
 * ([suspendRunCatching] rethrows it), or around a focus request, where `tryRequestFocus` belongs.
 */
class RunCatchingGuardTest {
    private val sources = File("src/main/java")

    /** Deliberate exceptions, as "path:fragment of the line": keep it short and justify each. */
    private val allowed = listOf(
        // A pure parse of bytes already read, in a suspend fun: nothing in it suspends.
        "app/houselights/providers/local/MediaProbe.kt:runCatching { MkvProbe.bitrates(bytes) }",
    )

    @Test
    fun noRunCatchingAroundSuspendingCodeOrFocus() {
        val found = kotlinSources(sources).flatMap { file ->
            val path = file.pathFrom(sources)
            violations(file.code.lines()).filterNot { (_, line) ->
                allowed.any { it.substringBefore(':') == path && line.contains(it.substringAfter(':')) }
            }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }
        assertTrue(
            "runCatching where it hides what it should not (suspendRunCatching, tryRequestFocus):\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    /** The lines, from 1, where the guard stops [source]. */
    private fun flagged(source: String) = violations(source.trimIndent().withoutComments().lines()).map { it.first + 1 }

    /** In a suspend fun's body, a multi-line signature included, and in a withContext block. */
    @Test
    fun theGuardCatchesRunCatchingInSuspendingCode() {
        assertEquals(
            listOf(2),
            flagged(
                """
                suspend fun load(): String? {
                    return runCatching { api.get() }.getOrNull()
                }
                """,
            ),
        )
        assertEquals(
            listOf(5),
            flagged(
                """
                suspend fun load(
                    id: Long,
                ): String? {
                    val a = 1
                    return runCatching { api.get(id) }.getOrNull()
                }
                """,
            ),
        )
        assertEquals(
            listOf(2, 4),
            flagged(
                """
                fun read() = scope.launch {
                    withContext(Dispatchers.IO) { runCatching { file.read() } }
                    val text = withContext(Dispatchers.IO) {
                        runCatching { file.read() }.getOrNull()
                    }
                }
                """,
            ),
        )
        assertEquals(listOf(1), flagged("suspend fun ping() = withContext(Dispatchers.IO) { runCatching { http.get() }.isSuccess }"))
        // Other modifiers stand between suspend and fun (inline, operator).
        assertEquals(listOf(1), flagged("private suspend inline fun <T> load(read: () -> T) = runCatching { read() }.getOrNull()"))
    }

    /** Around a request for focus, anywhere, on its line or the next. */
    @Test
    fun theGuardCatchesRunCatchingAroundFocus() {
        assertEquals(
            listOf(1, 2),
            flagged(
                """
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                val moved = runCatching {
                    next.requestFocus()
                }
                """,
            ),
        )
    }

    /** Plain code, code after a suspend fun has ended, comments and the right helpers pass. */
    @Test
    fun theGuardLetsTheRestThrough() {
        assertEquals(
            emptyList<Int>(),
            flagged(
                """
                fun parse(text: String) = runCatching { Json.decode(text) }.getOrNull()
                suspend fun load(): String? {
                    return suspendRunCatching { api.get() }.getOrNull()
                }
                val code = runCatching { Locale(it).isO3Language }.getOrDefault(it)
                // runCatching { api.get() } in a comment
                LaunchedEffect(Unit) { first.tryRequestFocus() }
                """,
            ),
        )
    }

    /** The lines (index, text) of [code], comments blanked, where a `runCatching` hides a cancellation or a focus mistake. */
    private fun violations(code: List<String>): List<Pair<Int, String>> {
        val suspending = suspendingLines(code)
        return code.indices.filter { i ->
            runCatching.containsMatchIn(code[i]) &&
                (i in suspending || aroundFocus.containsMatchIn(code[i] + " " + code.getOrElse(i + 1) { "" }))
        }.map { it to code[it] }
    }

    /** Lines of a suspending body: from `suspend fun` or `withContext(` to its closing line, by indentation. */
    private fun suspendingLines(code: List<String>): Set<Int> {
        val inside = HashSet<Int>()
        code.forEachIndexed { i, line ->
            val opening = suspendingStart.find(line) ?: return@forEachIndexed
            if (runCatching.containsMatchIn(line.substring(opening.range.last + 1))) inside += i
            val indent = indentOf(line)
            inside += (i + 1 until code.size)
                .filter { code[it].isNotBlank() }
                .takeWhile { indentOf(code[it]) > indent || code[it].trimStart().startsWith(")") }
        }
        return inside
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    private val runCatching = Regex("""(?<!\w)runCatching\b""")
    private val suspendingStart = Regex("""\bsuspend\s+(\w+\s+)*fun\b|\bwithContext\s*\(""")
    private val aroundFocus = Regex("""(?<!\w)runCatching\s*\{[^}]*\brequestFocus\(""")
}
