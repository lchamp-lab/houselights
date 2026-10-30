package app.houselights.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlErrorState
import app.houselights.ui.design.components.HlScrollArea
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.retainedError
import java.util.Locale

/** A title's NFO in a reading sheet over its page: its details as a list of definitions, or its XML as written. */
@Composable
fun NfoSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    vm: NfoViewModel = viewModel(key = "nfo-${item.id}", factory = NfoViewModel.factory(appContainer(), item)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    NfoSheet(item, state, onRetry = vm::load, onDismiss = onDismiss)
}

/**
 * The sheet of [state]: how the file reads after the title's facts, its views (Informations, XML) on the title's line,
 * then the one shown, focused and ringed as it is read; a failure replaces them and stays while retrying.
 */
@Composable
internal fun NfoSheet(item: MediaItem, state: NfoState, onRetry: () -> Unit, onDismiss: () -> Unit) {
    var view by remember { mutableIntStateOf(0) }
    val reading = remember { FocusRequester() }
    val loaded = state.document
    val doc = (loaded as? LoadState.Ready)?.value
    val info = doc?.info
    val failure = retainedError((loaded as? LoadState.Failed)?.error, retrying = loaded is LoadState.Loading)
    // Focused as it opens when read already, else once read.
    val ready = doc != null
    LaunchedEffect(ready) { if (ready) reading.tryRequestFocus() }
    HlSideSheet(
        stringResource(R.string.nfo_title),
        onDismiss = onDismiss,
        facts = listOf(item.title, item.year?.toString()),
        // A parsed file reads as done; an unreadable one needs attention.
        stateText = stringResource(
            when {
                loaded is LoadState.Loading -> R.string.nfo_state_reading
                info == null -> R.string.nfo_state_unreadable
                info.kind == NfoKind.LINK -> R.string.nfo_state_link
                else -> R.string.nfo_state_full
            },
        ),
        state = when {
            info != null -> StatusState.Done
            loaded is LoadState.Loading -> StatusState.Pending
            else -> StatusState.Attention
        },
        description = stringResource(R.string.nfo_desc),
        places = if (failure == null) {
            {
                HlSegmented(
                    listOf(stringResource(R.string.nfo_tab_info), stringResource(R.string.nfo_tab_xml)),
                    selected = view,
                    onSelect = { view = it },
                    selectOnFocus = true,
                )
            }
        } else {
            null
        },
        initialFocus = reading.takeIf { ready },
        scrolls = false,
        wide = true,
    ) { _ ->
        val shown = Modifier.weight(1f, fill = false).focusRequester(reading)
        when {
            failure != null -> HlErrorState(failure, onRetry, retrying = loaded is LoadState.Loading)
            doc == null -> Unit
            view == 0 -> HlScrollArea(shown, alwaysFocusable = true) { NfoDetails(info, doc.path.substringAfterLast('/')) }
            else -> HlScrollArea(doc.lines, shown, alwaysFocusable = true)
        }
    }
}

/** The NFO as definitions, a label in secondary `meta` beside each value in `body`, then the file it is read from. */
@Composable
private fun NfoDetails(info: NfoInfo?, file: String) {
    val c = Hl.colors
    // A definition's baseline 42 under the last's: the board's rhythm.
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
        when {
            info == null -> Text(stringResource(R.string.nfo_unreadable), style = Hl.type.body, color = c.contentSecondary)
            info.kind == NfoKind.LINK -> {
                Text(stringResource(R.string.nfo_link_only), style = Hl.type.body, color = c.contentSecondary)
                Identifiers(info)
            }
            else -> Details(info)
        }
        Definition(stringResource(R.string.label_file), file)
    }
}

/** What the NFO says of the title, in the order of its page; what it leaves out is left out. */
@Composable
private fun ColumnScope.Details(info: NfoInfo) {
    info.title?.let { Definition(stringResource(R.string.label_title), it) }
    info.originalTitle?.takeIf { it != info.title }?.let { Definition(stringResource(R.string.label_original_title), it) }
    if (info.season != null && info.episode != null) Definition(stringResource(R.string.label_episode), "S${info.season} E${info.episode}")
    (info.year?.toString() ?: info.premiered)?.let { Definition(stringResource(R.string.label_year), it) }
    info.runtimeMin?.let { Definition(stringResource(R.string.tech_duration), formatDuration(it * MS_PER_MINUTE)) }
    info.certification?.let { Definition(stringResource(R.string.label_certification), it) }
    info.rating?.let { Definition(stringResource(R.string.label_rating), String.format(Locale.getDefault(), "%.1f", it)) }
    Listed(R.string.label_genres, info.genres)
    Listed(R.string.label_directors, info.directors)
    Listed(R.string.label_cast, info.actors.take(ACTORS).map { it.name })
    Listed(R.string.label_studios, info.studios)
    info.collection?.let { Definition(stringResource(R.string.label_collection), it) }
    Identifiers(info)
    listOfNotNull(info.tagline, info.plot).takeIf { it.isNotEmpty() }?.let {
        Definition(stringResource(R.string.label_plot), it.joinToString("\n"))
    }
}

/** Names of one kind in a line, when the NFO gives any. */
@Composable
private fun Listed(label: Int, names: List<String>) {
    if (names.isNotEmpty()) Definition(stringResource(label), names.joinToString(", "))
}

/** The databases the NFO points to, each with its id. */
@Composable
private fun Identifiers(info: NfoInfo) {
    val ids = listOfNotNull(info.tmdbId?.let { "TMDB $it" }, info.imdbId?.let { "IMDb $it" }, info.tvdbId?.let { "TVDB $it" })
    if (ids.isNotEmpty()) Definition(stringResource(R.string.label_ids), ids.joinToString(", "))
}

/** A [label] beside its [value], on one baseline. */
@Composable
private fun Definition(label: String, value: String) {
    val c = Hl.colors
    Row {
        Text(label, style = Hl.type.meta, color = c.contentSecondary, modifier = Modifier.width(HlLayout.readingLabel).alignByBaseline())
        Text(value, style = Hl.type.body, color = c.content, modifier = Modifier.weight(1f).alignByBaseline())
    }
}

private const val MS_PER_MINUTE = 60_000L

/** Actors listed: the leading cast, as on a detail page. */
private const val ACTORS = 8
