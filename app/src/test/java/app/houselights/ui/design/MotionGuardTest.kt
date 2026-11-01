package app.houselights.ui.design

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import app.houselights.testing.argumentsFrom
import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Motion moves on the system's own curves (`HlMotion`): no Material curve, no curve or spring drawn outside `Motion.kt`,
 * no animation or scroll left to a default, and screens cut: nothing cross-fades a screen's content.
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

    @Test
    fun noMaterialCurve() {
        val found = kotlinSources(main).linesWhere(main) { material.containsMatchIn(it) }
        assertTrue("Material curves, use HlMotion's:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun curvesAreDrawnInMotionOnly() {
        val found = kotlinSources(main).filter { it.name != motion }.linesWhere(main) { curve.containsMatchIn(it) }
        assertTrue("A curve of its own, name it in HlMotion:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun everyTweenNamesItsEasing() {
        val found = kotlinSources(main).linesWhere(main) { tween.containsMatchIn(it) && "easing" !in it }
        assertTrue("A tween on Material's default curve:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun nothingMovesOnADefault() {
        val found = kotlinSources(main).filter { it.name != motion }.linesWhere(main) { defaulted.containsMatchIn(it) }
        assertTrue("A motion of its own, use a tween of HlMotion:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** An animatable's or a scroll's move to a target: on Compose's own spring unless its spec comes second. */
    private val moving = Regex("""(?<!fun )\banimate(To|ScrollBy|ScrollTo)\(""")

    /** Moves on Compose's own spring and takes no spec: `scrollHome` goes back to a list's top. */
    private val toItem = Regex("""\banimateScrollToItem\(""")

    /** Every move to a target names its spec, every scroll [HlMotion.scroll]. */
    @Test
    fun everyMoveNamesItsSpec() {
        val found = kotlinSources(main).flatMap { file ->
            val bare = moving.findAll(file.code).filter { !movesOnItsSpec(file.code, it) }
            (bare + toItem.findAll(file.code)).map { "${file.pathFrom(main)}:${file.lineOf(it.range.first)}" }.toList()
        }
        val message = "A move on a default spring, name its spec (HlMotion.scroll for a scroll):\n"
        assertTrue(message + found.joinToString("\n"), found.isEmpty())
    }

    private fun movesOnItsSpec(text: String, call: MatchResult): Boolean {
        val arguments = text.argumentsFrom(call.range.last)
        return arguments.any { named.find(it)?.groupValues?.get(1) == "animationSpec" } ||
            arguments.count { !named.containsMatchIn(it) } >= 2
    }

    /** Focus brings an element into view on [HlMotion.scroll]: each spec of the system gives it. */
    @Test
    fun focusScrollsOnTheSystemsSpring() {
        // A supertype, set off by a space as a return type is not.
        val spec = Regex("""\s:\s*BringIntoViewSpec\s*\{""")
        val own = "override val scrollAnimationSpec: AnimationSpec<Float> get() = HlMotion.scroll"
        val found = kotlinSources(main).filter { spec.containsMatchIn(it.code) }
            .filter { file -> file.code.split(own).size - 1 != spec.findAll(file.code).count() }
            .map { it.pathFrom(main) }
        assertTrue("A BringIntoViewSpec on Compose's spring, give it HlMotion.scroll:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A scroll comes within 1 % of its target between 250 and 300 ms (DESIGN.md, Motion). */
    @Test
    fun aScrollArrivesInAQuarterSecond() {
        val spec = HlMotion.scroll.vectorize(Float.VectorConverter)
        val from = AnimationVector1D(0f)
        val to = AnimationVector1D(1f)
        val still = AnimationVector1D(0f)
        fun left(ms: Long) = 1f - spec.getValueFromNanos(ms * NANOS_PER_MS, from, to, still).value
        assertTrue("Within 1 % before 250 ms: ${left(QUARTER)}", left(QUARTER) > ARRIVED)
        assertTrue("Not within 1 % by 300 ms: ${left(LATEST)}", left(LATEST) < ARRIVED)
    }

    /** Without its spec, a value animation runs on a spring: each one names its tween. */
    @Test
    fun everyAnimationNamesItsSpec() {
        val found = kotlinSources(main).flatMap { file ->
            animation.findAll(file.code).filter { !namesItsSpec(file.code, it) }
                .map { "${file.pathFrom(main)}:${file.lineOf(it.range.first)}" }.toList()
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

    /** What arrives comes in the focus pull, what leaves falls back (DESIGN.md, Motion): focus, a plate. */
    @Test
    fun whatArrivesPullsAndWhatLeavesFallsBack() {
        assertEquals(HlMotion.focusPull, HlMotion.inOut<Float>(on = true).easing)
        assertEquals(HlMotion.fallBack, HlMotion.inOut<Float>(on = false, HlMotion.PLATE_IN, HlMotion.PLATE_OUT).easing)
    }

    @Test
    fun theGuardCatchesWhatItShould() {
        listOf("easing = FastOutSlowInEasing", "val x = LinearOutSlowInEasing").forEach { assertTrue(it, material.containsMatchIn(it)) }
        listOf("CubicBezierEasing(0.2f, 0f, 0f, 1f)", "easing = EaseInOut", "easing = EaseOutCubic", "Ease")
            .forEach { assertTrue(it, curve.containsMatchIn(it)) }
        listOf("easing = HlMotion.fallBack", "LinearEasing", "val easing: Easing").forEach { assertTrue(it, !curve.containsMatchIn(it)) }
        listOf("tween(HlMotion.PLATE_IN)", "tween<Float>(HlMotion.PLATE_OUT)").forEach {
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
            """animateFloatAsState(if (on) 1f else 0f, tween(HlMotion.PLATE_IN, easing = HlMotion.fallBack), label = "x")""",
            """animateFloatAsState(if (on) 1f else 0f, fade, label = "x")""",
            """transition.animateFloat({ tween(HlMotion.PLATE_IN, easing = HlMotion.fallBack) }, label = "x") { 1f }""",
            """transition.animateFloat(transitionSpec = { snap() }, label = "x") { 1f }""",
            """infinite.animateFloat(0f, 1f, infiniteRepeatable(tween(HlMotion.WALL_DRIFT, easing = LinearEasing)))""",
            """infinite.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = repeating, label = "x")""",
        )
        bare.forEach { sample -> assertTrue(sample, !namesItsSpec(sample, animation.find(sample)!!)) }
        spec.forEach { sample -> assertTrue(sample, namesItsSpec(sample, animation.find(sample)!!)) }
        listOf("fade.animateTo(target)", "state.animateScrollBy(by)", "scroll.animateScrollTo(0)", "animateTo(to, initialVelocity = v)")
            .forEach { assertTrue(it, !movesOnItsSpec(it, moving.find(it)!!)) }
        listOf("fade.animateTo(target, EDGE_FADE)", "state.animateScrollBy(by, HlMotion.scroll)", "animateTo(to, animationSpec = s)")
            .forEach { assertTrue(it, movesOnItsSpec(it, moving.find(it)!!)) }
        listOf("suspend fun animateTo(", "state.animateScrollToItem(0)").forEach { assertTrue(it, !moving.containsMatchIn(it)) }
        assertTrue(toItem.containsMatchIn("state.animateScrollToItem(0)"))
    }

    private companion object {
        /** An infinite transition's spec follows its initial and target values. */
        const val INFINITE_SPEC = 3
        const val NANOS_PER_MS = 1_000_000L
        const val QUARTER = 250L
        const val LATEST = 300L
        const val ARRIVED = 0.01f
    }
}
