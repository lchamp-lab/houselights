package app.houselights.ui.design

import app.houselights.testing.argumentsFrom
import app.houselights.testing.inCommentAt
import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Motion moves on the system's own curves (`HlMotion`): no Material curve, no curve drawn outside `Motion.kt`,
 * no animation left to a default, and screens cut: nothing cross-fades a screen's content.
 */
class MotionGuardTest {
    private val main = File("src/main/java/app/houselights")
    private val motion = "Motion.kt"

    private val material = Regex("""\b(FastOutSlowInEasing|LinearOutSlowInEasing|FastOutLinearInEasing)\b""")

    /** A curve of its own, or one of Compose's named curves (`EaseInOut`, `EaseOutCubic`…). */
    private val curve = Regex("""\bCubicBezierEasing\b|\bEase(?!ing\b)\w*""")

    /** A tween without an easing takes Material's FastOutSlowIn. */
    private val tween = Regex("""\btween(<[^>]*>)?\(""")

    /** What moves on a default of its own: a spring, or a container that cross-fades or resizes on Material's curve. */
    private val defaulted = Regex("""\b(spring|Crossfade|AnimatedVisibility|AnimatedContent)\(|\.animateContentSize\(""")

    /** A value animation: a state's (`animateFloatAsState`), a transition's (`transition.animateFloat`). */
    private val animation = Regex("""(?<!fun )\banimate\w*AsState\(|\.animate(Float|Color|Dp|Int|Offset|Size|Rect|Value)\(""")

    private fun code(line: String) = !line.trimStart().let { it.startsWith("//") || it.startsWith("*") }

    @Test
    fun noMaterialCurve() {
        val found = kotlinSources(main).linesWhere(main) { code(it) && material.containsMatchIn(it) }
        assertTrue("Material curves, use HlMotion's:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun curvesAreDrawnInMotionOnly() {
        val found = kotlinSources(main).filter { it.name != motion }.linesWhere(main) { code(it) && curve.containsMatchIn(it) }
        assertTrue("A curve of its own, name it in HlMotion:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun everyTweenNamesItsEasing() {
        val found = kotlinSources(main).linesWhere(main) { code(it) && tween.containsMatchIn(it) && "easing" !in it }
        assertTrue("A tween on Material's default curve:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun nothingMovesOnADefault() {
        val found = kotlinSources(main).linesWhere(main) { code(it) && defaulted.containsMatchIn(it) }
        assertTrue("A motion of its own, use a tween of HlMotion:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** Without its spec, a value animation runs on a spring: each one names its tween. */
    @Test
    fun everyAnimationNamesItsSpec() {
        val found = kotlinSources(main).flatMap { file ->
            val text = file.readText()
            animation.findAll(text).filter { !text.inCommentAt(it.range.first) && !namesItsSpec(text, it) }
                .map { "${file.relativeTo(main).path}:${text.substring(0, it.range.first).count { c -> c == '\n' } + 1}" }
                .toList()
        }
        assertTrue("An animation on a default spring:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /**
     * A state's animation takes its spec second, a transition's its spec first (a lambda); an infinite
     * transition's comes third; any of them may name it.
     */
    private fun namesItsSpec(text: String, call: MatchResult): Boolean {
        val arguments = text.argumentsFrom(call.range.last)
        if (arguments.any { named.find(it)?.groupValues?.get(1) in specs }) return true
        val positional = arguments.filter { !named.containsMatchIn(it) }
        return if (call.value.endsWith("AsState(")) {
            positional.size >= 2
        } else {
            positional.firstOrNull()?.startsWith("{") == true || positional.size >= INFINITE_SPEC
        }
    }

    private val named = Regex("""^([a-zA-Z]\w*)\s*=(?!=)""")
    private val specs = setOf("animationSpec", "transitionSpec")

    @Test
    fun theGuardCatchesWhatItShould() {
        listOf("easing = FastOutSlowInEasing", "val x = LinearOutSlowInEasing").forEach { assertTrue(it, material.containsMatchIn(it)) }
        listOf("CubicBezierEasing(0.2f, 0f, 0f, 1f)", "easing = EaseInOut", "easing = EaseOutCubic", "Ease")
            .forEach { assertTrue(it, curve.containsMatchIn(it)) }
        listOf("easing = HlMotion.fallBack", "LinearEasing", "val easing: Easing").forEach { assertTrue(it, !curve.containsMatchIn(it)) }
        listOf("tween(HlMotion.FAST)", "tween<Float>(HlMotion.STANDARD)").forEach {
            assertTrue(it, tween.containsMatchIn(it) && "easing" !in it)
        }
        listOf("spring(dampingRatio = 1f)", "Crossfade(target) { }", "AnimatedVisibility(shown)", "Modifier.animateContentSize()")
            .forEach { assertTrue(it, defaulted.containsMatchIn(it)) }
        val bare = listOf(
            """animateFloatAsState(if (on) 1f else 0f, label = "x")""",
            """transition.animateFloat(label = "x") { if (it) 1f else 0f }""",
            """animateColorAsState(target)""",
        )
        val spec = listOf(
            """animateFloatAsState(if (on) 1f else 0f, tween(HlMotion.FAST, easing = HlMotion.fallBack), label = "x")""",
            """animateFloatAsState(if (on) 1f else 0f, fade, label = "x")""",
            """transition.animateFloat({ tween(HlMotion.FAST, easing = HlMotion.fallBack) }, label = "x") { 1f }""",
            """transition.animateFloat(transitionSpec = { snap() }, label = "x") { 1f }""",
            """infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(HlMotion.WALL_DRIFT, easing = LinearEasing)))""",
            """infinite.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = repeating, label = "x")""",
        )
        bare.forEach { sample -> assertTrue(sample, !namesItsSpec(sample, animation.find(sample)!!)) }
        spec.forEach { sample -> assertTrue(sample, namesItsSpec(sample, animation.find(sample)!!)) }
    }

    private companion object {
        /** An infinite transition's spec follows its initial and target values. */
        const val INFINITE_SPEC = 3
    }
}
