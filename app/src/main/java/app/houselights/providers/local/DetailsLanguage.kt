package app.houselights.providers.local

import android.util.Log
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/**
 * The language of the folders' details: [wanted], TMDB's; [read], the one recorded; [refreshable] with the folders
 * as the source and a TMDB key.
 */
data class DetailsLanguage(val wanted: String, val read: String?, val refreshable: Boolean)

/**
 * Follows [languages]: a new one asks every folder to be read again in it ([LibraryScanner.refreshAllIn]) and is
 * recorded at once, so a folder out of reach is read once back, never the whole library at each launch. Until a
 * language is recorded, the folders were read in the one in use.
 */
suspend fun followDetailsLanguage(
    languages: Flow<DetailsLanguage>,
    refreshAllIn: suspend (Locale) -> Unit,
    record: suspend (String) -> Unit,
) {
    languages.distinctUntilChanged().collect { (wanted, read, refreshable) ->
        when {
            read == null -> record(wanted)
            wanted != read && refreshable -> suspendRunCatching { refreshAllIn(Locale.forLanguageTag(wanted)) }
                .onSuccess { record(wanted) }
                .onFailure { Log.w("DetailsLanguage", "Folders not asked to read their details in $wanted", it) }
        }
    }
}
