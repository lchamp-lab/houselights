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

/** Radii follow hierarchy, nested shapes keep parallel corners, and only a status dot is round. */
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
        // A track's options inside its well.
        assertEquals(HlShapes.wide.radius(), HlShapes.control.radius() + HlSpace.xxs.value, 0f)
    }

    @Test
    fun noPillNorDisc() {
        // Every radius is in dp: a share of the size would round a short control into a pill.
        listOf(
            HlShapes.thumb, HlShapes.badge, HlShapes.mark, HlShapes.control, HlShapes.field,
            HlShapes.row, HlShapes.artwork, HlShapes.wide, HlShapes.panel, HlShapes.sheet,
        ).forEach { assertEquals(it.radius(), it.topStart.toPx(Size(SMALL, SMALL), density), 0f) }
        val ui = File("src/main/java/app/houselights/ui")
        val round = Regex("""\bCircleShape\b|RoundedCornerShape\(\s*percent""")
        val dot = Regex("""HlSize\.(dot|newDot)|dot\.""")
        val found = kotlinSources(ui).linesWhere(ui) { line ->
            !line.trimStart().startsWith("import ") && round.containsMatchIn(line) && !dot.containsMatchIn(line)
        }
        assertTrue("Round, and not a status dot:\n" + found.joinToString("\n"), found.isEmpty())
    }

    private companion object {
        const val LARGE = 1000f
        const val SMALL = 10f
    }
}
