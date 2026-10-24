package app.houselights.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import app.houselights.core.Opened
import app.houselights.core.PathMapping
import app.houselights.core.Secrets
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.SourceConfig
import app.houselights.core.SubtitleMode
import app.houselights.core.TrackPreferences
import app.houselights.core.TraktSession
import app.houselights.core.lenientJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import java.util.UUID
import app.houselights.core.CollectionThreshold
import kotlin.coroutines.CoroutineContext

/**
 * A corrupt settings file resets to the defaults rather than crashing the app at launch: logged, and flagged
 * ([AppSettings.settingsReset]) so Welcome says why it shows. Declared before the store, which it is given to.
 */
internal val resetWhenCorrupt = ReplaceFileCorruptionHandler<Preferences> { e ->
    Log.w(TAG, "Settings file unreadable, reset: ${e.javaClass.simpleName}")
    mutablePreferencesOf(Keys.settingsReset to true)
}

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = resetWhenCorrupt,
    produceMigrations = { context -> listOf(SealSecrets(Secrets.device(context))) },
)

private object Keys {
    val source = stringPreferencesKey("source")
    val keptServer = stringPreferencesKey("kept_server")
    val deviceId = stringPreferencesKey("device_id")
    val mappings = stringPreferencesKey("path_mappings")
    val forceHttp = booleanPreferencesKey("force_http")
    val ui4k = booleanPreferencesKey("ui_4k")
    val tmdbKey = stringPreferencesKey("tmdb_api_key")
    val collectionMin = intPreferencesKey("collection_min_films")
    val metadataLanguage = stringPreferencesKey("metadata_language")
    val libraryLanguage = stringPreferencesKey("library_language")
    val trakt = stringPreferencesKey("trakt")
    val audioLanguage = stringPreferencesKey("audio_language")
    val subtitleMode = stringPreferencesKey("subtitle_mode")
    val subtitleLanguage = stringPreferencesKey("subtitle_language")
    val settingsReset = booleanPreferencesKey("settings_reset")
}

private val SECRET_KEYS = listOf(Keys.source, Keys.keptServer, Keys.tmdbKey, Keys.mappings, Keys.trakt)

/** A setting that holds a secret, named so screens can say which one could not be read. */
enum class SecretSetting {
    /** The source: a server's token. */
    SOURCE,

    /** The server left for the folders, still signed in: its token. */
    KEPT_SERVER,
    TMDB_KEY,

    /** Path mappings: their network paths may carry a password. */
    PATH_MAPPINGS,

    /** The Trakt account's tokens. */
    TRAKT,
}

private val Preferences.Key<String>.secret: SecretSetting
    get() = when (this) {
        Keys.source -> SecretSetting.SOURCE
        Keys.keptServer -> SecretSetting.KEPT_SERVER
        Keys.tmdbKey -> SecretSetting.TMDB_KEY
        Keys.trakt -> SecretSetting.TRAKT
        else -> SecretSetting.PATH_MAPPINGS
    }

/** A source holds a secret (the server's token) unless it is the local folders, which are never sealed. */
private fun sourceHoldsSecret(plain: String): Boolean =
    runCatching { lenientJson.decodeFromString<SourceConfig>(plain) }.getOrNull() != SourceConfig.Local

private fun needsSealing(key: Preferences.Key<String>, plain: String): Boolean = key != Keys.source || sourceHoldsSecret(plain)

