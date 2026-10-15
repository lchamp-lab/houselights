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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.textRoom
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalHouseLights
import app.houselights.ui.design.houseLit
import app.houselights.ui.design.RowScroll
import app.houselights.ui.design.scrollsAsBlock
import app.houselights.ui.design.ListScroll
import app.houselights.ui.design.fadeOutsideSafeArea
import app.houselights.ui.design.BackToStart
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.design.focusKey
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.design.components.Backdrop
import app.houselights.ui.design.components.BackdropStyle
import app.houselights.ui.design.components.BadgeRow
import app.houselights.ui.design.components.BillingBlock
import app.houselights.ui.design.components.EpisodeHeading
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HeroSkeleton
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.StatusState
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
import app.houselights.ui.util.ReloadOnLibraryChange
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.screenHeight
import app.houselights.ui.util.skeletonDelayPassed

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
    ReloadOnLibraryChange { vm.load() }
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item
    // A failure to play stays until the next action (OK, wherever it is pressed) or the next screen.
    DisposableEffect(Unit) { onDispose { app.playback.clearProblem() } }

    // Each arrival puts focus back on what opened another screen (Modifier, the NFO, a film of a
    // row); a first visit starts on the first action, the play button.
    val memory = rememberFocusMemory()
    val actions = remember { FocusRequester() }
    RestoreFocus(memory, actions, ready = item != null)
    // A film started over is resumed next: back from the player, focus is on the play button.
    LaunchedEffect(Unit) {
        app.playback.playbackEnded.collect { if (memory.last == RESTART) memory.focus(PLAY) }
    }
    val page = rememberLazyListState()

    if (item == null) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(null, BackdropStyle.Full)
            Box(Modifier.padding(top = HlLayout.errorTop)) {
                val error = state.error
                when {
                    error != null -> ErrorMessage(error, vm::load)
                    // Where the title and its facts will be, once loading takes long enough to be seen.
                    skeletonDelayPassed() -> HeroSkeleton(Modifier.padding(start = HlSpace.safeH, top = HlSpace.l))
                }
            }
        }
        return
    }

    // Down the page, Back returns to the actions; from the top, it leaves.
    val scrolled by remember {
        derivedStateOf { backReturnsToActions(page.firstVisibleItemIndex, page.firstVisibleItemScrollOffset) }
    }
    BackToStart(away = scrolled) {
        page.animateScrollToItem(0)
        runCatching { actions.requestFocus() }
    }

    val screenHeight = screenHeight()
    var focusedEpisode by remember { mutableStateOf<MediaItem?>(null) }
    var showTech by remember { mutableStateOf(false) }
    val media = item.media?.takeIf { it.video != null || it.audio.isNotEmpty() }
    if (showTech && media != null) {
        HlModal(
            stringResource(R.string.tech_details),
            onDismiss = { showTech = false },
            facts = listOf(item.title, item.year?.toString()),
        ) {
            TechSpecs(media, fileName = item.fileName)
        }
    }
    var showEdit by remember { mutableStateOf(false) }
    if (showEdit) {
        EditMenu(
            item = item,
            onDismiss = { showEdit = false },
            onArtwork = { kind -> showEdit = false; onEditArtwork(item.id, kind) },
            onIdentify = { showEdit = false; onIdentify(item.id) },
        )
    }

    val ambient = rememberAmbientColor(item.backdrop)
    CompositionLocalProvider(LocalAmbient provides { ambient.value }) {
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key in OK_KEYS) app.playback.clearProblem()
                false
            },
        ) {
            Backdrop(item.backdrop, BackdropStyle.Full)

            // The page turns by whole blocks: a row coming into view rises to the safe area, and
            // the gap between blocks, wider than that keyline, takes the one above off the screen.
            ListScroll(top = HlFocus.edge, toTop = true) { LazyColumn(
                    Modifier.fillMaxSize().houseLit(LocalHouseLights.current),
                    state = page,
                    contentPadding = PaddingValues(bottom = HlFocus.edge),
                    verticalArrangement = Arrangement.spacedBy(HlLayout.detailGap),
                ) {
                    item(key = "hero") {
                        // The screen less the first row's header and a strip of its images; taller
                        // rather than squeezed when the text is long.
                        Box(Modifier.fillMaxWidth().heightIn(min = HlLayout.detailHeroMin(screenHeight)).scrollsAsBlock()) {
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
                                memory = memory,
                                actions = actions,
                                modifier = Modifier
                                    .padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV)
                                    .align(Alignment.BottomStart),
                            )
                        }
                    }

                    if (item.kind == MediaKind.SERIES && state.seasons.isNotEmpty()) {
                        // A track with one option is no choice: a single season names the episode row instead.
                        val single = state.seasons.singleOrNull()
                        if (single == null) {
                            item(key = "seasons") { Box(Modifier.scrollsAsBlock()) { Seasons(state.seasons, state.selectedSeasonId, vm::selectSeason) } }
                        }
                        item(key = "episodes-${state.selectedSeasonId}") {
                            Episodes(
                                header = single?.title,
                                episodes = state.episodes,
                                memory = memory,
                                focused = focusedEpisode,
                                onFocus = { focusedEpisode = it },
                                onPlay = { ep -> onPlay(ep, ep.user.positionMs > 0) },
                            )
                        }
                    }

                    if (item.kind == MediaKind.COLLECTION && state.collectionFilms.isNotEmpty()) {
                        item(key = "films") {
                            MediaRow(stringResource(R.string.library_movies), memory.rowGroup(FILMS)) {
                                items(state.collectionFilms, key = { it.id }) { f ->
                                    PosterCard(f, onClick = { onOpenItem(f.id) }, modifier = memory.card(FILMS, f))
                                }
                            }
                        }
                    }

                    // A film's saga: the collection itself first, then its other films in order.
                    state.collection?.let { collection ->
                        item(key = "collection") {
                            MediaRow(collection.title, memory.rowGroup(SAGA)) {
                                item(key = collection.id) {
                                    PosterCard(
                                        collection,
                                        onClick = { onOpenItem(collection.id) },
                                        modifier = memory.card(SAGA, collection),
                                    )
                                }
                                items(state.collectionFilms, key = { it.id }) { f ->
                                    PosterCard(f, onClick = { onOpenItem(f.id) }, modifier = memory.card(SAGA, f))
                                }
                            }
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
                            MediaRow(stringResource(R.string.similar), memory.rowGroup(SIMILAR)) {
                                items(state.similar, key = { it.id }) { s ->
                                    PosterCard(s, onClick = { onOpenItem(s.id) }, modifier = memory.card(SIMILAR, s))
                                }
                            }
                        }
                    }
                } }
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
    memory: FocusMemory,
    actions: FocusRequester,
    modifier: Modifier,
) {
    val isEpisode = item.kind == MediaKind.EPISODE

    Column(modifier) {
        // The text keeps a reading measure; the actions below may run wider, within the safe area.
        // At the end of a page the last rows cannot rise to the top: what is left of the hero above
        // them fades out instead of showing a sliver.
        Column(Modifier.widthIn(max = HlLayout.readableNarrow).fadeOutsideSafeArea()) {
            TitleArt(
                title = if (isEpisode) item.seriesTitle ?: item.title else item.title,
                logo = item.logo,
                maxWidth = HlLayout.titleArtWidth,
                maxHeight = HlLayout.titleArtHeight,
            )
            Spacer(Modifier.height(HlSpace.l))
            if (isEpisode) {
                EpisodeHeading(item.episodeLabel, item.title)
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
                Text(
                    item.overview,
                    style = Hl.type.body,
                    color = Hl.colors.contentSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.directors.isNotEmpty() || item.cast.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.m))
                BillingBlock(item.directors, item.cast.map { it.name })
            }
        }
        Spacer(Modifier.height(HlSpace.xl))

        Actions(
            item, nextUp, capabilities, onPlay, onTogglePlayed, onToggleFavorite, onOpenSeries, onOpenNfo, onShowTech, onEdit,
            memory, actions,
        )
    }
}

