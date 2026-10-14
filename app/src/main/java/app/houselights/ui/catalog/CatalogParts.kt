package app.houselights.ui.catalog

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.UserState
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.design.components.SectionHeader

// Parts shared by the sections of the catalogue, each in its own file.

/**
 * A section of the catalogue. Sections with nothing to focus ([still]) take focus themselves, so
 * the remote can step through every one.
 */
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
