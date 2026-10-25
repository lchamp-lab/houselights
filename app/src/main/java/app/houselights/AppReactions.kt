package app.houselights

import app.houselights.core.SourceConfig
import app.houselights.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * While the folders are the source, a new TMDB key in [settings] (or back to the built-in one) runs [retry], which
 * forgets the old key's results and retries unmatched titles; the key read at launch is no change.
 */
suspend fun retryOnTmdbKeyChange(settings: Flow<AppSettings>, retry: suspend () -> Unit) {
    settings.filter { it.loaded }.distinctUntilChangedBy { it.tmdbApiKey }.drop(1).collect {
        if (it.source == SourceConfig.Local) retry()
    }
}

/** A new interface language in [chosen] runs [changed], so titles the library words itself ("Season 1") are made again. */
suspend fun rewordOnLanguageChange(chosen: Flow<String?>, changed: () -> Unit) {
    chosen.distinctUntilChanged().drop(1).collect { changed() }
}