/** Seals plain-text secrets; if the Keystore cannot seal, they stay plain and are sealed at a later launch. */
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
    /** The Jellyfin server left for the folders, still signed in, resumed without signing in again; one at most. */
    val keptServer: SourceConfig.Jellyfin? = null,
    val deviceId: String = "",
    val pathMappings: List<PathMapping> = emptyList(),
    val forceHttp: Boolean = false,
    val ui4k: Boolean = true,
    /** TMDB key saved in Settings; overrides the one built into the APK. */
    val tmdbApiKey: String? = null,
    /** How many films of a collection the library needs to show it; 0 hides collections. */
    val collectionMinFilms: Int = CollectionThreshold.DEFAULT,
    /** TMDB's language as a BCP 47 tag ("fr-CA"); null follows the device. */
    val metadataLanguage: String? = null,
    /** The language the folders' details are in, or being read again in, folder by folder; null until first recorded. */
    val libraryLanguage: String? = null,
    /** The Trakt account connected for the folders' watch history; null when none is. */
    val trakt: TraktSession? = null,
    /** The audio and subtitles asked of the player for every title; by default the player chooses. */
    val tracks: TrackPreferences = TrackPreferences(),
    /** Secrets that can never be read back (another key, a damaged or renamed value): each reads as unset until saved again. */
    val lostSecrets: Set<SecretSetting> = emptySet(),
    /** Secrets the Keystore did not open at this read, though stored intact: each reads as unset until read again. */
    val unreadSecrets: Set<SecretSetting> = emptySet(),
    /** The file keeps failing to read: retried ever less often, never replaced by defaults (which open Welcome). */
    val readFailed: Boolean = false,
    /** The file was corrupt and reset to the defaults; Welcome says so until a source is saved. */
    val settingsReset: Boolean = false,
) {
    /** The saved connection (server and token) could not be read back; the welcome screen says so. */
    val lostSource: Boolean get() = SecretSetting.SOURCE in lostSecrets

    /** The saved connection is kept, but the Keystore did not open it for now; the welcome screen says so. */
    val unreadSource: Boolean get() = SecretSetting.SOURCE in unreadSecrets

    /** Omits the TMDB key, the mappings' network paths, which may carry a password, and Trakt's tokens: logs may quote the settings. */
    override fun toString() =
        "AppSettings(loaded=$loaded, source=$source, keptServer=$keptServer, deviceId=$deviceId, pathMappings=${pathMappings.size}, " +
            "forceHttp=$forceHttp, ui4k=$ui4k, tmdbApiKey=${tmdbApiKey?.let { "***" }}, " +
            "collectionMinFilms=$collectionMinFilms, metadataLanguage=$metadataLanguage, libraryLanguage=$libraryLanguage, " +
            "trakt=$trakt, tracks=$tracks, lostSecrets=$lostSecrets, unreadSecrets=$unreadSecrets, readFailed=$readFailed, " +
            "settingsReset=$settingsReset)"
}

