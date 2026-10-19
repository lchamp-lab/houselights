package app.houselights.ui.design

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Motion moves on the system's own curves (`HlMotion`): no Material curve, and no curve drawn outside `Motion.kt`. */
class MotionGuardTest {
    private val main = File("src/main/java/app/houselights")
    private val motion = "Motion.kt"

    private val material = Regex("""\b(FastOutSlowInEasing|LinearOutSlowInEasing|FastOutLinearInEasing)\b""")
    private val curve = Regex("""\bCubicBezierEasing\b""")

    /** A tween without an easing takes Material's FastOutSlowIn. */
    private val tween = Regex("""\btween(<[^>]*>)?\(""")

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
    fun theGuardCatchesWhatItShould() {
        listOf("easing = FastOutSlowInEasing", "val x = LinearOutSlowInEasing").forEach { assertTrue(it, material.containsMatchIn(it)) }
        assertTrue(curve.containsMatchIn("CubicBezierEasing(0.2f, 0f, 0f, 1f)"))
        listOf("tween(HlMotion.FAST)", "tween<Float>(HlMotion.STANDARD)").forEach {
            assertTrue(it, tween.containsMatchIn(it) && "easing" !in it)
        }
    }
}
