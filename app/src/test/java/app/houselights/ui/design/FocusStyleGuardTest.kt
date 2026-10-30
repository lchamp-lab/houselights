package app.houselights.ui.design

import app.houselights.testing.callFrom
import app.houselights.testing.inCommentAt
import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Focus is said in one place, `FocusStyle`, in two gestures (frame, fill) and the ring, and drawn by one
 * surface, `HlFocusSurface`, whose frame a row of cards takes over (`RowFrame`), so a row holds framed cards only:
 * no component builds its own white or frame, nothing grows with focus but a saga's panel, which opens with it,
 * tv-material's focus (its scale, glow and Material timing) is never drawn, OK clicks through the surface, what takes
 * focus shows it, and `focusRestorer` is never used.
 */
class FocusStyleGuardTest {
    private val main = File("src/main/java/app/houselights")
    private val components = File("src/main/java/app/houselights/ui/design/components")
    private val home = setOf("FocusStyle.kt", "FocusSurface.kt", "RowFrame.kt")

    /** What only FocusStyle and its surface may write. */
    private val rules = listOf(
        Regex("""\bFocusLook\("""),
        Regex("""\bGesture\.(Frame|Fill)\b"""),
        Regex("""\b(c|colors|Hl\.colors)\.focus\b"""),
        Regex("""\bHlFocus\.(frame|frameGap|glow)\b"""),
    )

    private fun sources() = kotlinSources(components).filter { it.name !in home }

