package app.houselights.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.houselights.core.PathMapping
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import app.houselights.core.CollectionThreshold

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    // A damaged settings file starts over from the defaults instead of stopping the app at launch.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { listOf(SealSecrets(Secrets.device)) },
)

private object Keys {
    val source = stringPreferencesKey("source")
    val deviceId = stringPreferencesKey("device_id")
    val mappings = stringPreferencesKey("path_mappings")
    val forceHttp = booleanPreferencesKey("force_http")
    val ui4k = booleanPreferencesKey("ui_4k")
    val tmdbKey = stringPreferencesKey("tmdb_api_key")
    val collectionMin = intPreferencesKey("collection_min_films")
}

/**
 * The settings that hold a secret: the source (with its server token), a personal TMDB key, and path
 * mappings, whose network paths may carry a password.
 */
private val SECRET_KEYS = listOf(Keys.source, Keys.tmdbKey, Keys.mappings)

/** Seals secrets written in plain text before sealing existed. */
private class SealSecrets(private val secrets: Secrets) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        SECRET_KEYS.any { key -> currentData[key]?.let { !secrets.isSealed(it) } == true }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        for (key in SECRET_KEYS) this[key]?.takeUnless(secrets::isSealed)?.let { this[key] = secrets.seal(it) }
    }

    override suspend fun cleanUp() = Unit
}

data class AppSettings(
    val loaded: Boolean = false,
    val source: SourceConfig? = null,
    val deviceId: String = "",
    val pathMappings: List<PathMapping> = emptyList(),
    val forceHttp: Boolean = false,
    val ui4k: Boolean = true,
    /** User-provided TMDB key, overrides the one built into the APK. */
    val tmdbApiKey: String? = null,
    /** How many films of a collection the library needs to show it; 0 hides collections. */
    val collectionMinFilms: Int = CollectionThreshold.DEFAULT,
)

class SettingsStore(
    private val context: Context,
    scope: CoroutineScope,
    private val secrets: Secrets = Secrets.device,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val state: StateFlow<AppSettings> = context.dataStore.data
        // A file that can't be read gives the defaults: the app opens on its welcome screen, not on a blank one.
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        loaded = true,
        source = this[Keys.source]?.let(secrets::open)?.let { runCatching { json.decodeFromString<SourceConfig>(it) }.getOrNull() },
        deviceId = this[Keys.deviceId] ?: "",
        pathMappings = this[Keys.mappings]
            ?.let(secrets::open)
            ?.let { runCatching { json.decodeFromString<List<PathMapping>>(it) }.getOrNull() }
            ?: emptyList(),
        forceHttp = this[Keys.forceHttp] ?: false,
        ui4k = this[Keys.ui4k] ?: true,
        tmdbApiKey = this[Keys.tmdbKey]?.let(secrets::open)?.takeIf { it.isNotBlank() },
        collectionMinFilms = this[Keys.collectionMin] ?: CollectionThreshold.DEFAULT,
    )

    suspend fun ensureDeviceId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            id = prefs[Keys.deviceId] ?: UUID.randomUUID().toString().also { prefs[Keys.deviceId] = it }
        }
        return id
    }

    suspend fun setSource(source: SourceConfig?) {
        context.dataStore.edit {
            if (source == null) it.remove(Keys.source) else it[Keys.source] = secrets.seal(json.encodeToString(source))
        }
    }

    suspend fun setPathMappings(mappings: List<PathMapping>) {
        context.dataStore.edit { it[Keys.mappings] = secrets.seal(json.encodeToString(mappings)) }
    }

    suspend fun setForceHttp(value: Boolean) {
        context.dataStore.edit { it[Keys.forceHttp] = value }
    }

    suspend fun setUi4k(value: Boolean) {
        context.dataStore.edit { it[Keys.ui4k] = value }
    }

    suspend fun setCollectionMinFilms(value: Int) {
        context.dataStore.edit { it[Keys.collectionMin] = value }
    }

    suspend fun setTmdbApiKey(value: String?) {
        context.dataStore.edit {
            if (value.isNullOrBlank()) it.remove(Keys.tmdbKey) else it[Keys.tmdbKey] = secrets.seal(value.trim())
        }
    }
}
