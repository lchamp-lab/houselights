package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails on a screen that hard-codes a colour, size or font instead of `ui/design`'s tokens and components. */
class DesignTokenGuardTest {
    private val sources = File("src/main/java")
    private val ui = sources.resolve("app/houselights/ui")

    /** What a screen may not write itself, and what to use instead. */
    private val rules = listOf(
        Regex("""(?<![\w.])(?!0\.dp\b)\d+(\.\d+)?\.dp\b|\bDp\(\s*\d""") to "a size: use HlSpace, HlSize or HlLayout",
        Regex("""(?<![\w.])\d+(\.\d+)?\.(sp|em)\b""") to "a text size or tracking: use an Hl.type style",
        Regex("""\b(fontSize|lineHeight|fontFamily|fontWeight|letterSpacing)\s*=|\bTextStyle\(""") to
            "a text style of its own: use or add an Hl.type style",
        Regex("""FontFamily\.""") to "a font: use HlFonts through Hl.type",
        Regex("""(?<!\w)Color\(\s*(0x|red\s*=|[\d.])""") to "a colour: use Hl.colors",
        Regex("""Color\.(Black|White|Gray|DarkGray|LightGray|Red|Green|Blue|Yellow|Cyan|Magenta)\b""") to
            "a named colour: use Hl.colors.background or content",
        // The palette and the theme's values are read through their roles, as the theme provides them.
        Regex("""\bHlPalette\.|\bHouselights(Colors|Type)\b""") to "a raw colour or style: use Hl.colors and Hl.type",
        Regex("""\.copy\(alpha\s*=\s*\d|\balpha\s*=\s*[01]?\.\d|\.alpha\(\s*[01]?\.\d""") to "an opacity: use HlAlpha",
        Regex("""\b(RoundedCornerShape|CutCornerShape|AbsoluteRoundedCornerShape|AbsoluteCutCornerShape|GenericShape)\(""") to
            "a shape: use HlShapes",
        // Veils and lights are drawn by the design system, over what they lie on.
        Regex("""\bBrush\.\w*Gradient\(""") to "a gradient: use a veil (HlScrim) or a component's light",
        // A sum of tokens is an alignment worked out in a screen: it gets a name in the design system.
        Regex("""\bHl(Space|Size|Layout|Inset)\.[a-z]\w*\s*[-+*/](?!>)""") to "arithmetic on a token: name the measure in Dimens.kt",
        Regex("""[\w)]\s*[-+*/]\s*Hl(Space|Size|Layout|Inset)\.[a-z]""") to "arithmetic on a token: name the measure in Dimens.kt",
        // A raw image is left blank when it fails: the design system's show the title or an empty frame.
        Regex("""\bAsyncImage\(""") to "an image: use ArtworkImage, PosterThumb or RowPreview",
        // Ember is a closed list of states: a screen says the state, the component lights it.
        Regex("""\.(ember|emberFrom|emberTo|emberOnLight|onEmber)\b""") to
            "ember: say the state (HlStatus, AttentionNote, lit, valueLit, detailLit)",
        // The ember light on words or a fill is a component's: the place you are in, the main action.
        Regex("""\bember(Light|Lit)\(|\bbrush\s*=""") to "the ember light: a component lights the place or the state",
    )

