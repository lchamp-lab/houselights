package app.houselights.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `runCatching` catches everything, a coroutine's cancellation included: around a suspending call
 * it turns "stop" into a failure, and the work goes on. Suspending code uses [suspendRunCatching],
 * which rethrows the cancellation. A request for focus fails one way only, a requester with nothing
 * to focus: `tryRequestFocus` catches that, and lets a real error through. This test reads every
 * source and fails on a `runCatching` where those belong, so both rules hold on every build.
 */
class RunCatchingGuardTest {
    private val sources = File("src/main/java")

    /**
     * Deliberate exceptions, as "path:fragment of the line". Keep this list short and say why; a new
     * one is usually a [suspendRunCatching] or a `tryRequestFocus` instead.
     */
    private val allowed = listOf(
        // Blocking OkHttp calls only, under withContext(IO): nothing in them suspends or is cancelled.
        "app/houselights/player/ZidooStatus.kt:runCatching {",
        // Blocking socket reads and JSON only, under withContext(IO); the loop checks isActive itself.
        "app/houselights/providers/jellyfin/ServerDiscovery.kt:runCatching {",
        // A pure parse of bytes already read, in a suspend fun: nothing in it suspends.
        "app/houselights/providers/local/MediaProbe.kt:runCatching { MkvProbe.bitrates(bytes) }",
        // Not yet changed with its screen: becomes actions.tryRequestFocus().
        "app/houselights/ui/detail/DetailScreen.kt:runCatching { actions.requestFocus() }",
    )

    @Test
    fun noRunCatchingAroundSuspendingCodeOrFocus() {
        assertTrue("Sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val found = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = file.relativeTo(sources).path
                violations(file.readLines()).filterNot { (_, line) ->
                    allowed.any { it.substringBefore(':') == path && line.contains(it.substringAfter(':')) }
                }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "runCatching where it hides what it should not (suspendRunCatching, tryRequestFocus):\n" + found.joinToString("\n"),
            found.isEmpty(),
        )
    }

    /** The lines, from 1, where the guard stops [source]. */
    private fun flagged(source: String) = violations(source.trimIndent().lines()).map { it.first + 1 }

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

    /** The lines (index, text) of [lines] where a `runCatching` hides a cancellation or a focus mistake. */
    private fun violations(lines: List<String>): List<Pair<Int, String>> {
        val code = lines.map(::code)
        val suspending = suspendingLines(lines, code)
        return code.indices.filter { i ->
            runCatching.containsMatchIn(code[i]) &&
                (i in suspending || aroundFocus.containsMatchIn(code[i] + " " + code.getOrElse(i + 1) { "" }))
        }.map { it to lines[it] }
    }

    /**
     * The lines of a suspending body: what follows `suspend fun` or `withContext(` on its line, then
     * every line indented under it (the closing parenthesis of a long signature too), up to the
     * line that closes it.
     */
    private fun suspendingLines(lines: List<String>, code: List<String>): Set<Int> {
        val inside = HashSet<Int>()
        code.forEachIndexed { i, line ->
            val opening = suspendingStart.find(line) ?: return@forEachIndexed
            if (runCatching.containsMatchIn(line.substring(opening.range.last + 1))) inside += i
            val indent = indentOf(lines[i])
            inside += (i + 1 until lines.size)
                .filter { code[it].isNotBlank() }
                .takeWhile { indentOf(lines[it]) > indent || code[it].trimStart().startsWith(")") }
        }
        return inside
    }

    /** [line] without its comment: nothing of a comment line, nothing after a `//` that follows a space. */
    private fun code(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return line.replace(Regex("""\s//.*$"""), "")
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    private val runCatching = Regex("""(?<!\w)runCatching\b""")
    private val suspendingStart = Regex("""\bsuspend\s+fun\b|\bwithContext\s*\(""")
    private val aroundFocus = Regex("""(?<!\w)runCatching\s*\{[^}]*\brequestFocus\(""")
}
