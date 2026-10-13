package app.houselights.ui.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatDuration
import java.util.Locale

/**
 * Facts about a title, separated by space rather than punctuation: year, length,
 * age rating (as the badge it really is), score, then genres in a quieter tone.
 */
@Composable
fun MetaLine(item: MediaItem, modifier: Modifier = Modifier, showGenres: Boolean = true) {
    val c = Hl.colors
    val seasons = item.childCount?.takeIf { item.kind == MediaKind.SERIES && it > 0 }
        ?.let { pluralStringResource(R.plurals.season_count, it, it) }
    val years = when {
        item.kind != MediaKind.SERIES -> item.year?.toString()
        item.year != null && item.continuing -> "${item.year} –"
        item.year != null && item.endYear != null && item.endYear != item.year -> "${item.year} – ${item.endYear}"
        else -> item.year?.toString()
    }
    val length = if (item.kind == MediaKind.SERIES) seasons else item.runtimeMs?.let(::formatDuration)

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        years?.let { Text(it, style = Hl.type.meta, color = c.content) }
        length?.let { Text(it, style = Hl.type.meta, color = c.contentSecondary) }
        item.officialRating?.let { Badge(it) }
        item.communityRating?.let { score ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = c.tungsten, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(HlSpace.xxs))
                Text(String.format(Locale.getDefault(), "%.1f", score), style = Hl.type.meta, color = c.contentSecondary)
            }
        }
        if (showGenres && item.genres.isNotEmpty()) {
            Text(
                item.genres.take(3).joinToString(", "),
                style = Hl.type.meta,
                color = c.contentTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Hairline badge for ratings and technical formats; [lit] in tungsten for a state (in use). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, lit: Boolean = false) {
    val c = Hl.colors
    Text(
        text,
        style = Hl.type.badge,
        color = if (lit) c.tungsten else c.contentSecondary,
        modifier = modifier
            .border(HlSize.hairline, if (lit) c.tungsten.copy(alpha = 0.6f) else c.outline, HlShapes.badge)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.xs)) { badges.forEach { Badge(it) } }
}

/**
 * The credits at the foot of a poster: names in tall condensed capitals, joined by small
 * words ("a film by", "with"). The one place where Houselights sets words in capitals.
 */
@Composable
fun BillingBlock(directors: List<String>, cast: List<String>, modifier: Modifier = Modifier) {
    if (directors.isEmpty() && cast.isEmpty()) return
    val c = Hl.colors
    val t = Hl.type
    val locale = Locale.getDefault()
    val by = stringResource(R.string.billing_by)
    val with = stringResource(R.string.billing_with)
    val credits = buildAnnotatedString {
        // A line may break between names, never inside one or after "with".
        fun join(word: String) = withStyle(t.billingJoin.toSpanStyle().copy(color = c.contentTertiary)) { append(word.unbreakable() + NBSP) }
        fun names(list: List<String>) = list.forEachIndexed { i, name ->
            if (i > 0) append(GAP)
            withStyle(t.billing.toSpanStyle().copy(color = c.contentSecondary)) { append(name.uppercase(locale).unbreakable()) }
        }
        if (directors.isNotEmpty()) {
            join(by)
            names(directors.take(1))
        }
        if (cast.isNotEmpty()) {
            if (directors.isNotEmpty()) append(GAP)
            join(with)
            names(cast.take(3))
        }
    }
    Text(credits, style = t.billing, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

private const val NBSP = "\u00A0"

/** Between names: three spaces wide, no punctuation, and the only place a line may break. */
private const val GAP = "$NBSP$NBSP "

private fun String.unbreakable() = replace(' ', '\u00A0').replace('-', '\u2011')
