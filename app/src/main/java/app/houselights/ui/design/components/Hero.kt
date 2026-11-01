package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.HeroDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlGap
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HlStroke
import app.houselights.ui.util.episodeCode
import app.houselights.ui.util.timeLeft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The focused title atop « À regarder » and a person's page, in its band ([HeroDefaults.band]) and standing on its foot:
 * logo or name, a line of facts (an episode's number and name), then progress and time left or two lines of overview;
 * changes with its picture ([WithItsPicture]). A change its card's options asked that was not saved ([notSaved]) is
 * said in the facts' place, by the attention mark.
 */
@Composable
fun HeroInfo(item: MediaItem?, modifier: Modifier = Modifier, notSaved: String? = null) {
    // The hero keeps its height, so nothing re-measures as the title changes; what it lacks leaves room above it.
    WithItsPicture(item, modifier.height(HeroDefaults.band), Alignment.BottomStart) { shown ->
        Column(Modifier.widthIn(max = HlLayout.readableNarrow)) {
            val episode = shown.kind == MediaKind.EPISODE
            TitleArt(
                title = shown.headTitle,
                logo = shown.logo,
                maxWidth = HeroDefaults.titleWidth,
                maxHeight = HeroDefaults.titleHeight,
                style = Hl.type.headline,
                maxLines = 1,
            )
            Spacer(Modifier.height(HlGap.inBlock))
            when {
                notSaved != null && shown.id == item?.id -> HlStatus(StatusState.Attention, notSaved, maxLines = 1)
                episode -> EpisodeHeading(shown.episodeCode(), shown.title, style = Hl.type.entry)
                else -> MetaLine(shown, showGenres = false)
            }
            Spacer(Modifier.height(HlGap.inBlock))
            if (shown.progress > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(shown.progress, Modifier.width(HlSize.heroProgress), thickness = HlStroke.bold)
                    Spacer(Modifier.width(HlSpace.s))
                    timeLeft(shown)?.let { Text(it, style = Hl.type.meta, color = Hl.colors.contentSecondary) }
                }
            } else if (!shown.overview.isNullOrBlank()) {
                Text(
                    shown.overview,
                    style = Hl.type.body,
                    color = Hl.colors.contentSecondary,
                    maxLines = HeroDefaults.OVERVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Shows [content] for [item] once the backdrop settles on its picture ([LocalBackdropSettled]), at most
 * [HlMotion.HOLD] later, so text never shows over the previous picture; one crossfade, no re-measure, the words coming
 * in as a plate does ([HlMotion.PLATE_IN]) and leaving as one ([HlMotion.PLATE_OUT]). Each title stands at [alignment]
 * in [modifier]'s box.
 */
@Composable
fun WithItsPicture(
    item: MediaItem?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    content: @Composable (MediaItem) -> Unit,
) {
    val named = rememberNamed(item)
    val transition = updateTransition(named?.id, label = "named")
    // The latest version of each composed title by id: leaving, shown, or waiting for its picture.
    val latest = remember { HashMap<String, MediaItem>() }
    named?.let { latest[it.id] = it }
    item?.let { latest[it.id] = it }
    val laid = listOfNotNull(transition.currentState, transition.targetState, item?.id).distinct()
    SideEffect { latest.keys.retainAll(laid.toSet()) }
    Box(modifier) {
        laid.forEach { id ->
            key(id) {
                val alpha = transition.animateFloat(
                    transitionSpec = { HlMotion.inOut(targetState == id, HlMotion.PLATE_IN, HlMotion.PLATE_OUT) },
                    label = "namedAlpha",
                ) { if (it == id) 1f else 0f }
                // Only the target title is exposed to accessibility; the others are fading in or out.
                val said = if (id == transition.targetState) Modifier else Modifier.clearAndSetSemantics {}
                Box(Modifier.align(alignment).graphicsLayer { this.alpha = alpha.value }.then(said)) {
                    latest[id]?.let { content(it) }
                }
            }
        }
    }
}

/** The title to show for [item]: [item] once its picture is up, the previous one until then. */
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
    // An update to the same title (progress, overview) applies at once.
    return if (item != null && item.id == named?.id) item else named
}

/** "S1 É3" then the episode's name, aligned on their baseline; the name in [style]. */
@Composable
fun EpisodeHeading(label: String?, title: String, modifier: Modifier = Modifier, style: TextStyle = Hl.type.lead) {
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

/** Placeholder while a detail page loads: two quiet short rules, shown once `skeletonDelayPassed()`. */
@Composable
fun HeroSkeleton(modifier: Modifier = Modifier) {
    val rule = Hl.colors.contentDecorative
    Column(modifier) {
        Box(Modifier.width(HlSize.skeletonTitle).height(HlStroke.hair).background(rule))
        Spacer(Modifier.height(HlSpace.l))
        Box(Modifier.width(HlSize.skeletonFacts).height(HlStroke.hair).background(rule))
    }
}
