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
import androidx.compose.ui.text.TextStyle
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
import app.houselights.ui.util.timeLeft

/**
 * What the focused title is, at the top of browsing screens, in its band of [HlLayout.hero]: its
 * logo (or its name on one line), one line of facts and formats (an episode's number and name in
 * their place), then where it stands (progress and time left) or two lines of its overview. Lighter
 * than a detail page by design: it names, the detail page tells. Cross-fades with focus.
 */
@Composable
fun HeroInfo(item: MediaItem?, modifier: Modifier = Modifier) {
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
                maxLines = 1,
            )
            Spacer(Modifier.height(HlSpace.s))
            if (episode) {
                EpisodeHeading(shown.episodeLabel, shown.title, style = Hl.type.entry)
            } else {
                MetaLine(shown, showGenres = false)
            }
            Spacer(Modifier.height(HlSpace.s))
            if (shown.progress > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(shown.progress, Modifier.width(HlSize.progressShort))
                    Spacer(Modifier.width(HlSpace.s))
                    timeLeft(shown)?.let { Text(it, style = Hl.type.caption, color = Hl.colors.contentSecondary) }
                }
            } else if (!shown.overview.isNullOrBlank()) {
                Text(
                    shown.overview,
                    style = Hl.type.body,
                    color = Hl.colors.contentSecondary,
                    maxLines = HERO_OVERVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The overview of a browsing hero: enough to recognise a film, the rest is on its page. */
private const val HERO_OVERVIEW_LINES = 2

/** "S1 E3" then the episode's name, aligned on their baseline; the name in [style]. */
@Composable
fun EpisodeHeading(label: String?, title: String, modifier: Modifier = Modifier, style: TextStyle = Hl.type.title) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
        label?.let { Text(it, style = Hl.type.meta, color = Hl.colors.contentSecondary, modifier = Modifier.alignByBaseline()) }
        Text(
            title,
            style = style,
            color = Hl.colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alignByBaseline(),
        )
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
