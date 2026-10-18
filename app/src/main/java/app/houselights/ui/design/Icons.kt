package app.houselights.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** App icons on a 24 dp grid, 1.75 stroke, round caps and joins, tinted by the caller; only Play is Material's. */
object HlIcons {
    private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
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

    /** A film's copies: one frame, another behind it. */
    val Versions = icon("Versions") {
        moveTo(3.5f, 8f); lineTo(16f, 8f); lineTo(16f, 20.5f); lineTo(3.5f, 20.5f); close()
        moveTo(8f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 16f)
    }

    val Sort = icon("Sort") {
        moveTo(4f, 7f); lineTo(20f, 7f)
        moveTo(4f, 12f); lineTo(15f, 12f)
        moveTo(4f, 17f); lineTo(10f, 17f)
    }

    val Filter = icon("Filter") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(14f, 12.5f); lineTo(14f, 18.5f); lineTo(10f, 20f); lineTo(10f, 12.5f); close()
    }

    /** Information, and About. */
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

    val Storage = icon("Storage") {
        moveTo(4.5f, 6.5f); quadTo(4.5f, 4f, 12f, 4f); quadTo(19.5f, 4f, 19.5f, 6.5f); quadTo(19.5f, 9f, 12f, 9f); quadTo(4.5f, 9f, 4.5f, 6.5f); close()
        moveTo(4.5f, 6.5f); lineTo(4.5f, 17.5f); quadTo(4.5f, 20f, 12f, 20f); quadTo(19.5f, 20f, 19.5f, 17.5f); lineTo(19.5f, 6.5f)
        moveTo(4.5f, 12f); quadTo(4.5f, 14.5f, 12f, 14.5f); quadTo(19.5f, 14.5f, 19.5f, 12f)
    }

    /** The library in numbers: a number sign. */
    val Numbers = icon("Numbers") {
        moveTo(10.5f, 4f); lineTo(8.5f, 20f)
        moveTo(15.5f, 4f); lineTo(13.5f, 20f)
        moveTo(5f, 9f); lineTo(19.5f, 9f)
        moveTo(4.5f, 15f); lineTo(19f, 15f)
    }

    val Edit = icon("Edit") {
        moveTo(4.5f, 19.5f); lineTo(5.25f, 15.75f); lineTo(15.5f, 5.5f); quadTo(16.5f, 4.5f, 17.5f, 5.5f)
        lineTo(18.5f, 6.5f); quadTo(19.5f, 7.5f, 18.5f, 8.5f); lineTo(8.25f, 18.75f); close()
        moveTo(13.75f, 7.25f); lineTo(16.75f, 10.25f)
    }

    /** Logo: the title as type. */
    val Logo = icon("Logo") {
        moveTo(5f, 6.5f); lineTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 6.5f)
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(9f, 19f); lineTo(15f, 19f)
    }

    val Search = icon("Search") {
        moveTo(17f, 10.5f)
        arcTo(6.5f, 6.5f, 0f, true, true, 4f, 10.5f)
        arcTo(6.5f, 6.5f, 0f, true, true, 17f, 10.5f)
        close()
        moveTo(15.25f, 15.25f); lineTo(20f, 20f)
    }

    /** A check: done (watched) in projector white, your choice in ember. */
    val Check = icon("Check") {
        moveTo(5f, 12.5f); lineTo(9.75f, 17f); lineTo(19f, 7.5f)
    }

    /** Done in a toggle: [Check] cut out of a filled disc, so it reads as on whatever the tint. */
    val CheckFilled = ImageVector.Builder("CheckFilled", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(21.5f, 12f)
            arcTo(9.5f, 9.5f, 0f, true, true, 2.5f, 12f)
            arcTo(9.5f, 9.5f, 0f, true, true, 21.5f, 12f)
            close()
            moveTo(6.8f, 13.13f); lineTo(10.63f, 16.83f); lineTo(17.33f, 9.9f)
            arcTo(1.15f, 1.15f, 0f, false, false, 15.67f, 8.3f)
            lineTo(10.57f, 13.57f); lineTo(8.4f, 11.47f)
            arcTo(1.15f, 1.15f, 0f, false, false, 6.8f, 13.13f)
            close()
        }
    }.build()

    /** Play from the start: an arrow turning back to the top. */
    val Restart = icon("Restart") {
        moveTo(12f, 5.25f)
        arcTo(7.25f, 7.25f, 0f, true, true, 4.75f, 12.5f)
        moveTo(14.5f, 2.75f); lineTo(12f, 5.25f); lineTo(14.5f, 7.75f)
    }

    /** The Back key of the remote: an arrow turning back. */
    val Back = icon("Back") {
        moveTo(4.75f, 10f); lineTo(15f, 10f)
        arcTo(4.5f, 4.5f, 0f, false, true, 15f, 19f)
        lineTo(11f, 19f)
        moveTo(8.75f, 6f); lineTo(4.75f, 10f); lineTo(8.75f, 14f)
    }

    /** Opens what a row leads to. */
    val Chevron = icon("Chevron") {
        moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
    }

    /** Turns a screen to its other view: two arrows, one each way. */
    val Swap = icon("Swap") {
        moveTo(4.5f, 8f); lineTo(19.5f, 8f)
        moveTo(15.5f, 4f); lineTo(19.5f, 8f); lineTo(15.5f, 12f)
        moveTo(19.5f, 16f); lineTo(4.5f, 16f)
        moveTo(8.5f, 12f); lineTo(4.5f, 16f); lineTo(8.5f, 20f)
    }

    val Settings = icon("Settings") {
        moveTo(10.28f, 5.99f); lineTo(10.59f, 3.11f); lineTo(13.41f, 3.11f); lineTo(13.72f, 5.99f)
        arcTo(6.25f, 6.25f, 0f, false, true, 16.34f, 7.5f)
        lineTo(18.99f, 6.34f); lineTo(20.4f, 8.77f); lineTo(18.06f, 10.49f)
        arcTo(6.25f, 6.25f, 0f, false, true, 18.06f, 13.51f)
        lineTo(20.4f, 15.23f); lineTo(18.99f, 17.66f); lineTo(16.34f, 16.5f)
        arcTo(6.25f, 6.25f, 0f, false, true, 13.72f, 18.01f)
        lineTo(13.41f, 20.89f); lineTo(10.59f, 20.89f); lineTo(10.28f, 18.01f)
        arcTo(6.25f, 6.25f, 0f, false, true, 7.66f, 16.5f)
        lineTo(5.01f, 17.66f); lineTo(3.6f, 15.23f); lineTo(5.94f, 13.51f)
        arcTo(6.25f, 6.25f, 0f, false, true, 5.94f, 10.49f)
        lineTo(3.6f, 8.77f); lineTo(5.01f, 6.34f); lineTo(7.66f, 7.5f)
        arcTo(6.25f, 6.25f, 0f, false, true, 10.28f, 5.99f)
        close()
        moveTo(14.75f, 12f)
        arcTo(2.75f, 2.75f, 0f, true, true, 9.25f, 12f)
        arcTo(2.75f, 2.75f, 0f, true, true, 14.75f, 12f)
        close()
    }

    val Add = icon("Add") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }

    /** Try again: an arrow going round, the mirror of [Restart]. */
    val Refresh = icon("Refresh") {
        moveTo(12f, 5.25f)
        arcTo(7.25f, 7.25f, 0f, true, false, 19.25f, 12.5f)
        moveTo(9.5f, 2.75f); lineTo(12f, 5.25f); lineTo(9.5f, 7.75f)
    }

    /** Test a connection: a pulse, as on a monitor. */
    val Test = icon("Test") {
        moveTo(3f, 12.5f); lineTo(7.5f, 12.5f); lineTo(9.75f, 7f); lineTo(14.25f, 18f); lineTo(16.5f, 12.5f); lineTo(21f, 12.5f)
    }

    /** Remove a line: a cross. */
    val Close = icon("Close") {
        moveTo(6.5f, 6.5f); lineTo(17.5f, 17.5f)
        moveTo(17.5f, 6.5f); lineTo(6.5f, 17.5f)
    }

    private val heart: PathBuilder.() -> Unit = {
        moveTo(12f, 19.75f)
        curveTo(7.5f, 16.5f, 3.5f, 13.25f, 3.5f, 9f)
        curveTo(3.5f, 6.5f, 5.5f, 4.5f, 8f, 4.5f)
        curveTo(9.75f, 4.5f, 11.25f, 5.5f, 12f, 7f)
        curveTo(12.75f, 5.5f, 14.25f, 4.5f, 16f, 4.5f)
        curveTo(18.5f, 4.5f, 20.5f, 6.5f, 20.5f, 9f)
        curveTo(20.5f, 13.25f, 16.5f, 16.5f, 12f, 19.75f)
        close()
    }

    /** Not a favourite: an outlined heart. */
    val Heart = icon("Heart", block = heart)

    /** A favourite: the same heart, filled. */
    val HeartFilled = icon("HeartFilled", filled = true, block = heart)

    /** A score: a five-pointed star, set in a line of facts. */
    val Star = icon("Star") {
        moveTo(12f, 3.35f); lineTo(14.41f, 9.28f); lineTo(20.8f, 9.74f); lineTo(15.9f, 13.87f); lineTo(17.44f, 20.08f)
        lineTo(12f, 16.7f); lineTo(6.56f, 20.08f); lineTo(8.1f, 13.87f); lineTo(3.2f, 9.74f); lineTo(9.59f, 9.28f)
        close()
    }

    /** Something needs attention: a circled exclamation mark, [Info] turned over. */
    val Attention = icon("Attention") {
        moveTo(20.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, true, true, 3.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, true, true, 20.5f, 12f)
        close()
        moveTo(12f, 7.5f); lineTo(12f, 13f)
        moveTo(12f, 16f); lineTo(12f, 16.25f)
    }
}
