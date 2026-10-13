package app.houselights.ui.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatDuration

/** What the focused title is, at the top of browsing screens. Cross-fades with focus. */
@Composable
fun HeroInfo(item: MediaItem?, modifier: Modifier = Modifier, overviewLines: Int = 3) {
    AnimatedContent(
        targetState = item,
        transitionSpec = { fadeIn(tween(HlMotion.STANDARD)) togetherWith fadeOut(tween(HlMotion.FAST)) },
        contentKey = { it?.id },
        label = "hero",
        modifier = modifier,
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.Bottom) {
            val episode = shown.kind == MediaKind.EPISODE
            TitleArt(
                title = if (episode) shown.seriesTitle ?: shown.title else shown.title,
                logo = shown.logo,
                maxWidth = 380.dp,
                maxHeight = 100.dp,
                style = Hl.type.headline,
            )
            Spacer(Modifier.height(HlSpace.m))
            if (episode) {
                Text(
                    listOfNotNull(shown.episodeLabel, shown.title).joinToString("   "),
                    style = Hl.type.title,
                    color = Hl.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(HlSpace.xs))
            }
            MetaLine(shown)
            if (shown.progress > 0f) {
                Spacer(Modifier.height(HlSpace.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(shown.progress, Modifier.width(120.dp))
                    Spacer(Modifier.width(HlSpace.s))
                    val left = (shown.runtimeMs ?: 0L) - shown.user.positionMs
                    if (left > 0) Text(stringResource(R.string.time_left, formatDuration(left)), style = Hl.type.caption, color = Hl.colors.contentSecondary)
                }
            }
            if (!shown.overview.isNullOrBlank()) {
                Spacer(Modifier.height(HlSpace.s))
                Text(
                    shown.overview,
                    style = Hl.type.body,
                    color = Hl.colors.contentSecondary,
                    maxLines = overviewLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
