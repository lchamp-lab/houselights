package app.houselights.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import app.houselights.core.PathMapping
import app.houselights.core.Secrets
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.SourceConfig
import app.houselights.core.lenientJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.util.UUID
import app.houselights.core.CollectionThreshold
import kotlin.coroutines.CoroutineContext

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

/** A setting that holds a secret, named for the screens that say it could not be read again. */
enum class SecretSetting {
    /** The source: a server's token. */
    SOURCE,
    TMDB_KEY,

    /** Path mappings: their network paths may carry a password. */
    PATH_MAPPINGS,
}

private val Preferences.Key<String>.secret: SecretSetting
    get() = when (this) {
        Keys.source -> SecretSetting.SOURCE
        Keys.tmdbKey -> SecretSetting.TMDB_KEY
        else -> SecretSetting.PATH_MAPPINGS
    }

/** A source holds a secret, the server's token, unless it is the folders: those are never sealed. */
private fun sourceHoldsSecret(plain: String): Boolean =
    runCatching { lenientJson.decodeFromString<SourceConfig>(plain) }.getOrNull() != SourceConfig.Local

/** [plain], kept under [key], is a secret to seal: anything there but a source that is the folders. */
private fun needsSealing(key: Preferences.Key<String>, plain: String): Boolean = key != Keys.source || sourceHoldsSecret(plain)

/**
 * Seals secrets written in plain text before sealing existed. A Keystore that cannot seal leaves them
 * as they are: read as plain text, they are sealed at a later launch, never lost, and the settings
 * still open.
 */
internal class SealSecrets(private val secrets: Secrets) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        SECRET_KEYS.any { key -> currentData[key]?.let { !secrets.isSealed(it) && needsSealing(key, it) } == true }

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        for (key in SECRET_KEYS) {
            val plain = this[key]?.takeUnless(secrets::isSealed)?.takeIf { needsSealing(key, it) } ?: continue
            try {
                this[key] = secrets.seal(plain)
            } catch (e: SecretsUnavailableException) {
                Log.w(TAG, "${key.name} left unsealed", e)
            }
        }
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
    /**
     * The secrets kept here (the source's token, path mappings, the TMDB key) that could not be read
     * again (the Keystore's key is gone, or a value no longer makes sense): each reads as not set, and
     * is to be given again. Saving a new value puts it back.
     */
    val lostSecrets: Set<SecretSetting> = emptySet(),
    /**
     * The settings file could not be read, again and again: not [loaded], and still tried, less and
     * less often. Never the defaults in its place, which would open on the welcome screen.
     */
    val readFailed: Boolean = false,
) {
    /** The connection kept (a server and its token) could not be read again: the welcome screen says so. */
    val lostSource: Boolean get() = SecretSetting.SOURCE in lostSecrets

    /** Never the TMDB key nor the mappings' network paths, which may carry a password: a log line may quote the settings. */
    override fun toString() =
        "AppSettings(loaded=$loaded, source=$source, deviceId=$deviceId, pathMappings=${pathMappings.size}, " +
            "forceHttp=$forceHttp, ui4k=$ui4k, tmdbApiKey=${tmdbApiKey?.let { "***" }}, " +
            "collectionMinFilms=$collectionMinFilms, lostSecrets=$lostSecrets, readFailed=$readFailed)"
}

