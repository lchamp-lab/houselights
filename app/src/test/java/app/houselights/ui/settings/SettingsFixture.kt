package app.houselights.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.houselights.providers.local.IgnoredFileRow
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.TmdbKeyCheck
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.idleLibrary
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.io.File

/** The settings' ViewModel on settings in [folder] and an empty library, no scan running; [close] once done. */
internal class SettingsFixture(folder: File) {
    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private val db = testDatabase { onCallingThread() }

    val store: SettingsStore = runBlocking { testSettings(folder, files) }

    val vm: SettingsViewModel = settingsModel(screens, store, idleLibrary(db.dao()), work)

    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
    }
}

/**
 * The settings' ViewModel as the screen holds it in [screens], on [store] and [library], saving in [work]: the source's
 * a [FakeProvider], no server signed out nor checked, TMDB answering [checkTmdbKey] when a key is saved.
 */
internal fun settingsModel(
    screens: ViewModelStore,
    store: SettingsStore,
    library: LocalLibrary,
    work: CoroutineScope,
    checkTmdbKey: suspend (String) -> TmdbKeyCheck = { error("No TMDB key is checked here") },
    toIdentifyCount: Flow<Int> = library.toIdentifyCount,
    ignoredFiles: Flow<List<IgnoredFileRow>> = library.ignored,
): SettingsViewModel {
    val factory = viewModelFactory {
        initializer {
            val sources = SourceSwitcher({ store.state.value }, store::setSource, work, signOut = { _, _ -> }, check = { _, _ -> })
            SettingsViewModel(
                store,
                library,
                { FakeProvider() },
                work,
                sources,
                images = null,
                checkTmdbKey = checkTmdbKey,
                toIdentifyCount = toIdentifyCount,
                ignoredFiles = ignoredFiles,
            )
        }
    }
    return ViewModelProvider(screens, factory)[SettingsViewModel::class.java]
}
