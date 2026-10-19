package app.houselights.ui.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.houselights.core.Secrets
import app.houselights.settings.SettingsStore
import app.houselights.testing.testSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext

/** Settings in a file of [folder], read once; file access and secrets run in [scope], for `advanceUntilIdle`. */
suspend fun testSettings(folder: File, scope: CoroutineScope, secrets: Secrets = testSecrets()): SettingsStore {
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { folder.resolve("settings.preferences_pb") })
    return SettingsStore(store, scope, secrets, readOn = EmptyCoroutineContext).also { settings -> settings.state.first { it.loaded } }
}

/** Settings that read, and refuse every write: the device's storage is full. */
fun fullSettings(scope: CoroutineScope) = SettingsStore(FullStore, scope, testSecrets())

private object FullStore : DataStore<Preferences> {
    override val data = flowOf(emptyPreferences())

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("No space left on device")
}
