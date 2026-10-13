package app.houselights.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn for Houselights (24 dp grid, 1.75 stroke, round joins), for the few
 * concepts the core Material set lacks. Tinted by the caller like any icon.
 */
object HlIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** A folder on the NAS. */
    val Folder = icon("Folder") {
        moveTo(3.5f, 7f); lineTo(3.5f, 18f); quadTo(3.5f, 19f, 4.5f, 19f); lineTo(19.5f, 19f)
        quadTo(20.5f, 19f, 20.5f, 18f); lineTo(20.5f, 9f); quadTo(20.5f, 8f, 19.5f, 8f)
        lineTo(11.5f, 8f); lineTo(9.5f, 5.5f); lineTo(4.5f, 5.5f); quadTo(3.5f, 5.5f, 3.5f, 7f); close()
    }

    /** A media server. */
    val Server = icon("Server") {
        moveTo(4f, 4.5f); lineTo(20f, 4.5f); lineTo(20f, 10.5f); lineTo(4f, 10.5f); close()
        moveTo(4f, 13.5f); lineTo(20f, 13.5f); lineTo(20f, 19.5f); lineTo(4f, 19.5f); close()
        moveTo(7.5f, 7.5f); lineTo(8f, 7.5f)
        moveTo(7.5f, 16.5f); lineTo(8f, 16.5f)
    }

    /** The TV screen. */
    val Display = icon("Display") {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 16f); lineTo(3f, 16f); close()
        moveTo(9f, 19.5f); lineTo(15f, 19.5f)
        moveTo(12f, 16f); lineTo(12f, 19.5f)
    }

    /** Playback: a film frame with a play mark. */
    val Playback = icon("Playback") {
        moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close()
        moveTo(10.25f, 9f); lineTo(15f, 12f); lineTo(10.25f, 15f); close()
    }

    /** Sorting: lines of decreasing length. */
    val Sort = icon("Sort") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(15f, 12f)
        moveTo(4f, 17f); lineTo(10f, 17f)
    }

    /** Filtering: a funnel. */
    val Filter = icon("Filter") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(14f, 12.5f); lineTo(14f, 18.5f); lineTo(10f, 20f); lineTo(10f, 12.5f); close()
    }

    /** A document: the NFO file. */
    val Document = icon("Document") {
        moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18.5f, 8f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(14f, 3.5f); lineTo(14f, 8f); lineTo(18.5f, 8f)
        moveTo(9f, 12.5f); lineTo(15.5f, 12.5f)
        moveTo(9f, 16f); lineTo(15.5f, 16f)
    }

    /** About: the house light itself. */
    val Lamp = icon("Lamp") {
        moveTo(6f, 11f); quadTo(6f, 5f, 12f, 5f); quadTo(18f, 5f, 18f, 11f); close()
        moveTo(12f, 14f); lineTo(12f, 15f)
        moveTo(8f, 13.5f); lineTo(7f, 15f)
        moveTo(16f, 13.5f); lineTo(17f, 15f)
        moveTo(9f, 19f); lineTo(15f, 19f)
    }
}
