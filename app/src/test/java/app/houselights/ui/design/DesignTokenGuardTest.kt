package app.houselights.ui.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DESIGN.md says screens never hard-code a colour, a size or a font: they use the tokens and
 * components of `ui/design`. This test reads every screen and fails on the first value that
 * goes around them, so the rule holds on every build.
 */
class DesignTokenGuardTest {
    private val ui = File("src/main/java/app/houselights/ui")

    /** What a screen may not write itself, and what to use instead. */
    private val rules = listOf(
        Regex("""(?<![\w.])(?!0\.dp\b)\d+(\.\d+)?\.dp\b""") to "a size: use HlSpace, HlSize or HlLayout",
        Regex("""(?<![\w.])\d+(\.\d+)?\.sp\b""") to "a text size: use an Hl.type style",
        Regex("""\b(fontSize|lineHeight|fontFamily)\s*=""") to "a text style override: use or add an Hl.type style",
        Regex("""FontFamily\.""") to "a font: use HlFonts through Hl.type",
        Regex("""Color\(0x""") to "a colour: use Hl.colors",
        Regex("""Color\.(Black|White)\b""") to "black or white: use Hl.colors.background or content",
        Regex("""\.copy\(alpha\s*=\s*\d""") to "an opacity: use HlAlpha",
        Regex("""RoundedCornerShape\(""") to "a shape: use HlShapes",
        // A sum of tokens is an alignment worked out in a screen: it gets a name in the design system.
        Regex("""\bHl(Space|Size|Layout|Inset)\.[a-z]\w*\s*[-+*/](?!>)""") to "arithmetic on a token: name the measure in Dimens.kt",
        Regex("""[\w)]\s*[-+*/]\s*Hl(Space|Size|Layout|Inset)\.[a-z]""") to "arithmetic on a token: name the measure in Dimens.kt",
    )

    /**
     * Deliberate exceptions, as "path:fragment of the line". Keep this list short and say why;
     * a value needed twice belongs in the design system instead.
     */
    private val allowed = emptyList<String>()

    @Test
    fun screensUseTheDesignSystem() {
        assertTrue("UI sources not found from ${File(".").absolutePath}", ui.isDirectory)
        val design = ui.resolve("design")
        val violations = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.startsWith(design) }
            .flatMap { file ->
                val path = file.relativeTo(ui).path
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trimStart()
                    if (code.startsWith("import ") || code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
                    val rule = rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(line) } ?: return@mapIndexedNotNull null
                    if (allowed.any { it.substringBefore(':') == path && line.contains(it.substringAfter(':')) }) return@mapIndexedNotNull null
                    "$path:${i + 1}: ${rule.second}\n    ${line.trim()}"
                }
            }
            .toList()
        assertTrue("Screens go around the design system:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun theGuardCatchesWhatItShould() {
        val samples = listOf(
            "Modifier.padding(12.dp)", "fontSize = 13.sp", "Color(0xFF000000)", "Color.Black",
            "c.surface.copy(alpha = 0.7f)", "RoundedCornerShape(4.dp)", "FontFamily.Monospace",
            "padding(end = HlSpace.safeH - HlSpace.m)", "width = HlSize.posterThumb + HlSpace.xs", "HlSize.poster * 1.3f",
        )
        samples.forEach { sample -> assertTrue(sample, rules.any { it.first.containsMatchIn(sample) }) }
        val fine = listOf(
            "Modifier.padding(0.dp)", "screenWidthDp.dp", "HlSpace.l", "Hl.colors.content.copy(alpha = HlAlpha.WELL)",
            "{ HlSpace.m -> it }", "screenHeight * HlLayout.DETAIL_HERO_SHARE", "spacedBy(HlSpace.xs)",
        )
        fine.forEach { sample -> assertTrue(sample, rules.none { it.first.containsMatchIn(sample) }) }
    }
}
