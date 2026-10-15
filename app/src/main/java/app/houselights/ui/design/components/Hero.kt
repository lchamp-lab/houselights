package app.houselights.ui.design.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.util.formatDuration

/** What the focused title is, at the top of browsing screens. Cross-fades with focus. */
@Composable
fun HeroInfo(item: MediaItem?, modifier: Modifier = Modifier, overviewLines: Int = 3) {
    AnimatedContent(
        targetState = item,
        // The hero keeps its height: no size to animate, so a change re-measures nothing per frame.
        transitionSpec = { (fadeIn(tween(HlMotion.STANDARD)) togetherWith fadeOut(tween(HlMotion.FAST))).using(null) },
        contentKey = { it?.id },
        label = "hero",
        modifier = modifier,
    ) { shown ->
        if (shown == null) return@AnimatedContent
        Column(Modifier.widthIn(max = HlLayout.readableNarrow), verticalArrangement = Arrangement.Bottom) {
            val episode = shown.kind == MediaKind.EPISODE
            TitleArt(
                title = if (episode) shown.seriesTitle ?: shown.title else shown.title,
                logo = shown.logo,
                maxWidth = HlLayout.heroTitleWidth,
                maxHeight = HlLayout.heroTitleHeight,
                style = Hl.type.headline,
            )
            Spacer(Modifier.height(HlSpace.m))
            if (episode) {
                EpisodeHeading(shown.episodeLabel, shown.title)
                Spacer(Modifier.height(HlSpace.xs))
            }
            MetaLine(shown)
            if (shown.badges.isNotEmpty()) {
                Spacer(Modifier.height(HlSpace.s))
                BadgeRow(shown.badges.take(5))
            }
            if (shown.progress > 0f) {
                Spacer(Modifier.height(HlSpace.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(shown.progress, Modifier.width(HlSize.progressShort))
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

/**
 * The place focus is on, where the title under focus would be: a library's name in the voice of
 * the poster, and what it holds under it, over the light of its posters. Nothing is selected yet:
 * the name says where OK leads.
 */
@Composable
fun PlaceHeading(name: String, facts: List<String?>, modifier: Modifier = Modifier) {
    Box(modifier) {
        Column(Modifier.align(Alignment.BottomStart).widthIn(max = HlLayout.readableNarrow)) {
            Text(name, style = Hl.type.display, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(HlSpace.xs))
            FactRow(facts)
        }
    }
}

/** "S1 E3" then the episode's name, aligned on their baseline. */
@Composable
fun EpisodeHeading(label: String?, title: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
        label?.let { Text(it, style = Hl.type.meta, color = Hl.colors.contentSecondary, modifier = Modifier.alignByBaseline()) }
        Text(title, style = Hl.type.title, color = Hl.colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alignByBaseline())
    }
}

/**
 * Where a title and its facts will be while a detail page loads: two short rules, quiet, no
 * shimmer. Screens show it after [HlMotion.SKELETON_DELAY].
 */
@Composable
fun HeroSkeleton(modifier: Modifier = Modifier) {
    val rule = Hl.colors.contentDecorative
    Column(modifier) {
        Box(Modifier.width(HlSize.skeletonTitle).height(HlSize.hairline).background(rule))
        Spacer(Modifier.height(HlSpace.l))
        Box(Modifier.width(HlSize.skeletonFacts).height(HlSize.hairline).background(rule))
    }
}
