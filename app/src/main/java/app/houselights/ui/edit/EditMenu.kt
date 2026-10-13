package app.houselights.ui.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.ArtworkKind
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlModal

/** What can be changed on a title: its three pieces of artwork and what it is matched with. */
@Composable
fun EditMenu(
    title: String,
    onDismiss: () -> Unit,
    onArtwork: (ArtworkKind) -> Unit,
    onIdentify: () -> Unit,
) {
    val first = remember { FocusRequester() }
    HlModal(stringResource(R.string.edit_title, title), onDismiss, initialFocus = first) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlListItem(
                    stringResource(R.string.artwork_poster),
                    stringResource(R.string.artwork_poster_desc),
                    onClick = { onArtwork(ArtworkKind.POSTER) },
                    modifier = Modifier.weight(1f).focusRequester(first),
                    icon = HlIcons.Poster,
                )
                HlListItem(
                    stringResource(R.string.artwork_backdrop),
                    stringResource(R.string.artwork_backdrop_desc),
                    onClick = { onArtwork(ArtworkKind.BACKDROP) },
                    modifier = Modifier.weight(1f),
                    icon = HlIcons.Backdrop,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                HlListItem(
                    stringResource(R.string.artwork_logo),
                    stringResource(R.string.artwork_logo_desc),
                    onClick = { onArtwork(ArtworkKind.LOGO) },
                    modifier = Modifier.weight(1f),
                    icon = HlIcons.Logo,
                )
                HlListItem(
                    stringResource(R.string.identify),
                    stringResource(R.string.identify_menu_desc),
                    onClick = onIdentify,
                    modifier = Modifier.weight(1f),
                    icon = HlIcons.Search,
                )
            }
        }
    }
}
