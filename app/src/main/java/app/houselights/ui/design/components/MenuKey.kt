package app.houselights.ui.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIconButtonDefaults
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace

/**
 * The remote's Menu key, drawn as a key (DESIGN.md, Focus, Menu): its lines ([HlIcons.Menu]) in secondary ink in a
 * frame of tertiary ink, quiet beside the name it follows; on focus's white plate, both in its [ink]. Shown by what has
 * focus, it says Menu opens its options; accessibility services read that element's long click instead.
 */
@Composable
fun MenuKey(modifier: Modifier = Modifier, ink: Color? = null) {
    val c = Hl.colors
    Box(
        modifier
            .testTag(MENU_KEY)
            .size(HlSize.menuKeyWidth, HlSize.menuKeyHeight)
            .border(HlSize.keyEdge, ink ?: c.contentTertiary, HlShapes.mark),
        contentAlignment = Alignment.Center,
    ) {
        Icon(HlIcons.Menu, contentDescription = null, tint = ink ?: c.contentSecondary, modifier = Modifier.size(HlSize.inlineIcon))
    }
}

/**
 * The Menu key on the marks' dark plate, white, while [shown] says and no sheet is open, coming and going as a tile's
 * label: a card's in its corner ([CornerKey]).
 */
@Composable
fun PlatedMenuKey(shown: () -> Boolean, modifier: Modifier = Modifier) {
    val alpha = rememberPlateAlpha(shown())
    val fading by remember { derivedStateOf { alpha.value > 0f } }
    if (shown() && OpenSheets.count == 0 || fading) {
        MenuKey(modifier.keyPlate { alpha.value }, ink = Hl.colors.content)
    }
}

/**
 * The remote's OK key, as small as a hint's line ([HlIconButtonDefaults.hintRoom]): « OK » as printed on it in the
 * technical voice ([Hl.type.badge]), in secondary ink in a frame of tertiary ink, on the marks' dark plate, quiet beside
 * the words it leads.
 */
@Composable
fun OkKey(modifier: Modifier = Modifier) {
    val c = Hl.colors
    Box(
        modifier
            .height(HlIconButtonDefaults.hintRoom)
            .widthIn(min = HlSize.menuKeyWidth)
            .clip(HlShapes.mark)
            .markPlate()
            .border(HlSize.keyEdge, c.contentTertiary, HlShapes.mark)
            .padding(horizontal = HlSpace.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.key_ok), style = Hl.type.badge, color = c.contentSecondary, maxLines = 1, softWrap = false)
    }
}

/**
 * What OK does on what has focus, while [shown] says and no sheet is open: the OK key ([OkKey]), then its [words] in a
 * hint's tertiary `meta`, on one line a hint's height, coming and going as a tile's label. A hint, never a control: read
 * after what it concerns, never focused; an open saga panel's, in its corner on the line of its neighbours' years.
 */
@Composable
fun OkHint(words: String, shown: Boolean, modifier: Modifier = Modifier) {
    val alpha = rememberPlateAlpha(shown)
    val fading by remember { derivedStateOf { alpha.value > 0f } }
    if (shown && OpenSheets.count == 0 || fading) {
        Row(
            modifier.testTag(OK_HINT).height(HlIconButtonDefaults.hintRoom).graphicsLayer {
                this.alpha = alpha.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OkKey(Modifier.clearAndSetSemantics {})
            Spacer(Modifier.width(HlSpace.xs))
            Text(words, style = Hl.type.meta, color = Hl.colors.contentTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A key's plate's opacity, coming as a tile's label does while [shown] and no sheet is open. */
@Composable
private fun rememberPlateAlpha(shown: Boolean) = (shown && OpenSheets.count == 0).let { showing ->
    animateFloatAsState(if (showing) 1f else 0f, HlMotion.inOut(showing, HlMotion.PLATE_IN, HlMotion.PLATE_OUT), label = "keyPlate")
}

/** The marks' dark plate under a key, faded by [alpha] (read while drawing). */
@Composable
private fun Modifier.keyPlate(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}
    .clip(HlShapes.mark)
    .markPlate()

/** Finds the keys in tests: they are read by no one. */
internal const val MENU_KEY = "menu-key"
internal const val OK_HINT = "ok-hint"
