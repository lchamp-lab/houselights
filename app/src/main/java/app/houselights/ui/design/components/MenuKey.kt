package app.houselights.ui.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Icon
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize

/**
 * The remote's Menu key, drawn as a key (DESIGN.md, Focus, Menu): its lines ([HlIcons.Menu]) in secondary ink in a
 * frame of tertiary ink, quiet beside the name it follows. Shown by what has focus, it says Menu opens its options;
 * accessibility services read that element's long click instead.
 */
@Composable
fun MenuKey(modifier: Modifier = Modifier) {
    val c = Hl.colors
    Box(
        modifier
            .testTag(MENU_KEY)
            .size(HlSize.menuKeyWidth, HlSize.menuKeyHeight)
            .border(HlSize.keyEdge, c.contentTertiary, HlShapes.mark),
        contentAlignment = Alignment.Center,
    ) {
        Icon(HlIcons.Menu, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.keyGlyph))
    }
}

/** Finds the key in tests: it is read by no one. */
internal const val MENU_KEY = "menu-key"
