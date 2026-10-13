package app.posterwall.ui.detail

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.posterwall.R
import app.posterwall.core.MediaItem
import app.posterwall.core.MediaKind
import app.posterwall.core.ProviderCapabilities
import app.posterwall.ui.components.ActionButton
import app.posterwall.ui.components.BadgeRow
import app.posterwall.ui.components.Chip
import app.posterwall.ui.components.ImmersiveBackdrop
import app.posterwall.ui.components.LandscapeCard
import app.posterwall.ui.components.MetaRow
import app.posterwall.ui.components.PersonCard
import app.posterwall.ui.components.PosterCard
import app.posterwall.ui.components.SafeH
import app.posterwall.ui.components.SectionTitle
import app.posterwall.ui.components.TitleLogo
import app.posterwall.ui.components.appContainer
import app.posterwall.ui.components.formatDuration
import app.posterwall.ui.components.formatTimecode
import app.posterwall.ui.components.metaLine
import app.posterwall.ui.components.screenWidthPx
import app.posterwall.ui.components.toPx
import app.posterwall.ui.home.ErrorMessage
import app.posterwall.ui.theme.Palette

@Composable
fun DetailScreen(
    itemId: String,
    onPlay: (MediaItem, Boolean) -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val app = appContainer()
    val vm = viewModel(key = "detail-$itemId") { DetailViewModel(app, itemId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item

    if (item == null) {
        Box(Modifier.fillMaxSize()) {
            ImmersiveBackdrop(null)
            state.error?.let { Box(Modifier.padding(top = 96.dp)) { ErrorMessage(it, vm::load) } }
        }
        return
    }

    val screenPx = screenWidthPx()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var focusedEpisode by remember { mutableStateOf<MediaItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        ImmersiveBackdrop(item.backdrop?.url(screenPx), bottomWeight = 0.5f)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(screenHeight * 0.88f)) {
                    Hero(
                        item = item,
                        nextUp = state.nextUp,
                        capabilities = state.capabilities,
                        onPlay = onPlay,
                        onTogglePlayed = vm::togglePlayed,
                        onToggleFavorite = vm::toggleFavorite,
                        onOpenSeries = { item.seriesId?.let(onOpenItem) },
                        modifier = Modifier.padding(start = SafeH, bottom = 24.dp).align(Alignment.BottomStart),
                    )
                }
            }

            if (item.kind == MediaKind.SERIES && state.seasons.isNotEmpty()) {
                item(key = "seasons") {
                    Seasons(state.seasons, state.selectedSeasonId, vm::selectSeason)
                }
                item(key = "episodes-${state.selectedSeasonId}") {
                    Episodes(
                        episodes = state.episodes,
                        focused = focusedEpisode,
                        onFocus = { focusedEpisode = it },
                        onPlay = { ep -> onPlay(ep, ep.user.positionMs > 0) },
                    )
                }
            }

            if (item.cast.isNotEmpty()) {
                item(key = "cast") {
                    val px = 88.dp.toPx()
                    RowSection(stringResource(R.string.cast)) {
                        items(item.cast) { p ->
                            PersonCard(p.name, p.role, p.image?.url(px), onFocused = {})
                        }
                    }
                }
            }

            if (state.similar.isNotEmpty()) {
                item(key = "similar") {
                    val px = 124.dp.toPx()
                    RowSection(stringResource(R.string.similar)) {
                        items(state.similar, key = { it.id }) { s ->
                            PosterCard(s, s.poster?.url(px), onClick = { onOpenItem(s.id) }, onFocused = {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    item: MediaItem,
    nextUp: MediaItem?,
    capabilities: ProviderCapabilities?,
    onPlay: (MediaItem, Boolean) -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSeries: () -> Unit,
    modifier: Modifier,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { runCatching { playFocus.requestFocus() } }
    val isEpisode = item.kind == MediaKind.EPISODE

    Column(modifier.widthIn(max = 660.dp)) {
        TitleLogo(
            if (isEpisode) item.seriesTitle ?: item.title else item.title,
            item.logo?.url(480.dp.toPx()),
            maxHeight = 140.dp,
            maxWidth = 480.dp,
        )
        Spacer(Modifier.height(18.dp))
        if (isEpisode) {
            Text(
                listOfNotNull(item.episodeLabel, item.title).joinToString("  —  "),
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.Text,
            )
            Spacer(Modifier.height(8.dp))
        }
        MetaRow(metaLine(item), item.genres)
        Spacer(Modifier.height(10.dp))
        BadgeRow(item.badges)
        item.tagline?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = Palette.Text)
        }
        if (!item.overview.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                item.overview,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextDim,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.directors.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.directed_by, item.directors.joinToString(", ")),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextFaint,
            )
        }
        Spacer(Modifier.height(26.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A series plays through its next episode.
            val target = if (item.kind == MediaKind.SERIES) nextUp else item.takeIf { it.isPlayable }
            if (target != null) {
                val resumeMs = target.user.positionMs
                val prefix = if (item.kind == MediaKind.SERIES) target.episodeLabel?.let { "$it  " } ?: "" else ""
                if (resumeMs > 0) {
                    ActionButton(
                        prefix + stringResource(R.string.resume, formatTimecode(resumeMs)),
                        onClick = { onPlay(target, true) },
                        icon = Icons.Filled.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    ActionButton(stringResource(R.string.from_start), onClick = { onPlay(target, false) }, icon = Icons.Filled.Refresh)
                } else {
                    val runtime = target.runtimeMs?.let { "  ·  " + formatDuration(it) } ?: ""
                    ActionButton(
                        prefix + stringResource(R.string.play) + runtime,
                        onClick = { onPlay(target, false) },
                        icon = Icons.Filled.PlayArrow,
                        primary = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                }
            }
            if (capabilities?.markPlayed == true) {
                ActionButton(
                    stringResource(if (item.user.played) R.string.watched else R.string.mark_watched),
                    onClick = onTogglePlayed,
                    icon = Icons.Filled.Check,
                    modifier = if (target == null) Modifier.focusRequester(playFocus) else Modifier,
                )
            }
            if (capabilities?.favorites == true) {
                val fav = item.user.favorite
                ActionButton(
                    stringResource(if (fav) R.string.favorite else R.string.add_favorite),
                    onClick = onToggleFavorite,
                    icon = if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                )
            }
            if (isEpisode && item.seriesId != null) {
                ActionButton(stringResource(R.string.view_series), onClick = onOpenSeries)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RowSection(title: String, content: LazyListScope.() -> Unit) {
    Column {
        SectionTitle(title, Modifier.padding(start = SafeH, bottom = 4.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = SafeH, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Seasons(seasons: List<MediaItem>, selectedId: String?, onSelect: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = SafeH),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seasons, key = { it.id }) { s ->
            Chip(
                s.title,
                selected = s.id == selectedId,
                onClick = { onSelect(s.id) },
                onFocused = { onSelect(s.id) },
            )
        }
    }
}

@Composable
private fun Episodes(
    episodes: List<MediaItem>,
    focused: MediaItem?,
    onFocus: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val px = 280.dp.toPx()
    Column {
        RowSection(stringResource(R.string.episodes)) {
            items(episodes, key = { it.id }) { ep ->
                LandscapeCard(
                    item = ep,
                    imageUrl = ep.landscape?.url(px),
                    title = listOfNotNull(ep.episodeNumber?.let { "$it." }, ep.title).joinToString(" "),
                    subtitle = ep.runtimeMs?.let { formatDuration(it) },
                    onClick = { onPlay(ep) },
                    onFocused = { onFocus(ep) },
                    width = 280.dp,
                )
            }
        }
        val shown = focused?.takeIf { f -> episodes.any { it.id == f.id } }
        Text(
            shown?.overview ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = SafeH).widthIn(max = 760.dp).height(60.dp),
        )
    }
}
