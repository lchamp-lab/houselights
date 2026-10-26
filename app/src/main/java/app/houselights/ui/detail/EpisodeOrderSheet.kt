package app.houselights.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.EpisodeOrderChoices
import app.houselights.core.EpisodeOrderKind
import app.houselights.core.EpisodeOrderOption
import app.houselights.core.MediaItem
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.util.formatCount

/**
 * The orders show [item]'s episodes can follow ([orders]), in a choice sheet about the show: Automatique first, said
 * by what it resolves to, then TMDB's seasons by their counts and its episode groups, each by its kind, its TMDB name
 * and its count; the one in use checked and focused. Choosing another applies it ([onChoose], its id, null for
 * Automatique) and closes; the one in use, or Back, changes nothing.
 */
@Composable
internal fun EpisodeOrderSheet(
    item: MediaItem,
    orders: EpisodeOrderChoices?,
    open: Boolean,
    onChoose: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open || orders == null) return
    // Automatique, then each order TMDB has.
    val choices = listOf<EpisodeOrderOption?>(null) + orders.options
    val selected = choices.indexOfFirst { it?.id == orders.chosen }.coerceAtLeast(0)
    HlChoiceSheet(
        title = stringResource(R.string.episode_order),
        options = choices.map { ChoiceOption(orderName(it), orderDetail(it, orders)) },
        selected = selected,
        onSelect = { i -> if (i != selected) onChoose(choices[i]?.id) },
        onDismiss = onDismiss,
        description = stringResource(R.string.episode_order_text),
        facts = listOf(item.title, item.year?.toString()),
    )
}

/**
 * The order in use, as Modifier's row says it on one line: "Automatique · Saisons TMDB", "Automatique · DVD, d’après le
 * NFO", "DVD · DVD Order" for a group chosen (its kind, then its TMDB name), "Saisons TMDB" when chosen.
 */
@Composable
internal fun orderInUse(orders: EpisodeOrderChoices): String {
    val chosen = orders.options.firstOrNull { it.id == orders.chosen }
    val more = when {
        chosen == null -> automaticDetail(orders)
        chosen.kind == EpisodeOrderKind.AIRED -> null
        else -> chosen.name
    }
    return listOfNotNull(orderName(chosen), more).joinToString(ROW_PARTS)
}

/** An order by its kind: "Automatique" for none, "Saisons TMDB", "DVD"… */
@Composable
internal fun orderName(option: EpisodeOrderOption?): String = stringResource(
    when (option?.kind) {
        null -> R.string.episode_order_auto
        EpisodeOrderKind.AIRED -> R.string.episode_order_aired
        EpisodeOrderKind.ORIGINAL_AIR_DATE -> R.string.episode_order_original
        EpisodeOrderKind.ABSOLUTE -> R.string.episode_order_absolute
        EpisodeOrderKind.DVD -> R.string.episode_order_dvd
        EpisodeOrderKind.DIGITAL -> R.string.episode_order_digital
        EpisodeOrderKind.STORY_ARC -> R.string.episode_order_story
        EpisodeOrderKind.PRODUCTION -> R.string.episode_order_production
        EpisodeOrderKind.TV -> R.string.episode_order_tv
    },
)

/**
 * What an order's row says on its one line: Automatique, what it resolves to; TMDB's seasons, how many seasons and
 * episodes ("1 saison · 14 épisodes"); a group, its TMDB name and how many episodes it holds ("DVD Order · 14 épisodes").
 */
@Composable
private fun orderDetail(option: EpisodeOrderOption?, orders: EpisodeOrderChoices): String {
    option ?: return automaticDetail(orders)
    val episodes = pluralStringResource(R.plurals.numbers_episodes, option.episodes, formatCount(option.episodes.toLong()))
    val first = if (option.kind == EpisodeOrderKind.AIRED) {
        pluralStringResource(R.plurals.season_count, option.seasons, option.seasons)
    } else {
        option.name
    }
    return listOfNotNull(first, episodes).joinToString(ROW_PARTS)
}

/** What Automatique resolves to: "Saisons TMDB", or "DVD, d’après le NFO". */
@Composable
private fun automaticDetail(orders: EpisodeOrderChoices): String {
    val name = orderName(orders.automatic)
    return if (orders.fromNfo) stringResource(R.string.episode_order_auto_nfo, name) else name
}

/** Between the parts of a setting row's line, as every row joins them. */
private const val ROW_PARTS = " · "
