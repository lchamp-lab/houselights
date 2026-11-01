package app.houselights.ui.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.ListScroll

/**
 * A wall of posters (a library's, a person's): [HlLayout.WALL_COLUMNS] across the safe area, [HlLayout.wallGutter]
 * apart, so a [PosterCard] without a width fills a cell of [requestedPoster], the size its picture is decoded and
 * loaded ahead at ([PicturesAhead.wall]). A scrolled line comes to where the first line rests. One frame travels from
 * card to card, across lines too ([GroupFrame]); while a card has focus, the others step back.
 */
@Composable
fun PosterWallGrid(state: LazyGridState, modifier: Modifier = Modifier, content: LazyGridScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val frame = rememberGridFrame(state)
    CompositionLocalProvider(LocalGroupFocused provides focused, LocalGroupFrame provides frame) {
        ListScroll(top = HlFocus.wallRoom) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(HlLayout.WALL_COLUMNS),
                state = state,
                contentPadding = PaddingValues(start = HlSpace.safeH, end = HlSpace.safeH, top = HlFocus.wallRoom, bottom = HlFocus.edge),
                horizontalArrangement = Arrangement.spacedBy(HlLayout.wallGutter),
                verticalArrangement = Arrangement.spacedBy(HlSpace.l),
                modifier = Modifier.fillMaxSize().then(modifier).onFocusChanged { focused = it.hasFocus }.then(frame.drawn),
                content = content,
            )
        }
    }
}