    /** Deliberate exceptions, as "path:fragment of the line"; a value used twice belongs in the design system. */
    private val allowed = listOf(
        // The catalogue's swatches of the palette and of ember's roles.
        "catalog/CatalogScreen.kt: to HlPalette.",
        "catalog/CatalogScreen.kt:\"ember\" to c.ember",
        // The catalogue's own frames (swatches, a mark's white ground): a size no screen uses.
        "catalog/CatalogParts.kt:val CatalogSwatch = 52.dp",
        // The catalogue's swatch of the ember light, and the type specimen of the place you are in.
        "catalog/CatalogScreen.kt:Swatch(\"ember light\", c.emberLight())",
        "catalog/CatalogScreen.kt:t.placeCurrent.emberLit(c)",
        // The contrast board: ember's roles on what they sit on, over the lightest picture.
        "catalog/CatalogContrast.kt:val e = Embers(",
        "catalog/CatalogContrast.kt:val white = Color.White",
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

    /** The screens titled as a place, in ember (`ScreenTitle`); a task or a form is titled in white (`TaskTitle`). */
    private val places = mapOf(
        "library/LibraryScreen.kt" to "a wall: Films, Séries, Sagas",
        "settings/SettingsScreen.kt" to "Réglages",
        "search/SearchScreen.kt" to "Rechercher",
        "person/PersonScreen.kt" to "a person",
    )

    /** Ember says where you are: only a place lights its title, never a task, a form or a film. */
    @Test
    fun onlyAPlaceIsTitledInEmber() {
        val lit = Regex("""(?<!fun )\b(ScreenTitle|HlTitleSwitch)\(""")
        // The catalogue shows both titles.
        val elsewhere = listOf(ui.resolve("design"), ui.resolve("catalog"))
        val titled = kotlinSources(ui).filter { file -> elsewhere.none { file.startsWith(it) } }
            .filter { file -> file.readLines().any { !it.trimStart().startsWith("import ") && lit.containsMatchIn(it) } }
            .map { it.relativeTo(ui).path }.toSortedSet()
        assertEquals("Screens titled as a place", places.keys.toSortedSet(), titled)
        // One place per screen: a title, or on a wall with sagas the title that switches to the other view.
        val twice = titled.filter { path ->
            val text = ui.resolve(path).readText()
            listOf("ScreenTitle(", "HlTitleSwitch(").any { call -> Regex("""(?<!fun )\b${Regex.escape(call)}""").findAll(text).count() > 1 }
        }
        assertTrue("A screen titled as more than one place: ${twice.joinToString()}", twice.isEmpty())
        // In the design system, only the switch of a wall's title lights one.
        val components = ui.resolve("design/components")
        val lighting = kotlinSources(components).filter { file -> file.readLines().any { lit.containsMatchIn(it) } }.map { it.name }
        assertEquals(listOf("Controls.kt"), lighting)
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

    /** One icon family (`HlIcons`) everywhere: Material's, which tv-material brings along, are never drawn nor declared. */
    @Test
    fun oneFamilyOfIcons() {
        val found = kotlinSources(sources).linesWhere(sources) { materialIcon.containsMatchIn(it) }
        assertTrue("Material icons, draw them in HlIcons:\n" + found.joinToString("\n"), found.isEmpty())
        val builds = listOf(File("build.gradle.kts"), File("../gradle/libs.versions.toml"))
        builds.forEach { assertTrue("${it.path} not found from ${File(".").absolutePath}", it.isFile) }
        val declared = builds.filter { "material-icons" in it.readText() || "material.icons" in it.readText() }
        assertTrue("Material icons declared in ${declared.joinToString()}", declared.isEmpty())
    }

    /** What the room is not: frosted glass, a drop shadow (DESIGN.md, Principles). */
    @Test
    fun noShadowNorFrostedGlass() {
        val found = kotlinSources(sources).linesWhere(sources) { line ->
            !line.trimStart().let { it.startsWith("//") || it.startsWith("*") } && glass.containsMatchIn(line)
        }
        assertTrue("A shadow or a blur:\n" + found.joinToString("\n"), found.isEmpty())
    }

    private val glass = Regex("""\.(shadow|blur)\(|\b(RenderEffect|BlurMaskFilter|BlurEffect)\b""")

    /** A Material icon, used or imported. */
    private val materialIcon = Regex("""\bIcons\.(Filled|Default|Outlined|Rounded|Sharp|TwoTone|AutoMirrored)\.|\bmaterial\.icons\b""")

    @Test
    fun theGuardCatchesWhatItShould() {
        val samples = listOf(
            "Modifier.padding(12.dp)", "fontSize = 13.sp", "Color(0xFF000000)", "Color.Black",
            "c.surface.copy(alpha = 0.7f)", "RoundedCornerShape(4.dp)", "FontFamily.Monospace",
            "padding(end = HlSpace.safeH - HlSpace.m)", "width = HlSize.posterThumb + HlSpace.xs", "HlSize.poster * 1.3f",
            "AsyncImage(model = url, contentDescription = null)", "color = c.ember", "tint = Hl.colors.ember",
            "fontWeight = FontWeight.Bold", "letterSpacing = style.letterSpacing * 2",
            "style = Hl.type.headline.emberLit(c)", "background(c.emberLight())", "brush = Brush.linearGradient(colors)",
            "tint = c.emberTo", "color = Hl.colors.onEmber", "HlPalette.N65", "HouselightsColors.content",
            "Color(0.5f, 0.5f, 0.5f)", "Color(red = 1f, green = 0f, blue = 0f)", "Color.Gray", "graphicsLayer { alpha = 0.5f }",
            "Modifier.alpha(0.6f)", "CutCornerShape(4.dp)", "letterSpacing(0.02.em)", "TextStyle(color = c.content)",
            "Dp(12f)", "background(Brush.verticalGradient(stops))",
        )
        samples.forEach { sample -> assertTrue(sample, rules.any { it.first.containsMatchIn(sample) }) }
        listOf("Icons.Filled.PlayArrow", "Icons.Default.Settings", "import androidx.compose.material.icons.filled.Star")
            .forEach { assertTrue(it, materialIcon.containsMatchIn(it)) }
        listOf("HlIcons.Check", "HlIcons.Play").forEach { assertTrue(it, !materialIcon.containsMatchIn(it)) }
        listOf("Modifier.shadow(8.dp)", "Modifier.blur(HlSpace.m)", "RenderEffect.createBlurEffect(r, r, mode)")
            .forEach { assertTrue(it, glass.containsMatchIn(it)) }
        val fine = listOf(
            "Modifier.padding(0.dp)", "screenWidthDp.dp", "HlSpace.l", "Hl.colors.content.copy(alpha = HlAlpha.FILL_FAINT)",
            "{ HlSpace.m -> it }", "HlLayout.detailHeroMin(screenHeight)", "spacedBy(HlSpace.xs)",
            "Modifier.alpha(if (seen) 1f else 0f)", "SolidColor(color)", "context.getColor(R.color.room)", "Color.Transparent",
        )
        fine.forEach { sample -> assertTrue(sample, rules.none { it.first.containsMatchIn(sample) }) }
    }
}
