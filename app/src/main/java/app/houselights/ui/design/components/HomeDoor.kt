package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlStroke
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.edge
import app.houselights.ui.design.emberLight
import app.houselights.ui.design.textRoom

// A door of home's grid (DESIGN.md, Layout): its frame, its band and what the band says.

/** What a door's band says after its name. */
@Immutable
sealed interface DoorNote {
    /** Plain words, quiet ("Il reste 48 min"). */
    data class Words(val text: String) : DoorNote

    /** New titles, after the ember dot ("4 nouveaux"). */
    data class News(val text: String) : DoorNote

    /** A mark printed as on a case's back ("S1 É4", a count). */
    data class Mark(val text: String) : DoorNote
}

/** Along a door's band's top edge: the episodes of a season, those watched lit, or a title's progress. */
@Immutable
sealed interface DoorProgress {
    data class Episodes(val watched: List<Boolean>) : DoorProgress

    data class Seen(val fraction: Float) : DoorProgress
}

/**
 * A door of home's grid (DESIGN.md, Layout): its [picture] over a band of the room's glass holding its [icon], its
 * [name] in the poster's voice and its [note], [progress] along the band's edge. Framed with focus, it glows in its
 * picture's tone ([glow]) or across its posters' ([glowTones]); [picture] is told when it is lit.
 */
@Composable
fun HomeDoor(
    name: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: DoorNote? = null,
    progress: DoorProgress? = null,
    glow: (() -> Color)? = null,
    glowTones: (() -> List<Color>)? = null,
    picture: @Composable BoxScope.(lit: Boolean) -> Unit,
) {
    val c = Hl.colors
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(rest = c.glass, restInk = c.content, edge = BorderStroke(HlStroke.hair, c.edge)),
        shape = HlShapes.control,
        modifier = modifier,
        glow = glow,
        glowTones = glowTones,
    ) { lit ->
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) { picture(lit) }
            DoorBand(name, icon, note, progress)
        }
    }
}

/** A door's band: its edge, icon, name and note, the note dropped whole when the name leaves it no room. */
@Composable
private fun DoorBand(name: String, icon: ImageVector, note: DoorNote?, progress: DoorProgress?) {
    val c = Hl.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(textRoom(HomeGridDefaults.band))
            .background(c.glass)
            .drawBehind { drawRect(c.edge, size = Size(size.width, HlStroke.hair.toPx())) },
    ) {
        BandLine(Modifier.align(Alignment.CenterStart).padding(horizontal = HlSpace.s)) {
            Icon(icon, contentDescription = null, tint = c.content, modifier = Modifier.size(HlSize.icon))
            Text(name, style = Hl.type.cardName, color = c.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            note?.let { NoteWords(it) }
        }
        progress?.let { BandEdge(it) }
    }
}

/**
 * A band's icon, then its name, then at its end its note while the three fit; a name too long for its icon goes without
 * it. A door of one part ([doorWidth]) keeps them closer.
 */
@Composable
private fun BandLine(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val narrow = constraints.maxWidth < (doorWidth(2) - HlSpace.s * 2).roundToPx()
        val gap = (if (narrow) HomeGridDefaults.bandGapNarrow else HlSpace.xs).roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nameWidth = measurables[1].maxIntrinsicWidth(loose.maxHeight)
        val icon = measurables[0].measure(loose)
        val iconed = nameWidth + icon.width + gap <= loose.maxWidth
        val start = if (iconed) icon.width + gap else 0
        val note = measurables.getOrNull(2)
        val noteWidth = note?.maxIntrinsicWidth(loose.maxHeight) ?: 0
        // The note keeps a word's room after the name, or goes.
        val noted = note != null && start + nameWidth + gap * 2 + noteWidth <= loose.maxWidth
        val name = measurables[1].measure(loose.copy(maxWidth = (loose.maxWidth - start).coerceAtLeast(0)))
        val placedNote = if (noted) note?.measure(loose) else null
        val height = maxOf(icon.height, name.height, placedNote?.height ?: 0)
        layout(loose.maxWidth, height) {
            if (iconed) icon.placeRelative(0, (height - icon.height) / 2)
            val nameTop = (height - name.height) / 2
            name.placeRelative(start, nameTop)
            // The note on the name's baseline, as words of two sizes on one line; centred when it has none.
            placedNote?.let {
                val line = name[FirstBaseline]
                val own = it[FirstBaseline]
                val top = if (line == AlignmentLine.Unspecified || own == AlignmentLine.Unspecified) {
                    (height - it.height) / 2
                } else {
                    nameTop + line - own
                }
                it.placeRelative(loose.maxWidth - it.width, top)
            }
        }
    }
}

@Composable
private fun NoteWords(note: DoorNote) {
    val c = Hl.colors
    when (note) {
        is DoorNote.Words -> Text(note.text, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
        is DoorNote.Mark -> Text(note.text, style = Hl.type.jaquette, color = c.contentSecondary, maxLines = 1)
        is DoorNote.News -> Row(verticalAlignment = Alignment.CenterVertically) {
            // The ember dot says new; the words say how many.
            Box(Modifier.size(HlSize.dot).background(c.ember, CircleShape))
            Spacer(Modifier.width(HlSpace.xs))
            Text(note.text, style = Hl.type.meta, color = c.content, maxLines = 1)
        }
    }
}

/** The band's top edge: a season's episodes, those watched in the ember light across the whole edge, or a title's part seen. */
@Composable
private fun BoxScope.BandEdge(progress: DoorProgress) {
    val c = Hl.colors
    Spacer(
        Modifier.align(Alignment.TopStart).fillMaxWidth().height(HlStroke.line).drawWithCache {
            val light = c.emberLight(across = size.width)
            val track = c.content.copy(alpha = HlAlpha.FILL_STRONG)
            onDrawBehind {
                when (progress) {
                    is DoorProgress.Seen -> {
                        drawRect(track)
                        drawRect(light, size = Size(size.width * progress.fraction.coerceIn(0f, 1f), size.height))
                    }
                    is DoorProgress.Episodes -> {
                        val n = progress.watched.size
                        val gap = HomeGridDefaults.segmentGap.toPx()
                        val w = (size.width - gap * (n - 1)) / n
                        progress.watched.forEachIndexed { k, seen ->
                            val at = Offset(k * (w + gap), 0f)
                            if (seen) drawRect(light, at, Size(w, size.height)) else drawRect(track, at, Size(w, size.height))
                        }
                    }
                }
            }
        },
    )
}
