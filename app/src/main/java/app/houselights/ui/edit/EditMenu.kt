package app.houselights.ui.edit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.core.MediaItem
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlDivider
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.PreviewShape
import app.houselights.ui.design.components.RowPreview

/**
 * What can be changed on a title, beside its page: its three pieces of artwork, each shown as it
 * is now, then what it is matched with. Choosing one opens its screen at once.
 */
@Composable
fun EditMenu(
    item: MediaItem,
    onDismiss: () -> Unit,
    onArtwork: (ArtworkKind) -> Unit,
    onIdentify: () -> Unit,
) {
    val first = remember { FocusRequester() }
    HlSideSheet(
        title = stringResource(R.string.edit),
        onDismiss = onDismiss,
        subject = item.title,
        facts = listOf(item.year?.toString()),
        initialFocus = first,
    ) {
        HlSettingRow(
            stringResource(R.string.artwork_poster),
            description = stringResource(R.string.artwork_poster_desc),
            onClick = { onArtwork(ArtworkKind.POSTER) },
            modifier = Modifier.focusRequester(first),
            leading = { RowPreview(item.poster, PreviewShape.Poster) },
        )
        HlSettingRow(
            stringResource(R.string.artwork_backdrop),
            description = stringResource(R.string.artwork_backdrop_desc),
            onClick = { onArtwork(ArtworkKind.BACKDROP) },
            leading = { RowPreview(item.backdrop, PreviewShape.Backdrop) },
        )
        HlSettingRow(
            stringResource(R.string.artwork_logo),
            description = stringResource(R.string.artwork_logo_desc),
            onClick = { onArtwork(ArtworkKind.LOGO) },
            leading = { RowPreview(item.logo, PreviewShape.Logo, icon = HlIcons.Logo) },
        )
        // Not an image: what the title is matched with.
        Spacer(Modifier.height(HlSpace.s))
        HlDivider()
        Spacer(Modifier.height(HlSpace.s))
        HlSettingRow(
            stringResource(R.string.identify),
            description = stringResource(R.string.identify_menu_desc),
            onClick = onIdentify,
            leading = { RowPreview(null, PreviewShape.Backdrop, icon = HlIcons.Search) },
        )
    }
}
