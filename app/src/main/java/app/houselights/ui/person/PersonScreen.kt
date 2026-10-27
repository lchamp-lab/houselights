package app.houselights.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.houselights.R
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.components.FactRow
import app.houselights.core.Job
import app.houselights.core.MediaItem
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.components.AmbientBackdrop
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.artworkEdge
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.DetailAhead
import app.houselights.ui.design.components.HeroInfo
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.PosterWallGrid
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.design.components.WithItsPicture
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.jobName
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.valueOrNull
import app.houselights.ui.util.toPx

/**
 * A person's page: portrait, name, jobs and title count ("Réalisation  Interprétation  7 titres"), then their
 * titles by year as posters; with at most two lines of them, a hero and one row as on Home.
 */
@Composable
fun PersonScreen(
    personId: String,
    onOpenItem: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    vm: PersonViewModel = viewModel(key = "person-$personId", factory = PersonViewModel.factory(appContainer(), personId)),
) {
    ReloadOnLibraryChange { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val page = state.valueOrNull
    if (page == null) {
        // While retrying, the error stays on screen with its button busy.
        val failed = state as? LoadState.Failed
        Box(modifier.fillMaxSize().padding(top = HlLayout.errorTop)) {
            if (failed != null) {
                HlErrorState(failed.error, vm::load, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l), failed.retrying)
            }
        }
        return
    }
    PersonBands(page, onOpenItem, modifier)
}

/** Lays out [page]: its head, then its titles as a wall, or as one row when they are few ([smallPage]). */
@Composable
internal fun PersonBands(page: PersonPage, onOpenItem: (MediaItem) -> Unit, modifier: Modifier = Modifier) {
    val titles = remember(page) { page.titles.map { it.item } }
    val small = smallPage(titles.size)
    // The selected title survives navigation, defaulting to the first; it shows once focus settles.
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val shown by rememberSettled { titles.find { it.id == selectedId.value } ?: titles.firstOrNull() }
    val first = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    // On each arrival, focus returns to the last title focused, else the first.
    val memory = rememberFocusMemory()
    RestoreFocus(memory, first, ready = titles.isNotEmpty()) { key ->
        val index = FocusKey.itemId(key)?.let { id -> titles.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0 && !small) grid.scrollToItem(index)
        index >= 0
    }
    DetailAhead(shown)
    AmbientBackdrop(shown?.backdrop, if (small) BackdropStyle.Hero else BackdropStyle.Quiet, modifier) {
        Column(Modifier.fillMaxSize()) {
            PersonHead(page, Modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV))
            fun card(index: Int, item: MediaItem) = Modifier
                .focusKey(FocusKey.item(item.id), memory)
                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
            if (small) {
                Spacer(Modifier.height(HlSpace.l))
                // A fixed height that fits the text at the viewer's text size, whatever has focus.
                HeroInfo(shown, Modifier.padding(start = HlSpace.safeH).height(textRoom(HlLayout.hero)))
                Spacer(Modifier.height(HlSpace.l))
                MediaRow(header = null) {
                    items(titles.size, key = { titles[it].id }) { i ->
                        val item = titles[i]
                        PosterCard(
                            item,
                            onClick = { onOpenItem(item) },
                            modifier = card(i, item),
                            onFocus = { selectedId.value = item.id },
                        )
                    }
                }
            } else {
                // The selected title on one line, as on a wall, shown together with its backdrop.
                Box(
                    Modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m).height(HlLayout.infoLine),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    WithItsPicture(shown) { TitleLine(it) }
                }
                PosterWallGrid(grid) {
                    itemsIndexed(titles, key = { _, title -> title.id }) { i, item ->
                        PosterCard(
                            item,
                            onClick = { onOpenItem(item) },
                            modifier = card(i, item),
                            width = null,
                            onFocus = { selectedId.value = item.id },
                        )
                    }
                }
            }
        }
    }
}

/** Whether a page of [titles] titles uses a small library's layout: at most two lines of posters. */
internal fun smallPage(titles: Int): Boolean = titles <= HlLayout.WALL_COLUMNS * SMALL_LINES

/** Poster lines up to which a page uses the small library layout. */
private const val SMALL_LINES = 2

@Composable
private fun PersonHead(page: PersonPage, modifier: Modifier = Modifier) {
    val person = page.person
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Portrait(person)
        Spacer(Modifier.width(HlSpace.l))
        Column {
            ScreenTitle(person.name)
            Spacer(Modifier.height(HlSpace.xs))
            val count = page.titles.size
            FactRow(personJobs(page).map { jobName(it) } + pluralStringResource(R.plurals.title_count, count, count))
        }
    }
}

/** A person's jobs, each once, in credits order ([Job]) with the cast last. */
internal fun personJobs(page: PersonPage): List<Job> = page.jobs.filterValues { it > 0 }.keys.sortedBy { job ->
    if (job == Job.CAST) Job.entries.size else job.ordinal
}

/** A large portrait, the bare frame until it loads or when there is none; the name beside it announces it. */
@Composable
private fun Portrait(person: Person) {
    val px = HlSize.portrait.toPx()
    val c = Hl.colors
    // The artwork well and edge at rest, as for a portrait in a row: without a picture, the bare frame.
    Box(
        Modifier
            .width(HlSize.portrait)
            .aspectRatio(HlSize.PORTRAIT_RATIO)
            .clip(HlShapes.artwork)
            .background(groundStepFill())
            .artworkEdge(HlShapes.artwork, c)
            .clearAndSetSemantics {},
    ) {
        ArtworkImage(person.image?.url(px), person.name, Modifier.fillMaxSize())
    }
}
