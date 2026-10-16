package app.houselights.core

import kotlinx.coroutines.flow.StateFlow

/** When library data may have changed, for the screens that show it. */
interface LibraryEvents {
    /**
     * Moves on when library data may have changed (playback ended, scan progress, something the user
     * changed), at most every few seconds. Screens on display reload when it moves; screens behind
     * others when they come back.
     */
    val libraryVersion: StateFlow<Long>

    /** The user changed something screens show (watched, a favourite): the others reload. */
    fun libraryChanged()
}
