package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fails on a screen that hard-codes a colour, size or font instead of `ui/design`'s tokens and components, and on a
 * component that sets type of its own instead of `Type.kt`'s styles.
 */
class DesignTokenGuardTest {
    private val sources = File("src/main/java")
    private val ui = sources.resolve("app/houselights/ui")

    /** What neither a screen nor a component may write itself: type is made in `Type.kt` only. */
    private val typeRules = listOf(
        Regex("""(?<![\w.])\d+(\.\d+)?\.(sp|em)\b""") to "a text size or tracking: use an Hl.type style",
        Regex("""\b(fontSize|lineHeight|lineHeightStyle|fontFamily|fontWeight|letterSpacing)\s*=|\bTextStyle\(""") to
            "a text style of its own: use or add an Hl.type style",
        Regex("""FontFamily\.""") to "a font: use HlFonts through Hl.type",
    )

    /** What a screen may not write itself, and what to use instead. */
    private val rules = typeRules + listOf(
        Regex("""(?<![\w.])(?!0\.dp\b)\d+(\.\d+)?\.dp\b|\bDp\(\s*\d""") to "a size: use HlSpace, HlSize or HlLayout",
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
        Regex("""\bAsyncImage\(""") to "an image: use ArtworkImage or PosterThumb",
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

    /** A component's deliberate exceptions to [typeRules], as [allowed]'s are. */
    private val allowedInComponents = listOf(
        // An empty field is one line of its glyphs tall, without the line height the style adds.
        "design/components/TextField.kt:textStyle.copy(lineHeight = TextUnit.Unspecified)",
    )

    @Test
    fun screensUseTheDesignSystem() {
        val design = ui.resolve("design")
        val violations = breaches(kotlinSources(ui).filter { !it.startsWith(design) }, rules, allowed)
        assertTrue("Screens go around the design system:\n" + violations.joinToString("\n"), violations.isEmpty())
        val stale = stale(allowed, rules)
        assertTrue("Exceptions for lines that no longer break a rule: ${stale.joinToString()}", stale.isEmpty())
    }

    @Test
    fun componentsTakeTheirTypeFromTheScale() {
        val violations = breaches(kotlinSources(ui.resolve("design/components")), typeRules, allowedInComponents)
        assertTrue("Components set type of their own:\n" + violations.joinToString("\n"), violations.isEmpty())
        val stale = stale(allowedInComponents, typeRules)
        assertTrue("Exceptions for lines that no longer break a rule: ${stale.joinToString()}", stale.isEmpty())
    }

    /** The lines of [files] that break one of [rules], unless [exceptions] allow them; comments and imports aside. */
    private fun breaches(files: List<File>, rules: List<Pair<Regex, String>>, exceptions: List<String>) = files.flatMap { file ->
        val path = file.relativeTo(ui).path
        file.readLines().mapIndexedNotNull { i, line ->
            val code = line.trimStart()
            if (code.startsWith("import ") || code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
            val rule = rules.firstOrNull { (pattern, _) -> pattern.containsMatchIn(line) } ?: return@mapIndexedNotNull null
            if (exceptions.any { it.allows(path, line) }) return@mapIndexedNotNull null
            "$path:${i + 1}: ${rule.second}\n    ${line.trim()}"
        }
    }

    /** The [exceptions] whose line no longer breaks one of [rules]. */
    private fun stale(exceptions: List<String>, rules: List<Pair<Regex, String>>) = exceptions.filter { exception ->
        val path = exception.substringBefore(':')
        val file = ui.resolve(path)
        !file.isFile || file.readLines().none { line -> exception.allows(path, line) && rules.any { it.first.containsMatchIn(line) } }
    }

    /** Where 14 sp is read outside the catalogue, each time beside a larger line it completes (DESIGN.md, Type). */
    private val captions = mapOf(
        "design/components/Cards.kt" to "a card's facts under its name, a role under a name, a row's count beside its title",
        "design/components/Hero.kt" to "time left beside the hero's progress, under its title",
        "design/components/Meta.kt" to "a case back's names beside its formats",
        "design/components/Picker.kt" to "an artwork choice's facts under its name",
        "design/components/ScanProgress.kt" to "the file under a counter's label",
        "home/HomeScreen.kt" to "TMDB's note in home's hero band, whose room is counted",
    )

    /** What is read keeps to 16 sp: `caption` only where [captions] says. */
    @Test
    fun whatIsReadKeepsToTheFloor() {
        val caption = Regex("""\btype\.caption\b""")
        val catalogue = ui.resolve("catalog")
        val set = kotlinSources(ui).filter { !it.startsWith(catalogue) }
            .filter { file -> file.readLines().any { caption.containsMatchIn(it) } }
            .map { it.relativeTo(ui).path }.toSortedSet()
        assertEquals("Files setting text in caption", captions.keys.toSortedSet(), set)
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
        val lit = Regex("""(?<!fun )\bScreenTitle\(""")
        // The catalogue shows both titles.
        val elsewhere = listOf(ui.resolve("design"), ui.resolve("catalog"))
        val titled = kotlinSources(ui).filter { file -> elsewhere.none { file.startsWith(it) } }
            .filter { file -> file.readLines().any { !it.trimStart().startsWith("import ") && lit.containsMatchIn(it) } }
            .map { it.relativeTo(ui).path }.toSortedSet()
        assertEquals("Screens titled as a place", places.keys.toSortedSet(), titled)
        // One place per screen, a wall's whatever it shows.
        val twice = titled.filter { path -> lit.findAll(ui.resolve(path).readText()).count() > 1 }
        assertTrue("A screen titled as more than one place: ${twice.joinToString()}", twice.isEmpty())
        // The design system lights no title of its own.
        val components = ui.resolve("design/components")
        val lighting = kotlinSources(components).filter { file -> file.readLines().any { lit.containsMatchIn(it) } }.map { it.name }
        assertEquals(emptyList<String>(), lighting)
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
            "code.copy(lineHeightStyle = LineHeightStyle(alignment, trim))",
        )
        listOf("12.sp", "0.06.em", "lineHeight = 20.sp", "lineHeightStyle = trimmed", "TextStyle(fontSize = size)", "FontFamily.Serif")
            .forEach { sample -> assertTrue(sample, typeRules.any { it.first.containsMatchIn(sample) }) }
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
