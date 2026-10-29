package app.houselights.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.components.filmCount
import app.houselights.ui.detail.EpisodeOrderSheet
import app.houselights.ui.detail.OptionsSheet
import app.houselights.ui.detail.SheetOption
import app.houselights.ui.detail.orderInUse
import app.houselights.ui.edit.EditMenu
import app.houselights.ui.util.formatDuration
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.titleText

/** The rows of a poster's sheet, in their order: its mark first, so Menu then OK marks it; its favourite; Modifier. */
internal enum class PosterRow { MARK, FAVORITE, EDIT }

/**
 * What a poster's options do (#47), as the title's page does it: mark it ([onMark], null where the source keeps nothing
 * watched), make it a favourite or not ([onFavorite], null where it keeps none), and Modifier where its source keeps its
 * pictures ([canEdit]): the page's own sheet, whose images open their choice ([onEditArtwork]), whose Identifier opens
 * its search ([onIdentify]) and, for a show TMDB numbers more than one way, whose order of episodes ([episodeOrders])
 * is chosen there ([onEpisodeOrder]).
 */
@Immutable
internal class PosterActions(
    val onMark: ((MediaItem) -> Unit)?,
    val onFavorite: ((MediaItem) -> Unit)?,
    val canEdit: (MediaItem) -> Boolean,
    val onEditArtwork: (String, ArtworkKind) -> Unit,
    val onIdentify: (String) -> Unit,
    val episodeOrders: suspend (MediaItem) -> EpisodeOrderChoices? = { null },
    val onEpisodeOrder: (MediaItem, String?) -> Unit = { _, _ -> },
) {
    /** The rows [item]'s sheet has, in order; with none, its card has no options and shows no key. */
    fun rows(item: MediaItem): List<PosterRow> = listOfNotNull(
        PosterRow.MARK.takeIf { onMark != null && marks(item) },
        PosterRow.FAVORITE.takeIf { onFavorite != null && favours(item) },
        PosterRow.EDIT.takeIf { editsFromPoster(item) && canEdit(item) },
    )

    /** Whether [item]'s card opens options: Menu or a long OK on it, the Menu key in its corner. */
    fun hasOptions(item: MediaItem): Boolean = rows(item).isNotEmpty()
}

/**
 * Whether Modifier is offered from [item]'s poster, its source keeping its pictures: a film's and a show's. A saga's
 * comes with its own pictures, its page's Modifier (#48): here is where it joins them, its sheet having its mark alone
 * until then, as a Jellyfin box set's keeps, whose source has no editor.
 */
internal fun editsFromPoster(item: MediaItem): Boolean = item.kind == MediaKind.MOVIE || item.kind == MediaKind.SERIES

/** The actions of the posters of a screen whose changes [changes] saves, [onMark] and [onFavorite] showing them at once. */
internal fun PosterChanges.actions(
    onMark: (MediaItem) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    onEditArtwork: (String, ArtworkKind) -> Unit,
    onIdentify: (String) -> Unit,
): PosterActions {
    val can = capabilities
    return PosterActions(
        onMark = onMark.takeIf { can.markPlayed },
        onFavorite = onFavorite.takeIf { can.favorites },
        canEdit = this::canEdit,
        onEditArtwork = onEditArtwork,
        onIdentify = onIdentify,
        episodeOrders = this::episodeOrders,
        onEpisodeOrder = this::chooseEpisodeOrder,
    )
}

/**
 * A poster's options, which Menu or a long OK opens on [item] (#47): what makes sense without opening its page, in a
 * side sheet titled with its name, its facts saying where it stands (a film started, what is left; a show, its episodes
 * not watched; a saga, its films watched). Its mark first and focused, a show's whole, a saga's with its films, in their
 * page's words, watched or not as it stands; then a film's or a show's favourite; then Modifier, the page's own sheet,
 * opened as this one closes, a sheet never opening over another. Each row closes the sheet, then acts, with no
 * question; Back closes it, doing nothing. No tracks, no facts of its files, no Lire: OK on the poster opens its page.
 */
@Composable
internal fun PosterOptions(item: MediaItem?, actions: PosterActions, onDismiss: () -> Unit) {
    // The title Modifier was opened on, kept while its sheets show, and which of them does.
    var edited by remember { mutableStateOf<MediaItem?>(null) }
    var shown by remember { mutableStateOf(EditSheet.NONE) }
    if (item != null) {
        val rows = actions.rows(item).map { row ->
            when (row) {
                PosterRow.MARK -> markRow(item, actions)
                PosterRow.FAVORITE -> favoriteRow(item, actions)
                PosterRow.EDIT -> SheetOption(stringResource(R.string.edit), HlIcons.Edit) {
                    edited = item
                    shown = EditSheet.EDIT
                }
            }
        }
        OptionsSheet(sheetTitle(item), posterFacts(item), rows, onDismiss)
    }
    edited?.let { title -> PosterEdit(title, shown, onShow = { shown = it }, actions = actions) }
}

