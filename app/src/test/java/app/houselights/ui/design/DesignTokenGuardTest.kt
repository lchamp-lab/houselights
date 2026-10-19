package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails on a screen that hard-codes a colour, size or font instead of `ui/design`'s tokens and components. */
class DesignTokenGuardTest {
    private val sources = File("src/main/java")
    private val ui = sources.resolve("app/houselights/ui")

    /** What a screen may not write itself, and what to use instead. */
    private val rules = listOf(
        Regex("""(?<![\w.])(?!0\.dp\b)\d+(\.\d+)?\.dp\b""") to "a size: use HlSpace, HlSize or HlLayout",
        Regex("""(?<![\w.])\d+(\.\d+)?\.sp\b""") to "a text size: use an Hl.type style",
        Regex("""\b(fontSize|lineHeight|fontFamily|fontWeight|letterSpacing)\s*=""") to
            "a text style override: use or add an Hl.type style",
        Regex("""FontFamily\.""") to "a font: use HlFonts through Hl.type",
        Regex("""Color\(0x""") to "a colour: use Hl.colors",
        Regex("""Color\.(Black|White)\b""") to "black or white: use Hl.colors.background or content",
        Regex("""\.copy\(alpha\s*=\s*\d""") to "an opacity: use HlAlpha",
        Regex("""RoundedCornerShape\(""") to "a shape: use HlShapes",
        // A sum of tokens is an alignment worked out in a screen: it gets a name in the design system.
        Regex("""\bHl(Space|Size|Layout|Inset)\.[a-z]\w*\s*[-+*/](?!>)""") to "arithmetic on a token: name the measure in Dimens.kt",
        Regex("""[\w)]\s*[-+*/]\s*Hl(Space|Size|Layout|Inset)\.[a-z]""") to "arithmetic on a token: name the measure in Dimens.kt",
        // A raw image is left blank when it fails: the design system's show the title or an empty frame.
        Regex("""\bAsyncImage\(""") to "an image: use ArtworkImage, PosterThumb or RowPreview",
        // Ember is a closed list of states: a screen says the state, the component lights it.
        Regex("""\.ember\b""") to "ember: say the state (HlStatus, AttentionNote, lit, valueLit, detailLit)",
    )

    /** Deliberate exceptions, as "path:fragment of the line"; a value used twice belongs in the design system. */
    private val allowed = listOf(
        // The catalogue's swatch of the colour itself.
        "catalog/CatalogScreen.kt:\"ember\" to c.ember",
        // The catalogue's own frames (swatches, a mark's white ground): a size no screen uses.
        "catalog/CatalogParts.kt:val CatalogSwatch = 52.dp",
    )

    @Test
    fun screensUseTheDesignSystem() {
        val design = ui.resolve("design")
        val violations = kotlinSources(ui).filter { !it.startsWith(design) }
            .flatMap { file ->
                val path = file.relativeTo(ui).path
                file.readLines().mapIndexedNotNull { i, line ->
                    val code = line.trimStart()
                    if (code.startsWith("import ") || code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
                    val rule = rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(line) } ?: return@mapIndexedNotNull null
                    if (allowed.any { it.allows(path, line) }) return@mapIndexedNotNull null
                    "$path:${i + 1}: ${rule.second}\n    ${line.trim()}"
                }
            }
        assertTrue("Screens go around the design system:\n" + violations.joinToString("\n"), violations.isEmpty())
        val stale = allowed.filter { exception ->
            val path = exception.substringBefore(':')
            val file = ui.resolve(path)
            !file.isFile || file.readLines().none { line -> exception.allows(path, line) && rules.any { it.first.containsMatchIn(line) } }
        }
        assertTrue("Exceptions for lines that no longer break a rule: ${stale.joinToString()}", stale.isEmpty())
    }

    /** Whether this exception, "path:fragment", covers [line] of the file at [path]. */
    private fun String.allows(path: String, line: String) = substringBefore(':') == path && line.contains(substringAfter(':'))

    /** A message goes where the action was, in the app's own voice: never in a system Toast. */
    @Test
    fun noToast() {
        val toast = Regex("""\bandroid\.widget\.Toast\b""")
        val found = kotlinSources(sources).filter { toast.containsMatchIn(it.readText()) }.map { it.relativeTo(sources).path }
        assertTrue("Toast in: ${found.joinToString()}", found.isEmpty())
    }

    /** One icon family (`HlIcons`) everywhere; Play alone comes from Material, filled, as on a remote. */
    @Test
    fun oneFamilyOfIcons() {
        val found = kotlinSources(sources).linesWhere(sources) { materialIcon.containsMatchIn(it) }
        assertTrue("Material icons, draw them in HlIcons:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A Material icon other than Play, used or imported. */
    private val materialIcon = Regex(
        """\bIcons\.(Filled|Default|Outlined|Rounded|Sharp|TwoTone|AutoMirrored)\.(?!PlayArrow\b)""" +
            """|material\.icons\.(filled|outlined|rounded|sharp|twotone|automirrored)\.(?!PlayArrow\b)""",
    )

    @Test
    fun theGuardCatchesWhatItShould() {
        val samples = listOf(
            "Modifier.padding(12.dp)", "fontSize = 13.sp", "Color(0xFF000000)", "Color.Black",
            "c.surface.copy(alpha = 0.7f)", "RoundedCornerShape(4.dp)", "FontFamily.Monospace",
            "padding(end = HlSpace.safeH - HlSpace.m)", "width = HlSize.posterThumb + HlSpace.xs", "HlSize.poster * 1.3f",
            "AsyncImage(model = url, contentDescription = null)", "color = c.ember", "tint = Hl.colors.ember",
            "fontWeight = FontWeight.Bold", "letterSpacing = style.letterSpacing * 2",
        )
        samples.forEach { sample -> assertTrue(sample, rules.any { it.first.containsMatchIn(sample) }) }
        listOf("Icons.Filled.Check", "Icons.Default.Settings", "import androidx.compose.material.icons.filled.Star")
            .forEach { assertTrue(it, materialIcon.containsMatchIn(it)) }
        listOf("Icons.Filled.PlayArrow", "import androidx.compose.material.icons.filled.PlayArrow", "HlIcons.Check")
            .forEach { assertTrue(it, !materialIcon.containsMatchIn(it)) }
        val fine = listOf(
            "Modifier.padding(0.dp)", "screenWidthDp.dp", "HlSpace.l", "Hl.colors.content.copy(alpha = HlAlpha.FILL_FAINT)",
            "{ HlSpace.m -> it }", "HlLayout.detailHeroMin(screenHeight)", "spacedBy(HlSpace.xs)",
        )
        fine.forEach { sample -> assertTrue(sample, rules.none { it.first.containsMatchIn(sample) }) }
    }
}
