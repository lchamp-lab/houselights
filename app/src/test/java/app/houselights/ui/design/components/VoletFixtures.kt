package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlLayout

/** A saga's polyptych on the canvas: inside the safe area by a focused frame's reach, each side. */
val VOLET_SIDE = HlLayout.voletSide
val POLYPTYCH = HlLayout.CANVAS_WIDTH.dp - VOLET_SIDE * 2

/** The polyptych's height on the canvas at the default text size, above its predella, as a saga's page leaves it. */
val POLYPTYCH_HEIGHT = 370.5.dp

/** The plan of a saga of [films] and its own panel on the canvas. */
fun voletPlan(films: Int) = VoletPlan.of(films + 1, POLYPTYCH, POLYPTYCH_HEIGHT)

/** The name a panel reads as: the saga's, then its films' by number. */
fun panelName(panel: Int) = if (panel == 0) "La saga" else "Film $panel"

/** A saga [spine] of [films]: its own panel, then each film's, read by [panelName], its year, poster and mark by [film]. */
fun voletParts(films: Int, spine: String, film: (Int) -> VoletFilm): List<VoletPart> =
    listOf(VoletPart("saga", null, null, null, panelName(0), spine = spine)) +
        (1..films).map { i ->
            val (year, poster, mark) = film(i)
            VoletPart("film-$i", i, poster, null, panelName(i), mark = mark, year = year)
        }

/** What a film's panel shows of it: its year, its poster, its mark. */
data class VoletFilm(val year: String, val poster: ImageRef? = null, val mark: VoletMark = VoletMark.None)

/** [parts] laid out as a saga's page lays them, panel [open] open, focus entering on [enter]; no words under them. */
@Composable
fun VoletsOnTheCanvas(parts: List<VoletPart>, open: Int, modifier: Modifier = Modifier, enter: Int? = null) {
    Box(modifier.fillMaxSize()) {
        Volets(
            parts,
            open = open,
            shown = null,
            onFocus = {},
            onOpen = {},
            enter = enter,
            modifier = Modifier.padding(start = VOLET_SIDE, end = VOLET_SIDE, top = HlLayout.voletTop).fillMaxWidth()
                .height(POLYPTYCH_HEIGHT),
        ) {}
    }
}
