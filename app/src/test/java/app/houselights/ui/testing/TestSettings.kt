package app.houselights.ui.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.houselights.core.Secrets
import app.houselights.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.coroutines.EmptyCoroutineContext

/** A key of the test's own, in place of the device's Keystore. */
fun testSecrets(): Secrets {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return Secrets { key }
}

/** Settings in a file of [folder], read once; file access and secrets run in [scope], for `advanceUntilIdle`. */
suspend fun testSettings(folder: File, scope: CoroutineScope, secrets: Secrets = testSecrets()): SettingsStore {
    val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { folder.resolve("settings.preferences_pb") })
    return SettingsStore(store, scope, secrets, readOn = EmptyCoroutineContext).also { settings -> settings.state.first { it.loaded } }
}
