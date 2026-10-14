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

    /** Information: a circled i. */
    val Info = icon("Info") {
        moveTo(20.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, true, true, 20.5f, 12f)
        close()
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 7.75f); lineTo(12f, 8f)
    }

    /** A document: the NFO file. */
    val Document = icon("Document") {
        moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18.5f, 8f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(14f, 3.5f); lineTo(14f, 8f); lineTo(18.5f, 8f)
        moveTo(9f, 12.5f); lineTo(15.5f, 12.5f)
        moveTo(9f, 16f); lineTo(15.5f, 16f)
    }

    /** Storage: a stack of disks. */
    val Storage = icon("Storage") {
        moveTo(4.5f, 6.5f); quadTo(4.5f, 4f, 12f, 4f); quadTo(19.5f, 4f, 19.5f, 6.5f); quadTo(19.5f, 9f, 12f, 9f); quadTo(4.5f, 9f, 4.5f, 6.5f); close()
        moveTo(4.5f, 6.5f); lineTo(4.5f, 17.5f); quadTo(4.5f, 20f, 12f, 20f); quadTo(19.5f, 20f, 19.5f, 17.5f); lineTo(19.5f, 6.5f)
        moveTo(4.5f, 12f); quadTo(4.5f, 14.5f, 12f, 14.5f); quadTo(19.5f, 14.5f, 19.5f, 12f)
    }

    /** Edit: a pencil. */
    val Edit = icon("Edit") {
        moveTo(4.5f, 19.5f); lineTo(5.25f, 15.75f); lineTo(15.5f, 5.5f); quadTo(16.5f, 4.5f, 17.5f, 5.5f)
        lineTo(18.5f, 6.5f); quadTo(19.5f, 7.5f, 18.5f, 8.5f); lineTo(8.25f, 18.75f); close()
        moveTo(13.75f, 7.25f); lineTo(16.75f, 10.25f)
    }

    /** Poster: an upright picture. */
    val Poster = icon("Poster") {
        moveTo(6f, 3.5f); lineTo(18f, 3.5f); lineTo(18f, 20.5f); lineTo(6f, 20.5f); close()
        moveTo(6f, 16f); lineTo(9.5f, 12.5f); lineTo(12.5f, 15.5f); lineTo(14.5f, 13.5f); lineTo(18f, 17f)
    }

    /** Backdrop: a wide picture. */
    val Backdrop = icon("Backdrop") {
        moveTo(3f, 5.5f); lineTo(21f, 5.5f); lineTo(21f, 18.5f); lineTo(3f, 18.5f); close()
        moveTo(3f, 15f); lineTo(8f, 10.5f); lineTo(12f, 14f); lineTo(15f, 11.5f); lineTo(21f, 16.5f)
        moveTo(16.5f, 8.75f); lineTo(16.75f, 8.75f)
    }

    /** Logo: the title as type. */
    val Logo = icon("Logo") {
        moveTo(5f, 6.5f); lineTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 6.5f)
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(9f, 19f); lineTo(15f, 19f)
    }

    /** Search: a magnifying glass. */
    val Search = icon("Search") {
        moveTo(17f, 10.5f)
        arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f)
        arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
        close()
        moveTo(15.25f, 15.25f); lineTo(20f, 20f)
    }

    /** Play from the start: an arrow turning back to the top. */
    val Restart = icon("Restart") {
        moveTo(12f, 5.25f)
        arcTo(7.25f, 7.25f, 0f, true, true, 4.75f, 12.5f)
        moveTo(14.5f, 2.75f); lineTo(12f, 5.25f); lineTo(14.5f, 7.75f)
    }

    /** Opens what a row leads to. */
    val Chevron = icon("Chevron") {
        moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
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