    @Test
    fun noComponentBuildsItsOwnFocus() {
        val found = sources().linesWhere(components) { line -> rules.any { it.containsMatchIn(line) } }
        assertTrue("Focus belongs to FocusStyle:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A screen drawing focus would be a component copied out of this guard's sight. */
    @Test
    fun noScreenDrawsFocus() {
        val ui = File("src/main/java/app/houselights/ui")
        val design = ui.resolve("design")
        val found = kotlinSources(ui).filter { !it.startsWith(design) }
            .linesWhere(ui) { "FocusStyle." in it || "HlFocusSurface(" in it }
        assertTrue("Focus is drawn by the design system's components:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun everyFocusableSurfaceTakesAGestureFromFocusStyle() {
        // A surface's arguments run to the opening of its content lambda: `) {`.
        val surface = Regex("""(?<![A-Za-z]|fun )HlFocusSurface\(""")
        val calls = sources().flatMap { file ->
            val text = file.readText()
            surface.findAll(text).map { match ->
                val end = text.indexOf(") {", match.range.last).takeIf { it > 0 } ?: text.length
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                "${file.name}:$line" to text.substring(match.range.first, end)
            }.toList()
        }
        assertTrue("No HlFocusSurface found", calls.isNotEmpty())
        val missing = calls.filter { (_, arguments) -> "look = FocusStyle." !in arguments }.map { it.first }
        assertTrue("A focusable surface without a FocusStyle gesture:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** A row's one frame is the focus of its cards: a control in a row would show focus twice, its own and the frame left behind. */
    @Test
    fun aRowHoldsOnlyFramedCards() {
        val ui = File("src/main/java/app/houselights/ui")
        val row = Regex("""(?<!fun )\bMediaRow\(""")
        val controls = listOf(
            "HlButton",
            "HlIconButton",
            "HlTextField",
            "HlSettingRow",
            "HlListItem",
            "HlNavItem",
            "HlSegmented",
            "HlMenuButton",
        )
        val control = Regex("""\b(${controls.joinToString("|")})\(""")
        val found = kotlinSources(ui).flatMap { file ->
            val text = file.readText()
            row.findAll(text).mapNotNull { call ->
                control.find(text.callFrom(call.range.last))?.let { "${file.name}: ${it.value} in a MediaRow" }
            }.toList()
        }
        assertTrue("Rows hold framed cards only:\n" + found.joinToString("\n"), found.isEmpty())
        val inside = """MediaRow(header = null) { item { HlButton("Lire", {}) } }"""
        val after = """MediaRow(header = null) { item { PosterCard(item, {}) } }""" + "\n" + """HlButton("Lire", {})"""
        assertTrue("A control inside a row is seen", control.containsMatchIn(inside.callFrom(inside.indexOf('('))))
        assertTrue("A control after a row is not", !control.containsMatchIn(after.callFrom(after.indexOf('('))))
    }

    /**
     * Nothing grows with focus: no scale in `HlFocus`, none in the UI but a menu's opening, no size animated but a saga's
     * panel opening, and a surface's light in no layer of its own (its content's clip is one, kept at rest).
     */
    @Test
    fun nothingGrows() {
        val dimens = File("src/main/java/app/houselights/ui/design/Dimens.kt").readText()
        val focus = dimens.substring(dimens.indexOf("object HlFocus {")).substringBefore("\n}")
        assertTrue("HlFocus scales:\n$focus", !Regex("(?i)scale").containsMatchIn(focus))
        val surfaces = kotlinSources(components).filter { it.name in home }
        val layered = surfaces.linesWhere(components) { !comment(it) && layer.containsMatchIn(it) }
        assertTrue("A surface's light is drawn, never in a layer of its own:\n" + layered.joinToString("\n"), layered.isEmpty())
        val ui = File("src/main/java/app/houselights/ui")
        val found = kotlinSources(ui).flatMap { file ->
            val allowed = scales[file.relativeTo(ui).path].orEmpty()
            listOf(file).linesWhere(ui) { !comment(it) && scaling.containsMatchIn(it) && it.trim() !in allowed }
        }
        assertTrue("Focus never scales what it shows:\n" + found.joinToString("\n"), found.isEmpty())
        val stale = scales.flatMap { (path, lines) ->
            val code = ui.resolve(path).takeIf { it.isFile }?.readLines()?.map { it.trim() }.orEmpty()
            lines.filter { it !in code }.map { "$path: $it" }
        }
        assertTrue("Exceptions for lines that are gone: ${stale.joinToString()}", stale.isEmpty())
        val grown = kotlinSources(ui).filter { growing(it.readText()) }.map { it.relativeTo(ui).path }.filter { it !in opensWithFocus }
        assertTrue("Nothing opens wider with focus but a saga's panel:\n" + grown.joinToString("\n"), grown.isEmpty())
        val shut = opensWithFocus.keys.filter { path -> ui.resolve(path).let { !it.isFile || !growing(it.readText()) } }
        assertTrue("Exceptions for files that no longer grow: ${shut.joinToString()}", shut.isEmpty())
    }

    /**
     * A size that grows: one animated, or children measured at the fixed sizes of a layout of its own read from an
     * `Animatable` as they are placed.
     */
    private fun growing(text: String): Boolean {
        val measuredFromAnimation = listOf(animatable, policy, fixed).all { it.containsMatchIn(text) }
        return sizeAnimation.containsMatchIn(text) || measuredFromAnimation
    }

    private val sizeAnimation = Regex("""\banimate(ContentSize|DpAsState|SizeAsState|IntSizeAsState)\b""")
    private val animatable = Regex("""\bAnimatable\(""")
    private val policy = Regex("""\bMeasurePolicy\b""")
    private val fixed = Regex("""\bConstraints\.fixed\(""")

    /**
     * What opens wider with focus, by file, and why: the one exception to « nothing grows » (DESIGN.md, Focus), a saga's
     * panel that takes focus opening as the one left closes, the total kept.
     */
    private val opensWithFocus = mapOf(
        "design/components/Volets.kt" to "a saga's panel opens with focus: its picture is cut wider, never scaled, a held arrow jumping",
    )

    /** A scale set on a layer or a modifier, or drawn; Coil's `scale(Scale.FIT)` fits a picture to its request. */
    private val scaling = Regex(
        listOf(
            """\bscale[XY]\s*=""",
            """\.scale\((?!Scale\.)""",
            """\bimport androidx\.compose\.ui\.(draw\.scale|graphics\.drawscope\.(scale|withTransform))\b""",
        ).joinToString("|"),
    )

    /** A layer of a surface's own, set on it, placed with it or recorded. */
    private val layer = Regex("""\b(graphicsLayer|placeWithLayer|placeRelativeWithLayer|rememberGraphicsLayer)\b""")

    /**
     * Lines that scale without focus, by file: a menu grows from its button as it opens, whatever has focus, and pictures
     * are resampled to the size they are shown at (core's Bitmap.scale), or a poster to the copy its door's tint is read
     * from.
     */
    private val scales = mapOf(
        "design/components/Menu.kt" to setOf("scaleX = scale", "scaleY = scale"),
        "design/components/CutPosters.kt" to setOf(
            "bitmap.scale(width, height).also { scaled = it }",
            "val small = poster.scale((poster.width / TINT_SHRINK).coerceAtLeast(1), (poster.height / TINT_SHRINK).coerceAtLeast(1))",
        ),
        "design/components/DoorFigures.kt" to setOf("return tint.scale(w, h).also {"),
    )

    /** tv-material's surfaces bring Google TV's focus (scale, glow, 300 ms Material curves): only its theme stays. */
    @Test
    fun onlyTheThemeOfTvMaterial() {
        val allowed = setOf("Text", "Icon", "LocalContentColor", "LocalTextStyle", "MaterialTheme", "darkColorScheme", "Typography")
        val use = Regex("""androidx\.tv\.material3\.([\w*]+)""")
        val found = kotlinSources(main).linesWhere(main) { line ->
            use.findAll(line).any { it.groupValues[1] !in allowed } || material.containsMatchIn(line)
        }
        assertTrue("Focus is drawn by HlFocusSurface; only tv-material's theme stays:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** Compose's Material: its components, ripples and icons. */
    private val material = Regex("""\bandroidx\.compose\.material3?\.""")

    /** OK clicks on its release, through `HlFocusSurface`: no other way to click, select or show a press. */
    @Test
    fun okClicksThroughTheSurface() {
        val found = kotlinSources(main).linesWhere(main) { line -> !comment(line) && click.containsMatchIn(line) }
        assertTrue("A click of its own, use a component on HlFocusSurface:\n" + found.joinToString("\n"), found.isEmpty())
    }

    private val click = Regex("""\.(clickable|combinedClickable|selectable|toggleable|triStateToggleable)\b|\bindication\s*=""")

    /** What takes focus shows it: a surface, or a ring of `FocusStyle` drawn by the component itself. */
    @Test
    fun whatTakesFocusShowsIt() {
        val ui = File("src/main/java/app/houselights/ui")
        val focusable = Regex("""\.focusable\(""")
        val found = kotlinSources(ui).filter { file ->
            val text = file.readText()
            val path = file.relativeTo(ui).path
            path !in unseen && focusable.findAll(text).any { !text.inCommentAt(it.range.first) } &&
                (!file.startsWith(components) || "FocusStyle." !in text)
        }.map { it.relativeTo(ui).path }
        assertTrue("Focusable without showing focus:\n" + found.joinToString("\n"), found.isEmpty())
        val stale = unseen.keys.filter { path -> ui.resolve(path).let { !it.isFile || !focusable.containsMatchIn(it.readText()) } }
        assertTrue("Exceptions for files that take no focus: ${stale.joinToString()}", stale.isEmpty())
    }

    /** Focusable without a mark of focus, and why. */
    private val unseen = mapOf(
        "design/components/Synopsis.kt" to "a sheet's whole text, the only thing in it, focused to scroll",
        "design/components/DeviceCode.kt" to
            "a code to type elsewhere, the only thing in its pane while it waits, holding focus so Back cancels",
        "catalog/CatalogParts.kt" to "a catalogue section with nothing to focus, so the remote reaches it",
    )

    /** `focusRestorer` crashes in Compose 1.7 when its list changes; children are restored by `keepChildFocus`. */
    @Test
    fun neverFocusRestorer() {
        val restorer = Regex("""\b(focusRestorer|restoreFocusedChild|saveFocusedChild)\b""")
        val found = kotlinSources(main).linesWhere(main) { line -> !comment(line) && restorer.containsMatchIn(line) }
        assertTrue("Restore focus with keepChildFocus:\n" + found.joinToString("\n"), found.isEmpty())
    }

    private fun comment(line: String) = line.trimStart().let { it.startsWith("//") || it.startsWith("*") }

    @Test
    fun theGuardsCatchWhatTheyShould() {
        listOf("import androidx.compose.material3.Button", "androidx.compose.material.ripple.rememberRipple()")
            .forEach { assertTrue(it, material.containsMatchIn(it)) }
        listOf("Modifier.clickable { }", "Modifier.combinedClickable(onClick = {})", "Modifier.selectable(true) {}", "indication = null")
            .forEach { assertTrue(it, click.containsMatchIn(it)) }
        assertTrue(!click.containsMatchIn("Modifier.selectableGroup()"))
        listOf(
            "graphicsLayer { scaleX = s }",
            "Modifier.scale(1.08f)",
            "graphicsLayer(scaleY = s)",
            "import androidx.compose.ui.draw.scale",
            "import androidx.compose.ui.graphics.drawscope.scale",
            "import androidx.compose.ui.graphics.drawscope.withTransform",
        ).forEach { assertTrue(it, scaling.containsMatchIn(it)) }
        listOf(".scale(Scale.FIT)", "display = scale(HlFonts.condensed, FontWeight.Bold, 64, 72)", "import androidx.compose.ui.draw.clip")
            .forEach { assertTrue(it, !scaling.containsMatchIn(it)) }
        listOf("Modifier.graphicsLayer { alpha = a }", "placeable.placeWithLayer(0, 0)", "val layer = rememberGraphicsLayer()")
            .forEach { assertTrue(it, layer.containsMatchIn(it)) }
        val opening = "val t = Animatable(0f)\nMeasurePolicy { m, c -> layout(0, 0) { m[0].measure(Constraints.fixed(w(t.value), h)) } }"
        listOf("Modifier.animateContentSize()", "val width by animateDpAsState(target)", opening).forEach { assertTrue(it, growing(it)) }
        listOf("val t = Animatable(0f)\nModifier.layout { m, c -> }", "MeasurePolicy { m, c -> m[0].measure(Constraints.fixed(w, h)) }")
            .forEach { assertTrue(it, !growing(it)) }
        assertTrue(!layer.containsMatchIn("Box(Modifier.clip(shape))"))
    }
}
