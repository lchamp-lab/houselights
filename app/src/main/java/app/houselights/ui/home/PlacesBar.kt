package app.houselights.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.core.Library
import app.houselights.core.LibraryKind
import app.houselights.ui.design.FocusKey
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.awaitMotion
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlNavBar
import app.houselights.ui.design.focusGroupKey
import app.houselights.ui.design.focusKey
import app.houselights.ui.util.libraryName
import kotlinx.coroutines.launch

/**
 * The places of the bar and where they lead: home, then [tabs], every library but the sagas, which a films' wall
 * shows among its films ([sagas]). [onPlace] opens home (null) or a library's wall; [onLibraryAhead] prefetches a wall.
 */
data class Places(
    val tabs: List<Library>,
    val sagas: Library?,
    val onPlace: (Library?, sagas: Library?) -> Unit,
    val onSettings: () -> Unit,
    /** Null when the source cannot search. */
    val onSearch: (() -> Unit)? = null,
    val onLibraryAhead: (Library, sagas: Library?) -> Unit = { _, _ -> },
)

/** The places as one focus group, which picks the place to focus: the current one. */
internal const val TRACK = "track"

/**
 * The bar on home and on a library's wall, [here] lit (home when null), [HlSpace.safeV] from the top; as tall as a
 * compact tile (`HomeLayoutTest`). A place opens on OK, never as focus passes: its word lights while its wall loads,
 * then it opens. Down from the tiles goes to [down]; [track] goes to the places' group, [status] before the tiles.
 */
@Composable
internal fun PlacesBar(
    places: Places,
    here: Library?,
    memory: FocusMemory,
    down: () -> FocusRequester?,
    modifier: Modifier = Modifier,
    track: Modifier = Modifier,
    status: String? = null,
) {
    // A wall's own library is a place even before home's list of them is read.
    val shown = listOf(null) + places.tabs.let { tabs -> if (here == null || tabs.any { it.id == here.id }) tabs else tabs + here }
    val start = shown.indexOfFirst { it?.id == here?.id }
    // The chosen place lights before it opens; coming back makes this screen's place the current one again.
    var current by remember(start) { mutableIntStateOf(start) }
    val scope = rememberCoroutineScope()
    HlNavBar(
        places = shown.map { library -> library?.let { it.title ?: libraryName(it.kind) } ?: stringResource(R.string.nav_home) },
        current = current,
        onPlace = { i ->
            // One choice at a time: once another place is lit, the screen is leaving for it.
            if (i != current && current == start) {
                current = i
                val library = shown[i]
                val sagas = places.sagas.takeIf { library?.kind == LibraryKind.MOVIES }
                library?.let { places.onLibraryAhead(it, sagas) }
                scope.launch {
                    awaitMotion(HlMotion.STANDARD)
                    places.onPlace(library, sagas)
                }
            }
        },
        modifier = modifier.fillMaxWidth().padding(start = HlSpace.safeH, end = HlSpace.safeH, top = HlSpace.safeV),
        track = track.focusGroupKey(TRACK, memory),
        status = status,
    ) {
        // Down from the tiles goes where it goes from the places, not to what lies beneath a tile.
        val below = Modifier.focusProperties { this.down = down() ?: FocusRequester.Default }
        places.onSearch?.let { search ->
            HlIconButton(
                HlIcons.Search,
                stringResource(R.string.search),
                onClick = search,
                modifier = Modifier.focusKey(SEARCH, memory).then(below),
            )
        }
        HlIconButton(
            HlIcons.Settings,
            stringResource(R.string.nav_settings),
            onClick = places.onSettings,
            modifier = Modifier.focusKey(SETTINGS, memory).then(below),
        )
    }
}

private val SEARCH = FocusKey.action("search")
private val SETTINGS = FocusKey.action("settings")