/**
 * What can be done with the title, in one row. Room under it for the label of a round button:
 * nothing moves when it shows. [actions] focuses the first of them.
 */
@Composable
private fun Actions(
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
    memory: FocusMemory,
    actions: FocusRequester,
) {
    val problem by appContainer().playback.playbackProblem.collectAsStateWithLifecycle()
    Row(
        Modifier.focusRequester(actions).padding(bottom = HlLayout.iconLabelRoom).fadeOutsideSafeArea(),
        horizontalArrangement = Arrangement.spacedBy(HlSpace.s),
        verticalAlignment = Alignment.Top,
    ) {
        PlayButtons(item, nextUp, memory, onPlay)
        if (item.kind == MediaKind.EPISODE && item.seriesId != null) {
            HlButton(stringResource(R.string.view_series), onClick = onOpenSeries, modifier = Modifier.focusKey(SERIES, memory))
        }
        // A toggle says what OK does; its icon says where the title stands. Watched is done, not a
        // choice: the check fills, in projector white.
        if (capabilities?.markPlayed == true) {
            HlIconButton(
                if (item.user.played) HlIcons.CheckFilled else HlIcons.Check,
                stringResource(if (item.user.played) R.string.mark_unwatched else R.string.mark_watched),
                onClick = onTogglePlayed,
                modifier = Modifier.focusKey(WATCHED, memory),
            )
        }
        // A button bears the name of what it opens.
        if (onShowTech != null) {
            HlIconButton(
                HlIcons.Info,
                stringResource(R.string.tech_details),
                onClick = onShowTech,
                modifier = Modifier.focusKey(TECH, memory),
            )
        }
        if (item.hasNfo) {
            HlIconButton(
                HlIcons.Document,
                stringResource(R.string.nfo_title),
                onClick = onOpenNfo,
                modifier = Modifier.focusKey(NFO, memory),
            )
        }
        if (capabilities?.favorites == true && item.kind != MediaKind.COLLECTION) {
            HlIconButton(
                if (item.user.favorite) HlIcons.HeartFilled else HlIcons.Heart,
                stringResource(if (item.user.favorite) R.string.remove_favorite else R.string.add_favorite),
                onClick = onToggleFavorite,
                modifier = Modifier.focusKey(FAVORITE, memory),
                lit = item.user.favorite,
            )
        }
        if (onEdit != null) {
            HlIconButton(HlIcons.Edit, stringResource(R.string.edit), onClick = onEdit, modifier = Modifier.focusKey(EDIT, memory))
        }
        // Why the last title asked for did not play, beside the actions that asked for it.
        problem?.let {
            HlStatus(
                StatusState.Attention,
                stringResource(R.string.play_failed),
                Modifier.align(Alignment.CenterVertically).padding(start = HlSpace.m).weight(1f, fill = false),
                detail = stringResource(it.message),
            )
        }
    }
}

