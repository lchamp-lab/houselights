package app.houselights.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.ProviderCapabilities
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.RowScroll
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.BadgeRow
import app.houselights.ui.design.components.BillingBlock
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlTab
import app.houselights.ui.design.components.LandscapeCard
import app.houselights.ui.design.components.MediaRow
import app.houselights.ui.design.components.MetaLine
import app.houselights.ui.design.components.PersonCard
import app.houselights.ui.design.components.PosterCard
import app.houselights.ui.design.components.HlModal
import app.houselights.ui.design.components.TechSpecs
import app.houselights.ui.design.components.TitleArt
import app.houselights.ui.design.components.rememberAmbientColor
import app.houselights.ui.edit.EditMenu
import app.houselights.ui.home.ErrorMessage
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.formatTimecode

@Composable
fun DetailScreen(
    itemId: String,
    onPlay: (MediaItem, Boolean) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenNfo: (String) -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit,
    onIdentify: (String) -> Unit,
) {
    val app = appContainer()
    val vm = viewModel(key = "detail-$itemId") { DetailViewModel(app, itemId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item

    if (item == null) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(null, BackdropStyle.Full)
            state.error?.let { Box(Modifier.padding(top = 96.dp)) { ErrorMessage(it, vm::load) } }
        }
        return
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var focusedEpisode by remember { mutableStateOf<MediaItem?>(null) }
    var showTech by remember { mutableStateOf(false) }
    val media = item.media?.takeIf { it.video != null || it.audio.isNotEmpty() }
    if (showTech && media != null) {
        HlModal(stringResource(R.string.tech_details), onDismiss = { showTech = false }) { TechSpecs(media) }
    }
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        EditMenu(
            title = item.title,
            onDismiss = { showEdit = false },
            onArtwork = { kind -> showEdit = false; onEditArtwork(item.id, kind) },
            onIdentify = { showEdit = false; onIdentify(item.id) },
        )
    }

    CompositionLocalProvider(LocalAmbient provides rememberAmbientColor(item.backdrop)) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(item.backdrop, BackdropStyle.Full)

            LazyColumn(
                    Modifier.fillMaxSize().houseLit(LocalHouseLights.current),
                    contentPadding = PaddingValues(bottom = HlSpace.xxl),
                    verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                ) {
                    item(key = "hero") {
                        // At least most of the screen; taller rather than squeezed when the text is long.
                        Box(Modifier.fillMaxWidth().heightIn(min = screenHeight * 0.9f).revealOnFocus()) {
                            Hero(
                                item = item,
                                nextUp = state.nextUp,
                                capabilities = state.capabilities,
                                onPlay = onPlay,
                                onTogglePlayed = vm::togglePlayed,
                                onToggleFavorite = vm::toggleFavorite,
                                onOpenSeries = { item.seriesId?.let(onOpenItem) },
                                onOpenNfo = { onOpenNfo(item.id) },
                                onShowTech = if (media != null) ({ showTech = true }) else null,
                                onEdit = if (state.canEdit) ({ showEdit = true }) else null,
                                modifier = Modifier.padding(start = HlSpace.safeH, top = HlSpace.safeV + HlSpace.m, bottom = HlSpace.l).align(Alignment.BottomStart),
                            )
                        }
                    }

                    if (item.kind == MediaKind.SERIES && state.seasons.isNotEmpty()) {
                        item(key = "seasons") { Box(Modifier.revealOnFocus()) { Seasons(state.seasons, state.selectedSeasonId, vm::selectSeason) } }
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
                            MediaRow(stringResource(R.string.cast), spacing = HlSpace.s) {
                                items(item.cast) { PersonCard(it) }
                            }
                        }
                    }

                    if (state.similar.isNotEmpty()) {
                        item(key = "similar") {
                            MediaRow(stringResource(R.string.similar)) {
                                items(state.similar, key = { it.id }) { s -> PosterCard(s, onClick = { onOpenItem(s.id) }) }
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
    onOpenNfo: () -> Unit,
    onShowTech: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    modifier: Modifier,
) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(item.id) { runCatching { playFocus.requestFocus() } }
    val isEpisode = item.kind == MediaKind.EPISODE

    Column(modifier.widthIn(max = 520.dp)) {
        TitleArt(
            title = if (isEpisode) item.seriesTitle ?: item.title else item.title,
            logo = item.logo,
            maxWidth = 440.dp,
            maxHeight = 112.dp,
        )
        Spacer(Modifier.height(HlSpace.l))
        if (isEpisode) {
            Text(
                listOfNotNull(item.episodeLabel, item.title).joinToString("   "),
                style = Hl.type.title,
                color = Hl.colors.content,
            )
            Spacer(Modifier.height(HlSpace.xs))
        }
        MetaLine(item)
        if (item.badges.isNotEmpty()) {
            Spacer(Modifier.height(HlSpace.s))
            BadgeRow(item.badges)
        }
        item.tagline?.let {
            Spacer(Modifier.height(HlSpace.l))
            Text(it, style = Hl.type.title, color = Hl.colors.content)
        }
        if (!item.overview.isNullOrBlank()) {
            Spacer(Modifier.height(if (item.tagline != null) HlSpace.xs else HlSpace.l))
            Text(item.overview, style = Hl.type.body, color = Hl.colors.contentSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (item.directors.isNotEmpty() || item.cast.isNotEmpty()) {
            Spacer(Modifier.height(HlSpace.m))
            BillingBlock(item.directors, item.cast.map { it.name })
        }
        Spacer(Modifier.height(HlSpace.xl))

        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s), verticalAlignment = Alignment.Top) {
            // A series plays through its next episode.
            val target = if (item.kind == MediaKind.SERIES) nextUp else item.takeIf { it.isPlayable }
            if (target != null) {
                val resumeMs = target.user.positionMs
                val episode = if (item.kind == MediaKind.SERIES) target.episodeLabel else null
                if (resumeMs > 0) {
                    HlButton(
                        stringResource(R.string.resume),
                        onClick = { onPlay(target, true) },
                        modifier = Modifier.focusRequester(playFocus),
                        icon = Icons.Filled.PlayArrow,
                        detail = listOfNotNull(episode, formatTimecode(resumeMs)).joinToString("   "),
                        emphasis = Emphasis.Primary,
                    )
                    HlButton(stringResource(R.string.from_start), onClick = { onPlay(target, false) })
                } else {
                    HlButton(
                        stringResource(R.string.play),
                        onClick = { onPlay(target, false) },
                        modifier = Modifier.focusRequester(playFocus),
                        icon = Icons.Filled.PlayArrow,
                        detail = episode ?: target.runtimeMs?.let(::formatDuration),
                        emphasis = Emphasis.Primary,
                    )
                }
            }
            if (isEpisode && item.seriesId != null) {
                HlButton(stringResource(R.string.view_series), onClick = onOpenSeries)
            }
            if (capabilities?.markPlayed == true) {
                HlIconButton(
                    Icons.Filled.Check,
                    stringResource(if (item.user.played) R.string.watched else R.string.mark_watched),
                    onClick = onTogglePlayed,
                    modifier = if (target == null) Modifier.focusRequester(playFocus) else Modifier,
                )
            }
            if (onShowTech != null) {
                HlIconButton(HlIcons.Info, stringResource(R.string.tech_info), onClick = onShowTech)
            }
            if (item.hasNfo) {
                HlIconButton(HlIcons.Document, stringResource(R.string.nfo), onClick = onOpenNfo)
            }
            if (capabilities?.favorites == true) {
                HlIconButton(
                    if (item.user.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    stringResource(if (item.user.favorite) R.string.favorite else R.string.add_favorite),
                    onClick = onToggleFavorite,
                )
            }
            if (onEdit != null) {
                HlIconButton(HlIcons.Edit, stringResource(R.string.edit), onClick = onEdit)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Seasons(seasons: List<MediaItem>, selectedId: String?, onSelect: (String) -> Unit) = RowScroll {
    LazyRow(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = HlSpace.safeH - HlSpace.m),
        horizontalArrangement = Arrangement.spacedBy(HlSpace.xxs),
    ) {
        items(seasons, key = { it.id }) { s ->
            HlTab(s.title, selected = s.id == selectedId, onClick = { onSelect(s.id) }, onFocused = { onSelect(s.id) })
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
    Column {
        MediaRow(header = null, spacing = HlSpace.l) {
            items(episodes, key = { it.id }) { ep ->
                LandscapeCard(
                    item = ep,
                    onClick = { onPlay(ep) },
                    width = HlSize.episode,
                    title = listOfNotNull(ep.episodeNumber?.toString(), ep.title).joinToString("   "),
                    subtitle = ep.runtimeMs?.let(::formatDuration),
                    onFocused = { onFocus(ep) },
                )
            }
        }
        val shown = focused?.takeIf { f -> episodes.any { it.id == f.id } }
        Text(
            shown?.overview ?: "",
            style = Hl.type.bodySmall,
            color = Hl.colors.contentSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HlSpace.safeH).widthIn(max = 720.dp).height(64.dp),
        )
    }
}
