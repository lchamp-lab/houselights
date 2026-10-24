package app.houselights.core

import kotlinx.coroutines.flow.StateFlow

/** Tells screens when library data may have changed. */
interface LibraryEvents {
    /** Increments, at most every few seconds, when library data may have changed; screens reload on it. */
    val libraryVersion: StateFlow<Long>

    /** Increments before [libraryVersion] on every change but a running scan's progress; the scan's end moves it. */
    val settledVersion: StateFlow<Long>

    /** Reports an in-app edit that screens show (watched, favourite) so the other screens reload. */
    fun libraryChanged()
}
