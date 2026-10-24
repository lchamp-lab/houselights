package app.houselights.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.houselights.core.HomeSection
import app.houselights.core.ImageRef
import app.houselights.core.SourceConfig
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.components.FIELD_POSTERS

/**
 * The posters the settings and their forms lie on (`PosterField`), by source, the last ones kept for the process: the
 * folders' latest, a Jellyfin library's as home showed them, or, before any folder, the films the welcome wall shows.
 */
class RoomPosters(
    /** The source in use, once the settings are read. */
    private val source: suspend () -> SourceConfig?,
    /** The folders' latest posters. */
    private val latest: suspend () -> List<ImageRef>,
    private val hasFolders: suspend () -> Boolean,
    /** The welcome wall's posters, at a width in px. */
    private val trending: suspend (Int) -> List<ImageRef>,
) {
    private val kept = mutableMapOf<SourceConfig?, List<ImageRef>>()

    /** The posters last read for [source]: the room comes back to them at once. */
    @Synchronized
    fun last(source: SourceConfig?): List<ImageRef> = kept[source].orEmpty()

    /** Home showed [sections] from [source]: a Jellyfin library's posters, the room's without asking the server. */
    fun shown(source: SourceConfig?, sections: List<HomeSection>) {
        if (source !is SourceConfig.Jellyfin) return
        val posters = sections.asSequence().flatMap { it.items }.mapNotNull { it.poster }.distinct().take(FIELD_POSTERS).toList()
        if (posters.isNotEmpty()) synchronized(this) { kept[source] = posters }
    }

    /**
     * The posters to lie on now, read once: the folders' latest, else without folders the welcome wall's ([widthPx]
     * wide); a Jellyfin library's only as home showed them. Empty when there are none yet.
     */
    suspend fun read(widthPx: Int): List<ImageRef> {
        val from = source()
        val read = when (from) {
            SourceConfig.Local -> latest().ifEmpty { if (hasFolders()) emptyList() else welcome(widthPx) }
            is SourceConfig.Jellyfin -> last(from)
            null -> welcome(widthPx)
        }
        if (read.isNotEmpty()) synchronized(this) { kept[from] = read }
        return read
    }

    private suspend fun welcome(widthPx: Int) = trending(widthPx).take(FIELD_POSTERS)
}

/** The room's posters for this visit: the last ones at once, then those [RoomPosters.read] finds, once. */
@Composable
fun roomPosters(): List<ImageRef> {
    val app = appContainer()
    val width = HlSize.welcomePoster.toPx()
    return rememberRoomPosters(remember(app) { app.roomPosters.last(app.settings.state.value.source) }) { app.roomPosters.read(width) }
}

/** [initial] at once, then what [read] gives, read once while composed: what changes meanwhile waits for the next visit. */
@Composable
internal fun rememberRoomPosters(initial: List<ImageRef>, read: suspend () -> List<ImageRef>): List<ImageRef> {
    var posters by remember { mutableStateOf(initial) }
    LaunchedEffect(Unit) { read().takeIf { it.isNotEmpty() }?.let { posters = it } }
    return posters
}
