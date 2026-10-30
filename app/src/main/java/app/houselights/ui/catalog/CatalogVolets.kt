package app.houselights.ui.catalog

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.components.SagaVoletWords
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.VoletMark
import app.houselights.ui.design.components.VoletPart
import app.houselights.ui.design.components.VoletState
import app.houselights.ui.design.components.VoletWords
import app.houselights.ui.design.components.Volets
import kotlinx.coroutines.delay

// A saga's polyptych on the app's own pictures: the banner as every poster and backdrop, a missing file as a failed one.

/** A saga's page as a polyptych (« Les volets »): three, five and nine panels, each opening as focus reaches it. */
internal fun LazyListScope.catalogVolets() {
    item {
        Group("Volets: a trilogy, the saga's panel open, numbers in 72; a film watched, one in progress, one announced and grey") {
            Specimen("focus a panel: it opens, the one left closes; held, they jump") { Polyptych(TRILOGY) }
        }
    }
    item {
        Group("Volets: five films, numbers in 44; one missing, grey with its ring, two announced with the quiet dot") {
            Specimen("the saga closes into its spine, its name upright in the numbers' size") { Polyptych(FIVE) }
        }
    }
    item {
        Group("Volets: eight films at the narrowest, numbers in 32, the spine in rowTitle; a picture failed shows its dark") {
            Specimen("the years on one line, at least 4 apart; past eight films the panels scroll") { Polyptych(EIGHT) }
        }
    }
}

/** A sample saga's film: its title, its year, what its panel shows. */
private class SampleFilm(
    val title: String,
    val year: Int,
    val mark: VoletMark = VoletMark.None,
    val grey: Boolean = false,
    val progress: Float = 0f,
    val state: VoletState? = null,
    val broken: Boolean = false,
)

/** [films] as a saga's polyptych, as wide as the safe area less a frame's reach and as tall as its page leaves it. */
@Composable
private fun Polyptych(films: List<SampleFilm>) {
    val art = shippedImage(R.drawable.banner)
    var open by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }
    // As the page does: the picture and the words once focus rests.
    LaunchedEffect(open) {
        shown = MOVING
        delay(HlMotion.SETTLE)
        shown = open
    }
    val picture = { film: SampleFilm? -> if (film?.broken == true) brokenPicture else art }
    val parts = listOf(VoletPart("saga", null, null, art, "Saga, saga", spine = "Saga")) +
        films.mapIndexed { i, film ->
            VoletPart(
                key = film.title,
                number = i + 1,
                poster = picture(film),
                backdrop = picture(film).takeUnless { film.grey && i % 2 == 0 },
                readAs = "${film.title}, film ${i + 1}, ${film.year}",
                grey = film.grey,
                mark = film.mark,
                year = "${film.year}",
                progress = film.progress,
                state = film.state?.words,
            )
        }
    Volets(
        parts,
        open = open,
        shown = shown.takeIf { it != MOVING },
        onFocus = { at -> if (at != null) open = at },
        onOpen = {},
        modifier = Modifier.fillMaxWidth().aspectRatio(POLYPTYCH_SHAPE),
    ) { i ->
        val film = films.getOrNull(i - 1)
        if (film == null) {
            SagaVoletWords(
                "Saga",
                null,
                listOf("${films.first().year} – ${films.last().year}", "${films.size} films", "5 h 21"),
                listOf("1 film watched of 2", "1 h 14 left"),
                OVERVIEW,
                onCut = {},
            )
        } else {
            VoletWords(film.title, null, null, "${film.year}".takeUnless { film.mark == VoletMark.Upcoming }, film.state, OVERVIEW)
        }
    }
}

/** No panel settled: focus is on its way. */
private const val MOVING = -1

private val brokenPicture = ImageRef("catalog-volet-broken") { "file:///nonexistent/volet.jpg" }

/** The polyptych on the canvas: 853 across the safe area less a focused frame's reach, 374.5 above the predella. */
private const val POLYPTYCH_SHAPE = 853f / 374.5f

private const val OVERVIEW = "A desert world, a family at war for it, and a son who sees what is to come: two lines at most, " +
    "cut with an ellipsis, OK opening the whole."

private val TRILOGY = listOf(
    SampleFilm("The first", 2021, mark = VoletMark.Watched, state = VoletState("Watched", StatusState.Done)),
    SampleFilm("The second", 2024, progress = 0.55f, state = VoletState("1 h 14 left")),
    SampleFilm("The third", 2026, VoletMark.Upcoming, grey = true, state = VoletState("Out 15 December 2026", StatusState.Pending)),
)

private val FIVE = listOf(
    SampleFilm("One", 2009, VoletMark.Watched, state = VoletState("Watched", StatusState.Done)),
    SampleFilm("Two", 2022, VoletMark.Absent, grey = true, state = VoletState("Not in your library", StatusState.Off)),
    SampleFilm("Three", 2025, VoletMark.New, state = VoletState("Next up", lit = true)),
    SampleFilm("Four", 2029, VoletMark.Upcoming, grey = true, state = VoletState("Out 19 December 2029", StatusState.Pending)),
    SampleFilm("Five", 2031, VoletMark.Upcoming, grey = true, state = VoletState("Announced", StatusState.Pending)),
)

private val EIGHT = listOf(
    SampleFilm("One", 1996, VoletMark.Watched),
    SampleFilm("Two", 2000, VoletMark.Absent, grey = true),
    SampleFilm("Three", 2006, VoletMark.Absent, grey = true),
    SampleFilm("Four", 2011, VoletMark.Watched),
    SampleFilm("Five", 2015, VoletMark.Held, state = VoletState("Its file is missing", StatusState.Off)),
    SampleFilm("Six", 2018, VoletMark.Watched, broken = true),
    SampleFilm("Seven", 2023, state = VoletState("Next up", lit = true)),
    SampleFilm("Eight", 2025),
)
