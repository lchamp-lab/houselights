package app.houselights.ui.catalog

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.components.SectionHeader

// Parts shared by the sections of the catalogue, each in its own file.

/** A catalogue section; one with nothing to focus ([still]) takes focus itself, so the remote reaches it. */
@Composable
internal fun Group(title: String, modifier: Modifier = Modifier, still: Boolean = false, content: @Composable () -> Unit) {
    Column(
        modifier.revealOnFocus().then(if (still) Modifier.focusable() else Modifier),
        verticalArrangement = Arrangement.spacedBy(HlSpace.m),
    ) {
        SectionHeader(title)
        content()
    }
}

/** One state of a component, captioned [gap] below; with a [width], specimen and caption take it. */
@Composable
internal fun Specimen(
    caption: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    gap: Dp = HlSpace.s,
    content: @Composable () -> Unit,
) {
    Column(if (width == null) modifier else modifier.width(width), verticalArrangement = Arrangement.spacedBy(gap)) {
        content()
        Text(caption, style = Hl.type.caption, color = Hl.colors.contentTertiary)
    }
}

/** The catalogue's own frame size (swatches, a mark's white ground): no screen uses it, so it is no token. */
internal val CatalogSwatch = 52.dp

/** A title with no artwork, in the state asked for. */
internal fun sample(
    title: String,
    year: Int,
    kind: MediaKind = MediaKind.MOVIE,
    played: Boolean = false,
    unplayed: Int? = null,
    positionMs: Long = 0,
    addedAt: Long? = null,
) = MediaItem(
    id = "catalog-$title",
    kind = kind,
    title = title,
    year = year,
    runtimeMs = 3_600_000,
    user = UserState(played = played, unplayedCount = unplayed, positionMs = positionMs),
    addedAt = addedAt,
)
