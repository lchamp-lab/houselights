package app.houselights.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.NfoInfo
import app.houselights.core.NfoKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.DefinitionList
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlFormScaffold
import app.houselights.ui.design.components.HlScrollArea
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.Score
import app.houselights.ui.design.components.InfoBlock
import app.houselights.ui.design.components.StatusPanel
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.home.ErrorIntertitle
import app.houselights.ui.util.LoadState
import app.houselights.ui.util.retainedError
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatDuration

/** Shows the NFO of a title: its details laid out, or the XML as written. */
@Composable
fun NfoScreen(itemId: String) {
    val app = appContainer()
    val vm: NfoViewModel = viewModel(key = "nfo-$itemId", factory = NfoViewModel.factory(app, itemId))
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val tabsFocus = remember { FocusRequester() }
    val loaded = state.document
    val doc = (loaded as? LoadState.Ready)?.value
    val info = doc?.info
    // A failure takes the place of the tabs, and stays while trying again, its button at work.
    val failure = retainedError((loaded as? LoadState.Failed)?.error, retrying = loaded is LoadState.Loading)
    LaunchedEffect(failure == null) { if (failure == null) runCatching { tabsFocus.requestFocus() } }
    HlFormScaffold(
        title = stringResource(R.string.nfo_title),
        description = stringResource(R.string.nfo_desc),
        aside = {
            StatusPanel(
                // A file read is a fact; one that cannot be read needs attention.
                state = when {
                    info != null -> StatusState.Done
                    loaded is LoadState.Loading -> StatusState.Pending
                    else -> StatusState.Attention
                },
                stateText = stringResource(
                    when {
                        loaded is LoadState.Loading -> R.string.nfo_state_reading
                        info == null -> R.string.nfo_state_unreadable
                        info.kind == NfoKind.LINK -> R.string.nfo_state_link
                        else -> R.string.nfo_state_full
                    }
                ),
                label = stringResource(R.string.label_file),
                value = doc?.path?.substringAfterLast('/') ?: stringResource(R.string.placeholder_pending),
                valueStyle = Hl.type.entry,
                // Why it failed is the intertitle's to say, beside it.
                detail = state.title,
            )
        },
    ) {
        if (failure != null) {
            ErrorIntertitle(failure, vm::load, retrying = loaded is LoadState.Loading)
        } else {
            HlSegmented(
                listOf(stringResource(R.string.nfo_tab_info), stringResource(R.string.nfo_tab_xml)),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.focusRequester(tabsFocus),
                selectOnFocus = true,
            )
            if (doc != null) {
                when (tab) {
                    0 -> NfoDetails(info)
                    else -> XmlView(doc.lines)
                }
            }
        }
    }
}

/**
 * The NFO laid out like a sheet: what the title is (its name in the voice of the poster, its
 * facts), then one block per subject: its story, its credits, the rest, its identifiers.
 */
@Composable
private fun NfoDetails(info: NfoInfo?) = ScrollablePanel {
    val c = Hl.colors
    when {
        info == null -> Text(stringResource(R.string.nfo_unreadable), style = Hl.type.body, color = c.contentSecondary)
        info.kind == NfoKind.LINK -> {
            Text(stringResource(R.string.nfo_link_only), style = Hl.type.body, color = c.contentSecondary)
            Identifiers(info)
        }
        else -> {
            Column {
                info.title?.let { Text(it, style = Hl.type.subject, color = c.content) }
                info.originalTitle?.takeIf { it != info.title }?.let { Text(it, style = Hl.type.meta, color = c.contentSecondary) }
                Spacer(Modifier.height(HlSpace.xs))
                Facts(info)
            }
            if (info.tagline != null || info.plot != null) {
                InfoBlock(stringResource(R.string.label_plot)) {
                    Column(Modifier.widthIn(max = HlLayout.readable)) {
                        info.tagline?.let { Text(it, style = Hl.type.title, color = c.content) }
                        if (info.tagline != null && info.plot != null) Spacer(Modifier.height(HlSpace.xs))
                        info.plot?.let { Text(it, style = Hl.type.body, color = c.contentSecondary) }
                    }
                }
            }
            if (info.directors.isNotEmpty() || info.actors.isNotEmpty()) {
                InfoBlock(stringResource(R.string.label_cast)) { Credits(info) }
            }
            val details = buildList {
                info.genres.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.label_genres) to it.joinToString(", ")) }
                info.studios.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.label_studios) to it.joinToString(", ")) }
                info.collection?.let { add(stringResource(R.string.label_collection) to it) }
            }
            if (details.isNotEmpty()) {
                InfoBlock(stringResource(R.string.nfo_block_details)) {
                    DefinitionList(details, labelWidth = HlLayout.definitionLabelShort)
                }
            }
            Identifiers(info)
        }
    }
}

