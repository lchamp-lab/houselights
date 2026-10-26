package app.houselights.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Gallery
import app.houselights.ui.design.components.GalleryTile
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.PreviewShape

/**
 * A title's edit side sheet: its poster, backdrop and logo as a gallery, each image at its shape of use, then its
 * match on a plate. Choosing an image opens its screen directly.
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
        facts = listOf(item.title, item.year?.toString()),
        initialFocus = first,
    ) {
        // Its names a group's room (HlSpace.l) above the plate under it.
        Gallery(Modifier.padding(bottom = HlSpace.xs)) {
            GalleryTile(
                item.poster,
                PreviewShape.Poster,
                stringResource(R.string.artwork_poster),
                onClick = { onArtwork(ArtworkKind.POSTER) },
                width = HlLayout.posterChoice,
                modifier = Modifier.focusRequester(first),
            )
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
                GalleryTile(
                    item.backdrop,
                    PreviewShape.Backdrop,
                    stringResource(R.string.artwork_backdrop),
                    onClick = { onArtwork(ArtworkKind.BACKDROP) },
                    width = HlSize.galleryWide,
                )
                GalleryTile(
                    item.logo,
                    PreviewShape.Logo,
                    stringResource(R.string.artwork_logo),
                    onClick = { onArtwork(ArtworkKind.LOGO) },
                    width = HlSize.galleryWide,
                )
            }
        }
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.identify),
                // A title to identify has no match to change: it is to be found.
                description = stringResource(
                    if (item.matchDoubt != null) R.string.identify_menu_desc_none else R.string.identify_menu_desc,
                ),
                icon = HlIcons.Search,
                onClick = onIdentify,
            )
        }
    }
}
