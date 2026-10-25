package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HomeGridDefaults
import app.houselights.ui.design.Scrub
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.plateEdge
import app.houselights.ui.design.textRoom
import app.houselights.ui.util.timeLeft
import app.houselights.ui.util.toPx
import coil.request.ImageRequest

// Home's case (DESIGN.md, Layout): the title it resumes or proposes, and the picture a preload asks for it.

/**
 * Home's case (DESIGN.md, Layout): a title's backdrop cropped upright (else its poster) melting into its dark, under
 * its [label], its logo or name, its facts, then its progress and time left or its genres, and its [action], a plate
 * the case's focus fills white, after its [icon] (Play; none for a page it opens). What it [says] (why it did not
 * play, two lines of `meta` at most) takes the facts' place; their room is kept at two such lines, so the logo never
 * moves. OK does [onClick]; Menu or a long OK, [onLongClick], which the Menu key says at the plate's end while the case
 * has focus.
 */
@Composable
fun ResumeCase(
    item: MediaItem,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = HlIcons.Play,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    says: (@Composable () -> Unit)? = null,
) {
    val c = Hl.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val picture = caseUrl(item)
    val glow = rememberGlowColor(picture, focused && !Scrub.active)
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(rest = c.ambientDeep, restInk = c.content, edge = BorderStroke(HlSize.hairline, c.plateEdge)),
        shape = HlShapes.control,
        modifier = modifier,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        glow = { glow.value },
        interactionSource = interaction,
    ) { lit ->
        Box(Modifier.fillMaxSize()) {
            CasePicture(item, picture)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(HlSpace.m)) {
                label?.let {
                    Text(it, style = Hl.type.jaquette, color = c.contentSecondary, maxLines = 1)
                    Spacer(Modifier.height(HlSpace.xs))
                }
                val episode = item.kind == MediaKind.EPISODE
                TitleArt(
                    title = if (episode) item.seriesTitle ?: item.title else item.title,
                    logo = item.logo,
                    maxWidth = HomeGridDefaults.caseLogoWidth,
                    maxHeight = HomeGridDefaults.caseLogoHeight,
                    style = Hl.type.headline,
                )
                Spacer(Modifier.height(HlSpace.s))
                // A message's two lines at least, whatever takes the room.
                Box(Modifier.fillMaxWidth().heightIn(min = textRoom(Hl.type.meta) * 2 + HlSpace.xxs)) {
                    if (says == null) {
                        CaseFacts(item)
                    } else {
                        // Laid out unseen, the facts keep their height: nothing above the message moves.
                        Box(Modifier.alpha(0f).clearAndSetSemantics {}) { CaseFacts(item) }
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) { says() }
                    }
                }
                Spacer(Modifier.height(HlSpace.s))
                CaseAction(action, lit, icon, menu = onLongClick != null)
            }
        }
    }
}

/** The case's facts: an episode's heading or a title's (`MetaLine`), then its progress and time left, else its genres. */
@Composable
private fun CaseFacts(item: MediaItem) {
    val c = Hl.colors
    Column {
        if (item.kind == MediaKind.EPISODE) {
            EpisodeHeading(item.episodeLabel, item.title, style = Hl.type.entry)
        } else {
            // Closer than a page's facts: the case's column is narrow, and its score stays.
            MetaLine(item, showGenres = false, spacing = HlSpace.s)
        }
        val left = timeLeft(item)
        if (item.progress > 0f) {
            Spacer(Modifier.height(HlSpace.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressLine(item.progress, Modifier.weight(1f), thickness = HlSize.heroProgressWeight)
                left?.let {
                    Spacer(Modifier.width(HlSpace.s))
                    Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1)
                }
            }
        } else if (item.genres.isNotEmpty()) {
            Spacer(Modifier.height(HlSpace.s))
            Text(
                item.genres.take(CASE_GENRES).joinToString(FACT_JOIN),
                style = Hl.type.meta,
                color = c.contentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The case's picture: its backdrop cropped upright from its top, else its poster; a title alone set as a one-sheet. */
@Composable
private fun BoxScope.CasePicture(item: MediaItem, picture: String?) {
    val c = Hl.colors
    val width = HomeGridDefaults.case.toPx()
    val height = caseHeight().toPx()
    ArtworkImage(
        picture,
        null,
        Modifier.fillMaxSize(),
        size = IntSize(width, height),
        alignment = Alignment.TopCenter,
    ) { FallbackArtwork(item.title, item.year) }
    // The picture melts into the case's dark under its words.
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(*HlScrim.CASE_FOOT.colorStops(c.ambientDeep))))
}

/**
 * The case's action: its words after its [icon] on a quiet plate, white with black ink once the case is [lit], then
 * with the Menu key at its end when it has options ([menu]); nothing moves for it.
 */
@Composable
private fun CaseAction(action: String, lit: Boolean, icon: ImageVector?, menu: Boolean) {
    val c = Hl.colors
    val ink = FocusStyle.ink(lit)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HomeGridDefaults.action)
            .background(FocusStyle.heldPlate(lit, c.content.copy(alpha = HlAlpha.FILL)), HlShapes.control)
            .then(if (lit) Modifier else Modifier.border(HlSize.hairline, c.plateEdge, HlShapes.control)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(HomeGridDefaults.actionIcon))
                Spacer(Modifier.width(HlSpace.xs))
            }
            Text(action, style = Hl.type.cardName, color = ink, maxLines = 1)
        }
        if (menu && lit) MenuKey(Modifier.align(Alignment.CenterEnd).padding(end = HlSpace.xs), ink = ink)
    }
}

/** How tall the case stands: the grid's three rows and their gaps. */
internal fun caseHeight(): Dp = HomeGridDefaults.watchRow + HomeGridDefaults.libraryRow + HomeGridDefaults.toolRow + HlSpace.s * 2

/** The case's picture: its backdrop ([caseBackdropWidth]), else its poster. */
@Composable
private fun caseUrl(item: MediaItem): String? {
    val height = caseHeight().toPx()
    val width = HomeGridDefaults.case.toPx()
    return item.backdrop?.url(caseBackdropWidth(height)) ?: item.poster?.url(width)
}

/** The request [ResumeCase] makes for [item] on a canvas of [density] px a dp, as a preload must make it. */
internal fun caseRequest(context: Context, item: MediaItem, px: (Dp) -> Int): ImageRequest? {
    val height = px(caseHeight())
    val url = item.backdrop?.url(caseBackdropWidth(height)) ?: item.poster?.url(px(HomeGridDefaults.case)) ?: return null
    return doorRequest(context, url, px(HomeGridDefaults.case), height)
}

/** The case's backdrop for a case [heightPx] tall: as wide as it is cropped from, short of TMDB's original (1280). */
private fun caseBackdropWidth(heightPx: Int): Int = minOf(heightPx * WIDE_W / WIDE_H, CASE_BACKDROP_PX)

private const val CASE_BACKDROP_PX = 1280
private const val CASE_GENRES = 3
private const val FACT_JOIN = " · "
