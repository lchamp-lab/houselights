package app.houselights.ui.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.houselights.core.Secrets
import app.houselights.settings.SettingsStore
import app.houselights.testing.testSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

/** Settings in a file of [folder], read once; file access and secrets run in [scope], for `advanceUntilIdle`. */
suspend fun testSettings(folder: File, scope: CoroutineScope, secrets: Secrets = testSecrets()): SettingsStore {
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { folder.resolve("settings.preferences_pb") })
    return SettingsStore(store, scope, secrets, readOn = EmptyCoroutineContext).also { settings -> settings.state.first { it.loaded } }
}