/** Which of Modifier's sheets shows: none, Modifier, or the order of a show's episodes it opens. */
private enum class EditSheet { NONE, EDIT, ORDER }

/**
 * Modifier on [item] from its poster, the title page's own sheet ([EditMenu]): its images open their choice, Identifier
 * its search, and for a show TMDB numbers more than one way, Ordre des épisodes, which closes it, then opens the choice.
 */
@Composable
private fun PosterEdit(item: MediaItem, shown: EditSheet, onShow: (EditSheet) -> Unit, actions: PosterActions) {
    val orders by produceState<EpisodeOrderChoices?>(null, item.id) {
        value = if (item.kind == MediaKind.SERIES) actions.episodeOrders(item) else null
    }
    if (shown == EditSheet.EDIT) {
        EditMenu(
            item = item,
            onDismiss = { onShow(EditSheet.NONE) },
            onArtwork = { kind ->
                onShow(EditSheet.NONE)
                actions.onEditArtwork(item.id, kind)
            },
            onIdentify = {
                onShow(EditSheet.NONE)
                actions.onIdentify(item.id)
            },
            episodeOrder = orders?.let { orderInUse(it) },
            onEpisodeOrder = { onShow(EditSheet.ORDER) }.takeIf { orders != null },
        )
    }
    EpisodeOrderSheet(
        item,
        orders,
        open = shown == EditSheet.ORDER,
        onChoose = { id -> actions.onEpisodeOrder(item, id) },
        onDismiss = { onShow(EditSheet.NONE) },
    )
}

/** The mark, in the words of its page's tile: a film's or an episode's, a whole show's, a saga's with its films. */
@Composable
private fun markRow(item: MediaItem, actions: PosterActions): SheetOption {
    val watched = item.user.played
    val words = when (item.kind) {
        MediaKind.SERIES -> if (watched) R.string.mark_series_unwatched else R.string.mark_series_watched
        MediaKind.COLLECTION -> if (watched) R.string.mark_saga_unwatched else R.string.mark_saga_watched
        else -> if (watched) R.string.mark_unwatched else R.string.mark_watched
    }
    return SheetOption(stringResource(words), if (watched) HlIcons.CheckFilled else HlIcons.Check) { actions.onMark?.invoke(item) }
}

/** The favourite, as its page's heart: filled once it is one. */
@Composable
private fun favoriteRow(item: MediaItem, actions: PosterActions): SheetOption {
    val favorite = item.user.favorite
    return SheetOption(
        stringResource(if (favorite) R.string.remove_favorite else R.string.add_favorite),
        if (favorite) HlIcons.HeartFilled else HlIcons.Heart,
    ) { actions.onFavorite?.invoke(item) }
}

/** What a poster's sheet is titled with: the title's name, an episode's show's. */
internal fun sheetTitle(item: MediaItem): String = if (item.kind == MediaKind.EPISODE) item.seriesTitle ?: item.title else item.title

/**
 * Where a title stands, under its name: « 1988   2 h 48   Il reste 1 h 14 », « 2 saisons   6 épisodes non vus »,
 * « 2021 – 2024   2 films   1 film vu sur 2 », « S1 E4   52 min ».
 */
@Composable
internal fun posterFacts(item: MediaItem): List<String?> = when (item.kind) {
    MediaKind.SERIES -> showFacts(item)
    MediaKind.COLLECTION -> sagaFacts(item)
    MediaKind.EPISODE -> listOf(item.episodeLabel, item.runtimeMs?.let(::formatDuration), timeLeft(item))
    else -> listOf(item.year?.toString(), item.runtimeMs?.let(::formatDuration), timeLeft(item))
}

/** A show's seasons and its episodes not watched, none once all are. */
@Composable
private fun showFacts(item: MediaItem): List<String?> {
    val seasons = item.childCount?.takeIf { it > 0 }
    val unwatched = item.user.unplayedCount?.takeIf { it > 0 && !item.user.played }
    return listOf(
        seasons?.let { pluralStringResource(R.plurals.season_count, it, it) },
        unwatched?.let { pluralStringResource(R.plurals.unwatched_episodes, it, it) },
    )
}

/** A saga's years, the films it holds (of those out), and how many of them are watched, none before one is. */
@Composable
private fun sagaFacts(item: MediaItem): List<String?> {
    val held = item.childCount ?: 0
    val watched = held - (item.user.unplayedCount ?: held)
    return listOf(
        item.year?.let { first -> item.endYear?.let { "$first – $it" } ?: "$first" },
        filmCount(held, item.totalCount),
        when {
            held > 0 && watched == held -> pluralStringResource(R.plurals.saga_all_watched, held, held)
            watched > 0 -> pluralStringResource(R.plurals.saga_watched_of, watched, watched, held)
            else -> null
        },
    )
}

/** « Non enregistré · Serveur injoignable »: why the change [this] is about was not saved, said for [id] alone. */
@Composable
internal fun NotSaved?.saidFor(id: String?): String? =
    this?.takeIf { it.id == id }?.let { "${stringResource(R.string.action_not_saved)} · ${it.error.titleText()}" }
