package app.houselights.update

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.houselights.core.lenientJson
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A corrupt file starts again empty: what it held is bookkeeping, found again at the next check. */
private val Context.updatesStore by preferencesDataStore(
    name = "updates",
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w(TAG, "Updates file unreadable, reset: ${e.javaClass.simpleName}")
        emptyPreferences()
    },
)

/** An install handed to Android, written before its commit and settled at the next start. */
@Serializable
data class Pending(val versionCode: Long, val versionName: String, val at: Long)

/** The user left for Android's settings to allow installs, for [versionCode]: settled on coming back or at the next start. */
@Serializable
data class Trip(val versionCode: Long, val at: Long)

/** What the updater keeps between starts. */
data class Saved(
    /** GitHub's last answer to the list (200 or 304), by the device's clock. */
    val checkedAt: Long? = null,
    val etag: String? = null,
    val releases: List<ListedRelease>? = null,
    /** The manifests read, by tag, as GitHub served them. */
    val manifests: Map<String, String> = emptyMap(),
    val waitUntil: Long? = null,
    val pending: Pending? = null,
    val updated: Updated? = null,
    /** The newest versionCode whose sheet home opened: it does not open again until a newer one. */
    val offeredOnHome: Long = 0,
    val trip: Trip? = null,
)

/**
 * The updater's own file, apart from the settings: its writes are frequent and bookkeeping, and every change of the
 * settings recomposes the app. A write that fails is logged, never fatal: the next check finds it all again.
 */
class UpdateStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.updatesStore)

    suspend fun read(): Saved = suspendRunCatching { store.data.first().saved() }
        .onFailure { Log.w(TAG, "Updates file not read", it) }
        .getOrDefault(Saved())

    private fun Preferences.saved() = Saved(
        checkedAt = this[Keys.checkedAt],
        etag = this[Keys.etag],
        releases = decoded(Keys.releases, RELEASES),
        manifests = decoded(Keys.manifests, MANIFESTS).orEmpty(),
        waitUntil = this[Keys.waitUntil],
        pending = decoded(Keys.pending, Pending.serializer()),
        updated = decoded(Keys.updated, Updated.serializer()),
        offeredOnHome = this[Keys.offeredOnHome] ?: 0L,
        trip = decoded(Keys.trip, Trip.serializer()),
    )

    private fun <T> Preferences.decoded(key: Preferences.Key<String>, serializer: KSerializer<T>): T? = this[key]?.let { text ->
        try {
            lenientJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            Log.w(TAG, "${key.name} no longer reads: ${e.javaClass.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "${key.name} no longer reads: ${e.javaClass.simpleName}")
            null
        }
    }

    /** GitHub answered at [at]: a new list ([releases], its [etag]), or none for the same one (304). */
    suspend fun answered(at: Long, releases: List<ListedRelease>? = null, etag: String? = null) = write {
        it[Keys.checkedAt] = at
        it.remove(Keys.waitUntil)
        if (releases != null) {
            it[Keys.releases] = lenientJson.encodeToString(RELEASES, releases)
            if (etag == null) it.remove(Keys.etag) else it[Keys.etag] = etag
            // The manifests of releases still listed, read again only when a new one comes.
            val tags = releases.map(ListedRelease::tag).toSet()
            val kept = it.decoded(Keys.manifests, MANIFESTS).orEmpty().filterKeys(tags::contains)
            it[Keys.manifests] = lenientJson.encodeToString(MANIFESTS, kept)
        }
    }

    /** Keeps [text], the manifest of [tag]. */
    suspend fun manifest(tag: String, text: String) = write {
        val kept = it.decoded(Keys.manifests, MANIFESTS).orEmpty()
        it[Keys.manifests] = lenientJson.encodeToString(MANIFESTS, kept + (tag to text))
    }

    suspend fun waitUntil(at: Long?) = write { if (at == null) it.remove(Keys.waitUntil) else it[Keys.waitUntil] = at }

    suspend fun pending(value: Pending?) = put(Keys.pending, Pending.serializer(), value)

    suspend fun updated(value: Updated?) = put(Keys.updated, Updated.serializer(), value)

    suspend fun trip(value: Trip?) = put(Keys.trip, Trip.serializer(), value)

    suspend fun offeredOnHome(versionCode: Long) = write { it[Keys.offeredOnHome] = maxOf(it[Keys.offeredOnHome] ?: 0L, versionCode) }

    private suspend fun <T> put(key: Preferences.Key<String>, serializer: KSerializer<T>, value: T?) = write {
        if (value == null) it.remove(key) else it[key] = lenientJson.encodeToString(serializer, value)
    }

    private suspend fun write(transform: suspend (MutablePreferences) -> Unit) {
        suspendRunCatching { store.edit(transform) }.onFailure { Log.w(TAG, "Updates file not written", it) }
    }

    private object Keys {
        val checkedAt = longPreferencesKey("checked_at")
        val etag = stringPreferencesKey("etag")
        val releases = stringPreferencesKey("releases")
        val manifests = stringPreferencesKey("manifests")
        val waitUntil = longPreferencesKey("wait_until")
        val pending = stringPreferencesKey("pending")
        val updated = stringPreferencesKey("updated")
        val offeredOnHome = longPreferencesKey("offered_on_home")
        val trip = stringPreferencesKey("trip")
    }

    private companion object {
        val RELEASES = ListSerializer(ListedRelease.serializer())
        val MANIFESTS = MapSerializer(String.serializer(), String.serializer())
    }
}

private const val TAG = "Updates"