/** Episode, year, length, rating and score on one line, as on a detail page. */
@Composable
private fun Facts(info: NfoInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.fact), verticalAlignment = Alignment.CenterVertically) {
        FactRow(
            listOf(
                if (info.season != null && info.episode != null) "S${info.season} E${info.episode}" else null,
                info.year?.toString() ?: info.premiered,
                info.runtimeMin?.let { formatDuration(it * MS_PER_MINUTE) },
            ),
        )
        info.certification?.let { Badge(it) }
        info.rating?.let { Score(it) }
    }
}

/** Who made it, then who plays whom: names in two columns, each with what they did. */
@Composable
private fun Credits(info: NfoInfo) {
    val c = Hl.colors
    val directing = stringResource(R.string.label_directors)
    val credits = info.directors.map { it to directing } + info.actors.take(ACTORS).map { it.name to it.role }
    Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
        credits.chunked(2).forEach { pair ->
            Row {
                pair.forEach { (name, role) ->
                    Column(Modifier.weight(1f)) {
                        Text(name, style = Hl.type.entry, color = c.content)
                        role?.let { Text(it, style = Hl.type.caption, color = c.contentSecondary) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** The databases the NFO points to, as the badges of the formats they are. */
@Composable
private fun Identifiers(info: NfoInfo) {
    val ids = listOfNotNull(info.tmdbId?.let { "TMDB $it" }, info.imdbId?.let { "IMDb $it" }, info.tvdbId?.let { "TVDB $it" })
    if (ids.isEmpty()) return
    InfoBlock(stringResource(R.string.label_ids)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) { ids.forEach { Badge(it) } }
    }
}

/**
 * The NFO as written, in a monospaced face since it is code. One text of up to a megabyte would be
 * laid out whole each time the tab shows; a lazy list lays out only the lines on screen, each one
 * where it stood in that text ([CodeLines]).
 */
@Composable
private fun XmlView(lines: List<String>) {
    val code = Hl.type.code
    val styles = remember(code) { CodeLines(code) }
    HlScrollArea(code.lineHeight, Modifier.height(HlLayout.textPanel)) {
        itemsIndexed(lines) { i, line -> Text(line, style = styles.of(i, lines.size), color = Hl.colors.contentSecondary) }
    }
}

/**
 * The lines of a text in [code], as items of their own. The type scale trims a text's box to its
 * letters (Type.kt): the room a line height adds goes between lines only. So each line keeps that
 * room, but the first above it and the last under it, and the lines fall where they did in one text.
 */
private class CodeLines(private val code: TextStyle) {
    private val alignment = code.lineHeightStyle?.alignment ?: LineHeightStyle.Alignment.Proportional
    private val first = trimmed(LineHeightStyle.Trim.FirstLineTop)
    private val middle = trimmed(LineHeightStyle.Trim.None)
    private val last = trimmed(LineHeightStyle.Trim.LastLineBottom)

    private fun trimmed(trim: LineHeightStyle.Trim) = code.copy(lineHeightStyle = LineHeightStyle(alignment, trim))

    fun of(index: Int, count: Int): TextStyle = when {
        count == 1 -> code
        index == 0 -> first
        index == count - 1 -> last
        else -> middle
    }
}

/**
 * A panel the remote can focus and scroll with up and down. Up at the top lets focus
 * leave (to the tabs above).
 */
@Composable
private fun ScrollablePanel(content: @Composable () -> Unit) {
    HlScrollArea(Modifier.height(HlLayout.textPanel)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) { content() }
    }
}

private const val MS_PER_MINUTE = 60_000L

/** Actors listed: the leading cast, as a detail page shows it. */
private const val ACTORS = 8