/**
 * One button that says Lire or Reprendre, so that a film ended half seen keeps focus on it, and
 * beside it, once started, the way to start over. A series plays through its next episode, a
 * collection through its next film.
 */
@Composable
private fun PlayButtons(item: MediaItem, nextUp: MediaItem?, memory: FocusMemory, onPlay: (MediaItem, Boolean) -> Unit) {
    val series = item.kind == MediaKind.SERIES || item.kind == MediaKind.COLLECTION
    val target = (if (series) nextUp else item.takeIf { it.isPlayable }) ?: return
    val resume = target.user.positionMs > 0
    val episode = when (item.kind) {
        MediaKind.SERIES -> target.episodeLabel
        MediaKind.COLLECTION -> target.title
        else -> null
    }
    HlButton(
        stringResource(if (resume) R.string.resume else R.string.play),
        onClick = { onPlay(target, resume) },
        modifier = Modifier.focusKey(PLAY, memory),
        icon = Icons.Filled.PlayArrow,
        detail = if (resume) {
            listOfNotNull(episode, timeLeft(target)).joinToString(FACT_GAP)
        } else {
            episode ?: target.runtimeMs?.let(::formatDuration)
        },
        emphasis = Emphasis.Primary,
    )
    if (resume) {
        HlIconButton(
            HlIcons.Restart,
            stringResource(R.string.from_start),
            onClick = { onPlay(target, false) },
            modifier = Modifier.focusKey(RESTART, memory),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Seasons(seasons: List<MediaItem>, selectedId: String?, onSelect: (String) -> Unit) {
    HlSegmented(
        options = seasons.map { it.title },
        selected = seasons.indexOfFirst { it.id == selectedId },
        onSelect = { onSelect(seasons[it].id) },
        modifier = Modifier.padding(horizontal = HlSpace.safeH),
        selectOnFocus = true,
        scrollable = true,
    )
}

/** Where an episode stands, in words: watched, or the time left; nothing when not started. */
@Composable
private fun episodeState(ep: MediaItem): String? =
    if (ep.user.played) stringResource(R.string.watched) else timeLeft(ep)

/** "Il reste 1 h 26" for a title started: a duration, never a timecode, never "0 min"; nothing when unknown. */
@Composable
private fun timeLeft(item: MediaItem): String? {
    val left = (item.runtimeMs ?: 0L) - item.user.positionMs
    return if (item.user.positionMs > 0 && left > 0) stringResource(R.string.time_left, formatDuration(maxOf(left, MINUTE_MS))) else null
}

@Composable
private fun Episodes(
    header: String?,
    episodes: List<MediaItem>,
    memory: FocusMemory,
    focused: MediaItem?,
    onFocus: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    // The row and the overview of its focused episode come into view together.
    Column(Modifier.scrollsAsBlock()) {
        MediaRow(header = header, modifier = memory.rowGroup(EPISODES), spacing = HlSpace.l) {
            items(episodes, key = { it.id }) { ep ->
                LandscapeCard(
                    item = ep,
                    modifier = memory.card(EPISODES, ep),
                    onClick = { onPlay(ep) },
                    width = HlSize.episode,
                    title = ep.title,
                    number = ep.episodeNumber?.toString(),
                    facts = listOf(ep.runtimeMs?.let(::formatDuration), episodeState(ep)),
                    onFocused = { onFocus(ep) },
                )
            }
        }
        val shown = focused?.takeIf { f -> episodes.any { it.id == f.id } }
        Text(
            shown?.overview ?: "",
            style = Hl.type.caption,
            color = Hl.colors.contentSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            // Three lines at the viewer's text size, so the page stays still as focus moves; more if a script needs it.
            modifier = Modifier
                .padding(horizontal = HlSpace.safeH)
                .widthIn(max = HlLayout.readable)
                .heightIn(min = textRoom(HlLayout.episodeOverview))
                .fadeOutsideSafeArea(),
        )
    }
}

/** Back returns to the actions once the page has scrolled down; at its top, it leaves. */
internal fun backReturnsToActions(firstVisibleItem: Int, offset: Int): Boolean = firstVisibleItem > 0 || offset > 0

/** A row of the page as a group: a card gone since focus left it, focus comes back to its row. */
private fun FocusMemory.rowGroup(row: String) = Modifier.focusRequester(requester(FocusKey.row(row)))

/** A card of a row of the page: the same title may sit in two rows. */
private fun FocusMemory.card(row: String, item: MediaItem) =
    Modifier.focusKey(FocusKey.within(FocusKey.row(row), FocusKey.item(item.id)), this)

private val PLAY = FocusKey.action("play")
private val RESTART = FocusKey.action("restart")
private val SERIES = FocusKey.action("series")
private val WATCHED = FocusKey.action("watched")
private val TECH = FocusKey.action("tech")
private val NFO = FocusKey.action("nfo")
private val FAVORITE = FocusKey.action("favorite")
private val EDIT = FocusKey.action("edit")
private const val EPISODES = "episodes"
private const val FILMS = "films"
private const val SAGA = "collection"
private const val SIMILAR = "similar"
private const val MINUTE_MS = 60_000L

/** The keys that act on what has focus. */
private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
