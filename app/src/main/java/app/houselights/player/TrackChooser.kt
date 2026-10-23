package app.houselights.player

import android.util.Log
import app.houselights.core.PlaybackPlan
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan
import app.houselights.core.TrackPreferences
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * The tracks to ask of the player for a title: Settings', or the title's own choice, which [load] and [save] keep
 * under its key ([PlaybackPlan.Launch.titleKey]).
 */
class TrackChooser(
    private val preferences: () -> TrackPreferences,
    /** The app's language, with the device's region: a dub of that region comes first. */
    private val locale: () -> Locale,
    private val load: suspend (String) -> TrackChoice?,
    private val save: suspend (String, TrackChoice) -> Unit,
    private val forget: suspend (String) -> Unit,
    /** The player's own audio setting ([ZidooSettings.audio]), read for each plan. */
    private val playerAudio: () -> String? = { null },
) {
    /** One change at a time: each reads what the one before wrote. */
    private val changes = Mutex()

    /** The tracks to ask for [launch]; null when the player chooses them all, or when the file's order is unknown. */
    suspend fun plan(launch: PlaybackPlan.Launch): TrackPlan? = table(launch)?.takeUnless { it.pick.isEmpty }

    /** [launch]'s tracks and what Settings and the title's choice make of them; null when their order is unknown. */
    suspend fun table(launch: PlaybackPlan.Launch): TrackPlan? {
        val media = launch.media?.takeIf { it.tracksInOrder } ?: return null
        val key = launch.titleKey
        // A choice that cannot be read leaves Settings to decide, never the film unplayed.
        val choice = key?.let { k ->
            suspendRunCatching { load(k) }.onFailure { Log.w(TAG, "Could not read the tracks chosen", it) }.getOrNull()
        }
        val locale = locale()
        val language = TrackPlan.known(locale.language) ?: FALLBACK_LANGUAGE
        return TrackPlan(media, preferences(), choice, language, locale.country.ifEmpty { null }, key, playerAudio())
    }

    /** Changes the title's choice by [change]; an empty one leaves it to Settings again. */
    suspend fun choose(key: String, change: (TrackChoice) -> TrackChoice) = changes.withLock {
        val choice = change(load(key) ?: TrackChoice())
        if (choice.isEmpty) forget(key) else save(key, choice)
    }

    /** Keeps [changed] as the title's choice, besides what it had chosen of the other kind. */
    suspend fun remember(key: String, changed: TrackChoice) = changes.withLock {
        save(key, (load(key) ?: TrackChoice()).with(changed))
    }

    private companion object {
        const val TAG = "Tracks"
        const val FALLBACK_LANGUAGE = "en"
    }
}
