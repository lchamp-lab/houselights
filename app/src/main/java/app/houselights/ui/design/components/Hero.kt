package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
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
 * than a detail page by design: it names, the detail page tells. Changes with its picture
 * ([WithItsPicture]).
 */
@Composable
fun HeroInfo(item: MediaItem?, modifier: Modifier = Modifier) {
    // The hero keeps its height: nothing to re-measure as one title gives way to the next.
    WithItsPicture(item, modifier) { shown ->
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

/**
 * What is said of [item], its name and its facts, said once its picture comes up: on home and the
 * walls, a title's words never sit first on the picture of the last one. Laid out as soon as it is
 * asked for, unseen, it shows when the backdrop under it starts bringing up its picture
 * ([LocalBackdropSettled]), after [HlMotion.HOLD] at most, at once for a title without a picture.
 * Only opacities change then, its own going up as the last one's goes down: one fade, nothing in
 * cascade, nothing measured again.
 */
@Composable
fun WithItsPicture(item: MediaItem?, modifier: Modifier = Modifier, content: @Composable (MediaItem) -> Unit) {
    val named = rememberNamed(item)
    val transition = updateTransition(named?.id, label = "named")
    // Each title laid out, by id, as last asked for: what goes, what shows, what waits for its picture.
    val latest = remember { HashMap<String, MediaItem>() }
    named?.let { latest[it.id] = it }
    item?.let { latest[it.id] = it }
    val laid = listOfNotNull(transition.currentState, transition.targetState, item?.id).distinct()
    SideEffect { latest.keys.retainAll(laid.toSet()) }
    Box(modifier) {
        laid.forEach { id ->
            key(id) {
                val alpha = transition.animateFloat(
                    transitionSpec = { tween(if (targetState == id) HlMotion.STANDARD else HlMotion.FAST) },
                    label = "namedAlpha",
                ) { if (it == id) 1f else 0f }
                // Only the title said is read out; the others are on their way, in or out.
                val said = if (id == transition.targetState) Modifier else Modifier.clearAndSetSemantics {}
                Box(Modifier.graphicsLayer { this.alpha = alpha.value }.then(said)) {
                    latest[id]?.let { content(it) }
                }
            }
        }
    }
}

/** The title said for [item]: [item] once its picture comes up, the last one until then. */
@Composable
private fun rememberNamed(item: MediaItem?): MediaItem? {
    val settled = LocalBackdropSettled.current
    var named by remember { mutableStateOf(item) }
    LaunchedEffect(item?.id, item?.backdrop) {
        val picture = item?.backdrop
        if (picture != null && item.id != named?.id) {
            withTimeoutOrNull(HlMotion.HOLD) { snapshotFlow { settled(picture) }.first { it } }
        }
        named = item
    }
    // The same title, changed (its progress, its overview): at once.
    return if (item != null && item.id == named?.id) item else named
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
