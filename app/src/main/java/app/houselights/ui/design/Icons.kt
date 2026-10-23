package app.houselights.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * App icons: Lucide's (lucide.dev, ISC, third_party/lucide), their SVG paths as Lucide draws them on its 24 grid,
 * stroke 2, round caps and joins, tinted by the caller. Each names its Lucide glyph.
 */
object HlIcons {
    private const val STROKE = 2f

    /** A Lucide drawing from its SVG [paths], stroked; [filled] fills them too. */
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** A folder on the NAS (`folder`). */
    val Folder = icon(
        "Folder",
        "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z",
    )

    /** A media server (`server`). */
    val Server = icon(
        "Server",
        "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z",
        "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z",
        "M6 6L6.01 6",
        "M6 18L6.01 18",
    )

    /** The TV screen (`monitor`). */
    val Display = icon(
        "Display",
        "M4 3h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z",
        "M8 21L16 21",
        "M12 17L12 21",
    )

    /** Playback: a frame with a play mark (`square-play`). */
    val Playback = icon(
        "Playback",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2Z",
        "m9 8 6 4-6 4Z",
    )

    /** Play, filled as on a remote: the main action's glyph (`play`). */
    val Play = icon("Play", "M6 3L20 12L6 21L6 3Z", filled = true)

    /** A film's copies: one frame, another behind it (`copy`). */
    val Versions = icon(
        "Versions",
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
    )

    /** A title's audio and subtitles: a frame with lines of text (`captions`). */
    val Captions = icon(
        "Captions",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2Z",
        "M7 15h4",
        "M15 15h2",
        "M7 11h2",
        "M13 11h4",
    )

    /** Sound, a title's audio formats: a waveform's bars (`audio-lines`). */
    val Sound = icon("Sound", "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3")

    /** Sorting: lines of decreasing length, and the way they go (`arrow-down-wide-narrow`). */
    val Sort = icon("Sort", "m3 16 4 4 4-4", "M7 20V4", "M11 4h10", "M11 8h7", "M11 12h4")

    /** Filtering: a funnel (`filter`). */
    val Filter = icon("Filter", "M22 3L2 3L10 12.46L10 19L14 21L14 12.46L22 3Z")

    /** Information, and About (`info`). */
    val Info = icon("Info", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 16v-4", "M12 8h.01")

    /** A document: the NFO file (`file-text`). */
    val Document = icon(
        "Document",
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8",
        "M16 13H8",
        "M16 17H8",
    )

    /** Storage: a drive (`hard-drive`). */
    val Storage = icon(
        "Storage",
        "M22 12L2 12",
        "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
        "M6 16L6.01 16",
        "M10 16L10.01 16",
    )

    /** The library in numbers: a number sign (`hash`). */
    val Numbers = icon("Numbers", "M4 9L20 9", "M4 15L20 15", "M10 3L8 21", "M16 3L14 21")

    /** Edit: a pencil (`pencil`). */
    val Edit = icon(
        "Edit",
        "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622" +
            "l4.353-1.32a2 2 0 0 0 .83-.497z",
        "m15 5 4 4",
    )

    /** Search: a magnifying glass (`search`). */
    val Search = icon("Search", "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0Z", "m21 21-4.3-4.3")

    /** A check: done (watched) in projector white, your choice in ember (`check`). */
    val Check = icon("Check", "M20 6 9 17l-5-5")

    /**
     * Done in a toggle (`circle-check`): its disc filled, the check's stroke with its round ends cut out of it,
     * so it reads as on whatever the tint.
     */
    val CheckFilled = ImageVector.Builder("CheckFilled", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12Z" +
                    "M8.29 12.71L10.29 14.71A1 1 0 0 0 11.71 14.71L15.71 10.71A1 1 0 0 0 14.29 9.29" +
                    "L11 12.59L9.71 11.29A1 1 0 0 0 8.29 12.71Z",
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
    }.build()

    /** Play from the start: an arrow turning back (`rotate-ccw`). */
    val Restart = icon("Restart", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")

    /** The Back key of the remote: an arrow turning back (`undo-2`). */
    val Back = icon("Back", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11")

    /** Erase the last letter typed: a key with a cross (`delete`). */
    val Erase = icon(
        "Erase",
        "M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7" +
            "a2 2 0 0 0-2-2z",
        "m12 9 6 6",
        "m18 9-6 6",
    )

    /** Turns a screen to its other view: two arrows, one each way (`arrow-left-right`). */
    val Swap = icon("Swap", "M8 3 4 7l4 4", "M4 7h16", "m16 21 4-4-4-4", "M20 17H4")

    /** Settings: three faders, each at its own level (`sliders-horizontal`). */
    val Settings = icon(
        "Settings",
        "M21 4L14 4",
        "M10 4L3 4",
        "M21 12L12 12",
        "M8 12L3 12",
        "M21 20L16 20",
        "M12 20L3 20",
        "M14 2L14 6",
        "M8 10L8 14",
        "M16 18L16 22",
    )

    /** Add: a plus (`plus`). */
    val Add = icon("Add", "M5 12h14", "M12 5v14")

    /** Try again: two arrows going round (`refresh-cw`). */
    val Refresh = icon(
        "Refresh",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
        "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
        "M8 16H3v5",
    )

    /** Test a connection: a pulse, as on a monitor (`activity`). */
    val Test = icon(
        "Test",
        "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36" +
            "A2 2 0 0 1 4.49 12H2",
    )

    private const val HEART =
        "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2" +
            "A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"

    /** Not a favourite: an outlined heart (`heart`). */
    val Heart = icon("Heart", HEART)

    /** A favourite: the same heart, filled. */
    val HeartFilled = icon("HeartFilled", HEART, filled = true)

    /** A score: a five-pointed star, set in a line of facts (`star`). */
    val Star = icon(
        "Star",
        "M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26L12 2Z",
    )

    /** Something needs attention: a circled exclamation mark, [Info] turned over (`circle-alert`). */
    val Attention = icon("Attention", "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0Z", "M12 8L12 12", "M12 16L12.01 16")

    /** Watch history kept elsewhere, Trakt: a clock turning back (`history`). */
    val History = icon("History", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")

    /** The remote's Menu key: the three lines of `square-menu`, its square drawn as the key's frame ([MenuKey]). */
    val Menu = icon("Menu", "M7 8h10", "M7 12h10", "M7 16h10")
}