class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val secrets: Secrets = Secrets.device,
    /**
     * Where the settings are read and their secrets opened: never the main thread [scope] may run on,
     * since the first opening loads the Keystore's key and each one asks the keystore daemon.
     */
    private val readOn: CoroutineContext = Dispatchers.Default,
) {
    /** The app's own settings file. */
    constructor(context: Context, scope: CoroutineScope, secrets: Secrets = Secrets.device) : this(context.dataStore, scope, secrets)

    private val _state = MutableStateFlow(AppSettings())

    /**
     * The settings as last read. A read that fails is tried again, a little later each time: the
     * settings stay unread meanwhile, rather than the defaults, and say [AppSettings.readFailed]
     * after a few tries.
     */
    val state: StateFlow<AppSettings> = _state

    /** Asked to read again at once ([readAgain]); one ask stands for any number. */
    private val readNow = Channel<Unit>(Channel.CONFLATED)

    private val _reading = MutableStateFlow(false)

    /** A read asked by [readAgain] is under way: the screen that asked says it is at work. */
    val reading: StateFlow<Boolean> = _reading

    /** The file as [state] last read it: a write returns once it is read ([write]). */
    private val readFrom = MutableStateFlow<Preferences?>(null)

    init {
        scope.launch {
            dataStore.data
                .map { it to it.toSettings() }
                .flowOn(readOn)
                .retryWhen { e, attempt ->
                    if (e is CancellationException) return@retryWhen false
                    Log.w(TAG, "Settings could not be read (try ${attempt + 1})", e)
                    _reading.value = false
                    if (attempt + 1 >= READ_TRIES && !_state.value.loaded) _state.value = AppSettings(readFailed = true)
                    // An ask from before this failure is answered by it.
                    readNow.tryReceive()
                    withTimeoutOrNull(minOf(MAX_READ_WAIT_MS, FIRST_READ_WAIT_MS shl attempt.toInt().coerceAtMost(MAX_SHIFT))) {
                        readNow.receive()
                    }
                    true
                }
                .collect { (file, settings) ->
                    _reading.value = false
                    _state.value = settings
                    readFrom.value = file
                }
        }
    }

    /** After the settings could not be read ([AppSettings.readFailed]): reads them again now, not at the next try. */
    fun readAgain() {
        if (_state.value.loaded) return
        _reading.value = true
        readNow.trySend(Unit)
    }

    private fun Preferences.toSettings(): AppSettings {
        val lost = mutableSetOf<SecretSetting>()
        val source = this[Keys.source]?.let { opened(Keys.source, it, lost) }?.let { decoded<SourceConfig>(Keys.source, it, lost) }
        val mappings = this[Keys.mappings]
            ?.let { opened(Keys.mappings, it, lost) }
            ?.let { decoded<List<PathMapping>>(Keys.mappings, it, lost) }
        return AppSettings(
            loaded = true,
            source = source,
            deviceId = this[Keys.deviceId] ?: "",
            pathMappings = mappings.orEmpty(),
            forceHttp = this[Keys.forceHttp] ?: false,
            ui4k = this[Keys.ui4k] ?: true,
            tmdbApiKey = this[Keys.tmdbKey]?.let { opened(Keys.tmdbKey, it, lost) }?.takeIf { it.isNotBlank() },
            collectionMinFilms = this[Keys.collectionMin] ?: CollectionThreshold.DEFAULT,
            lostSecrets = lost,
        )
    }

    /** The plain text of a secret; when it can't be read, null, written down in [lost] and logged (never its value). */
    private fun opened(key: Preferences.Key<String>, stored: String, lost: MutableSet<SecretSetting>): String? {
        val plain = secrets.open(stored)
        if (plain == null) {
            Log.w(TAG, "${key.name} can no longer be decrypted")
            lost += key.secret
        }
        return plain
    }

    /** What [text] holds; when it no longer reads (a version that renamed a field), null, written down in [lost]. */
    private inline fun <reified T> decoded(key: Preferences.Key<String>, text: String, lost: MutableSet<SecretSetting>): T? = try {
        lenientJson.decodeFromString<T>(text)
    } catch (e: IllegalArgumentException) {
        // A SerializationException among them: its message would quote the value.
        Log.w(TAG, "${key.name} no longer reads: ${e.javaClass.simpleName}")
        lost += key.secret
        null
    }

    /**
     * Writes the file, and returns once [state] says what it holds: read away from the main thread,
     * the settings reach [state] a moment after the file. Signing in saves the source, then goes home,
     * whose rows ask [state] for it at once: the new one, not the one before. A read that fails
     * meanwhile is waited for [WRITE_READ_MS] at most.
     */
    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
        val written = dataStore.edit(transform)
        withTimeoutOrNull(WRITE_READ_MS) { readFrom.first { it == written } }
    }

    suspend fun ensureDeviceId(): String {
        var id = ""
        write { prefs ->
            id = prefs[Keys.deviceId] ?: UUID.randomUUID().toString().also { prefs[Keys.deviceId] = it }
        }
        return id
    }

    /** Saves the source; the folders hold no secret and are never sealed, so choosing them never waits on the Keystore. */
    suspend fun setSource(source: SourceConfig?) {
        write {
            when (source) {
                null -> it.remove(Keys.source)
                SourceConfig.Local -> it[Keys.source] = lenientJson.encodeToString<SourceConfig>(source)
                is SourceConfig.Jellyfin -> it[Keys.source] = secrets.seal(lenientJson.encodeToString<SourceConfig>(source))
            }
        }
    }

    suspend fun setPathMappings(mappings: List<PathMapping>) {
        write { it[Keys.mappings] = secrets.seal(lenientJson.encodeToString(mappings)) }
    }

    suspend fun setForceHttp(value: Boolean) {
        write { it[Keys.forceHttp] = value }
    }

    suspend fun setUi4k(value: Boolean) {
        write { it[Keys.ui4k] = value }
    }

    suspend fun setCollectionMinFilms(value: Int) {
        write { it[Keys.collectionMin] = value }
    }

    suspend fun setTmdbApiKey(value: String?) {
        write {
            if (value.isNullOrBlank()) it.remove(Keys.tmdbKey) else it[Keys.tmdbKey] = secrets.seal(value.trim())
        }
    }
}

private const val TAG = "Settings"

/** Failed reads before the settings say [AppSettings.readFailed]. */
private const val READ_TRIES = 3

/** The wait after a first failed read, doubled after each one, up to [MAX_READ_WAIT_MS]. */
private const val FIRST_READ_WAIT_MS = 500L
private const val MAX_READ_WAIT_MS = 30_000L
private const val MAX_SHIFT = 16

/** How long a write waits for [SettingsStore.state] to read it: the Keystore's slowest opening, and a try again. */
private const val WRITE_READ_MS = 2_000L
