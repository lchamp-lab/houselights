package app.houselights.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Radii follow hierarchy, nested shapes keep parallel corners, and only dots are round. */
class ShapesTest {
    private val density = Density(1f)

    /** A shape's radius in dp at its free corner, on a surface large enough not to clamp it. */
    private fun RoundedCornerShape.radius(): Float = topStart.toPx(Size(LARGE, LARGE), density)

    @Test
    fun radiiFollowHierarchy() {
        with(HlShapes) {
            val ladder = listOf(thumb, mark, control, row, wide, panel, sheet).map { it.radius() }
            assertEquals(ladder.sorted(), ladder)
            assertEquals(ladder.distinct(), ladder)
            assertEquals(control.radius(), field.radius(), 0f)
            assertEquals(row.radius(), artwork.radius(), 0f)
        }
    }

    @Test
    fun aSheetIsSquareAgainstTheScreenEdge() {
        val sheet = HlShapes.sheet
        assertEquals(0f, sheet.topEnd.toPx(Size(LARGE, LARGE), density), 0f)
        assertEquals(0f, sheet.bottomEnd.toPx(Size(LARGE, LARGE), density), 0f)
        assertEquals(sheet.radius(), sheet.bottomStart.toPx(Size(LARGE, LARGE), density), 0f)
    }

    /** Outer radius = inner radius + gap. */
    @Test
    fun nestedShapesKeepParallelCorners() {
        // A menu's options inside it.
        assertEquals(HlShapes.panel.radius(), HlShapes.control.radius() + HlMenuDefaults.inset.value, 0f)
        // A group's rows inside its plate.
        assertEquals(HlShapes.panel.radius(), HlShapes.row.radius() + HlInset.group.value, 0f)
        // A mark in the corner of a wide image (LandscapeCard's StatusMark).
        assertEquals(HlShapes.wide.radius(), HlShapes.mark.radius() + HlSpace.xs.value, 0f)
    }

    @Test
    fun noPillNorDisc() {
        // Every radius is in dp: a share of the size would round a short control into a pill.
        listOf(
            HlShapes.thumb, HlShapes.badge, HlShapes.mark, HlShapes.control, HlShapes.field,
            HlShapes.row, HlShapes.artwork, HlShapes.wide, HlShapes.panel, HlShapes.sheet,
        ).forEach { assertEquals(it.radius(), it.topStart.toPx(Size(SMALL, SMALL), density), 0f) }
        val ui = File("src/main/java/app/houselights/ui")
        val found = kotlinSources(ui).flatMap { file ->
            // A file may name its dot once (`val dot = Modifier.size(HlSize.dot)`) and round it where it draws it.
            val namesItsDot = namedDot.containsMatchIn(file.readText())
            listOf(file).linesWhere(ui) { line ->
                !line.trimStart().startsWith("import ") && round.containsMatchIn(line) &&
                    !dot.containsMatchIn(line) && !(namesItsDot && localDot.containsMatchIn(line))
            }
        }
        assertTrue("Round, and not a status dot:\n" + found.joinToString("\n"), found.isEmpty())
    }

    /** A disc or a pill: a circle, or a radius given as a share of the size. */
    private val round = Regex("""\bCircleShape\b|RoundedCornerShape\(\s*(percent\s*=\s*)?\d+\s*[,)]""")

    /** The sizes of a status dot: a state, a new title, a row's or a door's new titles, the current place's word. */
    private val dot = Regex("""\bHlSize\.(dot|newDot|newsDot|doorDot|wordDot)\b""")
    private val namedDot = Regex("""\bval dot = Modifier\.size\(HlSize\.(dot|newDot|newsDot|doorDot|wordDot)\)""")
    private val localDot = Regex("""\bdot\.\w+\(""")

    @Test
    fun theGuardCatchesWhatItShould() {
        listOf("Modifier.clip(CircleShape)", "RoundedCornerShape(50)", "RoundedCornerShape(percent = 50)")
            .forEach { assertTrue(it, round.containsMatchIn(it)) }
        assertTrue(!round.containsMatchIn("RoundedCornerShape(4.dp)"))
        assertTrue(dot.containsMatchIn("Modifier.size(HlSize.newsDot).background(c.ember, CircleShape)"))
        assertTrue(!dot.containsMatchIn("Modifier.size(HlSize.tile).clip(CircleShape) // a dot."))
    }

    private companion object {
        const val LARGE = 1000f
        const val SMALL = 10f
    }
}