class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
    private val secrets: Secrets,
    /** Where settings are read and secrets opened, never the main thread: each opening calls the keystore daemon. */
    private val readOn: CoroutineContext = Dispatchers.Default,
) {
    constructor(context: Context, scope: CoroutineScope, secrets: Secrets = Secrets.device(context)) :
        this(context.dataStore, scope, secrets)

    private val _state = MutableStateFlow(AppSettings())

    /** The settings as last read; a failed read is retried with growing delays, never replaced by defaults. */
    val state: StateFlow<AppSettings> = _state

    private val readNow = Channel<Unit>(Channel.CONFLATED)

    private val _reading = MutableStateFlow(false)

    /** True while a read requested by [readAgain] runs, so the screen can show it is busy. */
    val reading: StateFlow<Boolean> = _reading

    /** Reads asked by [readAgain] while secrets are unread: the file is read again though it has not changed. */
    private val rereads = MutableStateFlow(0)

    /** The file [state] last read, which [write] waits for. */
    private val readFrom = MutableStateFlow<Preferences?>(null)

    init {
        scope.launch {
            combine(dataStore.data, rereads) { file, _ -> file }
                .map { it to it.toSettings() }
                .flowOn(readOn)
                .retryWhen { e, attempt ->
                    if (e is CancellationException) return@retryWhen false
                    Log.w(TAG, "Settings could not be read (try ${attempt + 1})", e)
                    _reading.value = false
                    if (attempt + 1 >= READ_TRIES && !_state.value.loaded) _state.value = AppSettings(readFailed = true)
                    // A request made before this failure is answered by it.
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

    /**
     * Reads again now what could not be read: the file after [AppSettings.readFailed] rather than at the next retry,
     * or the [AppSettings.unreadSecrets] (Réessayer, or coming back to the app); nothing else.
     */
    fun readAgain() {
        val s = _state.value
        val unread = s.loaded && s.unreadSecrets.isNotEmpty()
        if (!s.readFailed && !unread) return
        _reading.value = true
        if (unread) rereads.update { it + 1 } else readNow.trySend(Unit)
    }

    /** The sealed values the last read did not open: read again, they are not waited for. */
    @Volatile
    private var waited = emptySet<String>()

    /**
     * The settings in this file. Values the Keystore newly failed to open are opened again after each of
     * [SECRET_WAITS_MS]; those already unread, wherever stored, are not waited for again, so that a write is read back
     * at once.
     */
    private suspend fun Preferences.toSettings(): AppSettings {
        var unopened = Unopened()
        var settings = readSettings(unopened)
        for (wait in SECRET_WAITS_MS) {
            if ((unopened.unreadValues - waited).isEmpty()) break
            delay(wait)
            unopened = Unopened()
            settings = readSettings(unopened)
        }
        waited = unopened.unreadValues
        return settings
    }

    private fun Preferences.readSettings(unopened: Unopened) = AppSettings(
        loaded = true,
        source = sealed<SourceConfig>(Keys.source, unopened),
        keptServer = sealed<SourceConfig.Jellyfin>(Keys.keptServer, unopened),
        deviceId = this[Keys.deviceId] ?: "",
        pathMappings = sealed<List<PathMapping>>(Keys.mappings, unopened).orEmpty(),
        forceHttp = this[Keys.forceHttp] ?: false,
        ui4k = this[Keys.ui4k] ?: true,
        tmdbApiKey = this[Keys.tmdbKey]?.let { opened(Keys.tmdbKey, it, unopened) }?.takeIf { it.isNotBlank() },
        collectionMinFilms = this[Keys.collectionMin] ?: CollectionThreshold.DEFAULT,
        metadataLanguage = this[Keys.metadataLanguage],
        libraryLanguage = this[Keys.libraryLanguage],
        trakt = sealed<TraktSession>(Keys.trakt, unopened),
        tracks = TrackPreferences(
            audio = this[Keys.audioLanguage],
            subtitles = SubtitleMode.entries.firstOrNull { it.name == this[Keys.subtitleMode] } ?: SubtitleMode.PLAYER,
            subtitleLanguage = this[Keys.subtitleLanguage],
        ),
        lostSecrets = unopened.lost,
        unreadSecrets = unopened.unread,
        settingsReset = this[Keys.settingsReset] ?: false,
    )

    /** The secrets a read could not open: [lost] for good, [unread] for now, as stored in [unreadValues]. */
    private class Unopened {
        val lost = mutableSetOf<SecretSetting>()
        val unread = mutableSetOf<SecretSetting>()
        val unreadValues = mutableSetOf<String>()
    }

    /** The value sealed at [key], opened and decoded; null when absent, or not opened and recorded in [unopened]. */
    private inline fun <reified T> Preferences.sealed(key: Preferences.Key<String>, unopened: Unopened): T? =
        this[key]?.let { opened(key, it, unopened) }?.let { decoded<T>(key, it, unopened) }

    /** A secret's plain text; null when not opened, recorded in [unopened] and logged without its value. */
    private fun opened(key: Preferences.Key<String>, stored: String, unopened: Unopened): String? = when (val read = secrets.read(stored)) {
        is Opened.Text -> read.plain
        Opened.Unavailable -> {
            Log.w(TAG, "${key.name} not opened: the Keystore is unavailable")
            unopened.unread += key.secret
            unopened.unreadValues += stored
            null
        }
        Opened.Undecryptable -> {
            Log.w(TAG, "${key.name} can no longer be decrypted")
            unopened.lost += key.secret
            null
        }
    }

    /** [text] decoded; null when it does not parse (a renamed field), recorded in [unopened] as lost. */
    private inline fun <reified T> decoded(key: Preferences.Key<String>, text: String, unopened: Unopened): T? = try {
        lenientJson.decodeFromString<T>(text)
    } catch (e: IllegalArgumentException) {
        // Includes SerializationException, whose message would quote the value: only its type is logged.
        Log.w(TAG, "${key.name} no longer reads: ${e.javaClass.simpleName}")
        unopened.lost += key.secret
        null
    }

    /**
     * Writes the file and returns once [state] reflects it, within [WRITE_READ_MS]: signing in saves the
     * source then goes home, whose rows read [state] at once.
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

    /** Saves the source; local folders hold no secret and are never sealed, so they never wait on the Keystore. */
    suspend fun setSource(source: SourceConfig?) {
        write { it.putSource(source) }
    }

    /**
     * Saves the source and the server kept signed in beside it ([kept], null for none) in one write, so neither is
     * ever saved without the other; both sealed before anything is written.
     */
    suspend fun setSource(source: SourceConfig, kept: SourceConfig.Jellyfin?) {
        write { prefs ->
            val sealed = kept?.let { secrets.seal(lenientJson.encodeToString(it)) }
            prefs.putSource(source)
            if (sealed == null) prefs.remove(Keys.keptServer) else prefs[Keys.keptServer] = sealed
        }
    }

    /**
     * Saves [source], keeping the server source it replaces as the kept server, still sealed: the Keystore did not open
     * it ([AppSettings.unreadSource]), so it is moved as stored, never dropped nor opened.
     */
    suspend fun setSourceKeepingUnopened(source: SourceConfig) {
        write { prefs ->
            val left = prefs[Keys.source]?.takeIf(secrets::isSealed)
            prefs.putSource(source)
            // One kept server at most: one already stored stays.
            if (left != null && Keys.keptServer !in prefs) prefs[Keys.keptServer] = left
        }
    }

    private fun MutablePreferences.putSource(source: SourceConfig?) {
        remove(Keys.settingsReset)
        when (source) {
            null -> remove(Keys.source)
            SourceConfig.Local -> this[Keys.source] = lenientJson.encodeToString<SourceConfig>(source)
            is SourceConfig.Jellyfin -> this[Keys.source] = secrets.seal(lenientJson.encodeToString<SourceConfig>(source))
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

    suspend fun setMetadataLanguage(tag: String?) {
        write { if (tag == null) it.remove(Keys.metadataLanguage) else it[Keys.metadataLanguage] = tag }
    }

    suspend fun setLibraryLanguage(tag: String) {
        write { it[Keys.libraryLanguage] = tag }
    }

    suspend fun setTracks(tracks: TrackPreferences) {
        write {
            if (tracks.audio == null) it.remove(Keys.audioLanguage) else it[Keys.audioLanguage] = tracks.audio
            it[Keys.subtitleMode] = tracks.subtitles.name
            if (tracks.subtitleLanguage == null) it.remove(Keys.subtitleLanguage) else it[Keys.subtitleLanguage] = tracks.subtitleLanguage
        }
    }

    /** Saves the Trakt account's tokens sealed, or forgets them ([session] null). */
    suspend fun setTrakt(session: TraktSession?) {
        write { if (session == null) it.remove(Keys.trakt) else it[Keys.trakt] = secrets.seal(lenientJson.encodeToString(session)) }
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

/** Waits before opening again secrets the Keystore did not open: three tries over two seconds, as a daemon restarts. */
private val SECRET_WAITS_MS = listOf(500L, 1_500L)

/** The wait after a first failed read, doubled after each one, up to [MAX_READ_WAIT_MS]. */
private const val FIRST_READ_WAIT_MS = 500L
private const val MAX_READ_WAIT_MS = 30_000L
private const val MAX_SHIFT = 16

/** How long a write waits for [SettingsStore.state]: the Keystore's slowest opening plus one retry. */
private const val WRITE_READ_MS = 2_000L
