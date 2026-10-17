package app.houselights.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Job
import app.houselights.core.MediaItem
import app.houselights.core.Person
import app.houselights.core.PersonPage
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll
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
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.components.TitleLine
import app.houselights.ui.design.components.WithItsPicture
import app.houselights.ui.library.COLUMNS
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.jobName
import app.houselights.ui.util.rememberSettled
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.toPx

/**
 * A person's page: their portrait in a large disc, their name, what they did and in how many titles
 * ("Réalisation  Interprétation  7 titres"); under it, their titles before and behind the camera in
 * the order of their years, as posters without words. Under two lines of posters, it takes the
 * composition of a small library: home's, a hero and one row.
 */
@Composable
fun PersonScreen(personId: String, onOpenItem: (MediaItem) -> Unit) {
    val app = appContainer()
    val vm: PersonViewModel = viewModel(key = "person-$personId", factory = PersonViewModel.factory(app, personId))
    ReloadOnLibraryChange { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val page = state.page
    if (page == null) {
        // Trying again keeps the error on screen, its button at work, until the answer.
        val error = retainedError(state.error, retrying = state.loading)
        Box(Modifier.fillMaxSize().padding(top = HlLayout.errorTop)) {
            if (error != null) HlErrorState(error, vm::load, Modifier.padding(start = HlSpace.safeH, top = HlSpace.l), state.loading)
        }
        return
    }
    PersonBands(page, onOpenItem)
}

/** [page] laid out: its head, then its titles as a wall, or as one row when they are few ([smallPage]). */
@Composable
internal fun PersonBands(page: PersonPage, onOpenItem: (MediaItem) -> Unit) {
    val titles = remember(page) { page.titles.map { it.item } }
    val small = smallPage(titles.size)
    // The title selected stays found coming back; before any, the first. Shown once focus settles.
    val selectedId = rememberSaveable { mutableStateOf<String?>(null) }
    val shown by rememberSettled { titles.find { it.id == selectedId.value } ?: titles.firstOrNull() }
    val first = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    // Each arrival puts focus back on the title left, else on the first one.
    val memory = rememberFocusMemory()
    RestoreFocus(memory, first, ready = titles.isNotEmpty()) { key ->
        val index = FocusKey.itemId(key)?.let { id -> titles.indexOfFirst { it.id == id } } ?: -1
        if (index >= 0 && !small) grid.scrollToItem(index)
        index >= 0
    }
    DetailAhead(shown)
    AmbientBackdrop(shown?.backdrop, if (small) BackdropStyle.Hero else BackdropStyle.Quiet) {
        Column(Modifier.fillMaxSize()) {
            PersonHead(page, Modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV))
            fun card(index: Int, item: MediaItem) = Modifier
                .focusKey(FocusKey.item(item.id), memory)
                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
            if (small) {
                Spacer(Modifier.height(HlSpace.l))
                // Its height holds the text at the viewer's size, whatever has focus.
                HeroInfo(shown, Modifier.padding(start = HlSpace.safeH).height(textRoom(HlLayout.hero)))
                Spacer(Modifier.height(HlSpace.l))
                MediaRow(header = null) {
                    items(titles.size, key = { titles[it].id }) { i ->
                        val item = titles[i]
                        PosterCard(
                            item,
                            onClick = { onOpenItem(item) },
                            modifier = card(i, item),
                            onFocused = { selectedId.value = item.id },
                        )
                    }
                }
            } else {
                // The title selected, named as a wall names it: one line, said with its picture.
                Box(
                    Modifier.padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m).height(HlLayout.infoLine),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    WithItsPicture(shown) { TitleLine(it) }
                }
                ListScroll(top = HlSpace.m) { LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = grid,
                    contentPadding = PaddingValues(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.m, bottom = HlFocus.edge),
                    horizontalArrangement = Arrangement.spacedBy(HlSpace.l),
                    verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(titles, key = { _, title -> title.id }) { i, item ->
                        PosterCard(
                            item,
                            onClick = { onOpenItem(item) },
                            modifier = card(i, item),
                            width = null,
                            onFocused = { selectedId.value = item.id },
                        )
                    }
                } }
            }
        }
    }
}

/** A person's page of [titles] titles takes a small library's composition: under two lines of posters. */
internal fun smallPage(titles: Int): Boolean = titles <= COLUMNS * SMALL_LINES

/** Lines of posters under which a page is a small library's. */
private const val SMALL_LINES = 2

/** The head of a person's page: their portrait, their name, then their jobs and titles as facts. */
@Composable
private fun PersonHead(page: PersonPage, modifier: Modifier = Modifier) {
    val person = page.person
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Portrait(person)
        Spacer(Modifier.width(HlSpace.l))
        Column {
            ScreenTitle(person.name)
            Spacer(Modifier.height(HlSpace.xs))
            // No punctuation between facts: the space separates them.
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
                personJobs(page).forEach { Text(jobName(it), style = Hl.type.meta, color = Hl.colors.contentSecondary) }
                val count = page.titles.size
                Text(pluralStringResource(R.plurals.title_count, count, count), style = Hl.type.meta, color = Hl.colors.contentSecondary)
            }
        }
    }
}

/**
 * What a person did across the library, as their page says it: behind the camera before in front
 * of it, each job once, in the order of the credits ([Job]).
 */
internal fun personJobs(page: PersonPage): List<Job> = page.jobs.filterValues { it > 0 }.keys.sortedBy { job ->
    if (job == Job.CAST) Job.entries.size else job.ordinal
}

/** A person's portrait, large: their initials until it loads, or for one without a picture. Said by the name beside it. */
@Composable
private fun Portrait(person: Person) {
    val px = HlSize.portrait.toPx()
    val c = Hl.colors
    // The well and edge of artwork at rest, as a portrait in a row has them.
    Box(
        Modifier
            .size(HlSize.portrait)
            .clip(HlShapes.avatar)
            .background(c.content.copy(alpha = HlAlpha.FILL))
            .artworkEdge(HlShapes.avatar, c)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            person.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
            style = Hl.type.title,
            color = Hl.colors.contentTertiary,
        )
        person.image?.url(px)?.let { ArtworkImage(it, person.name, Modifier.fillMaxSize()) }
    }
}
