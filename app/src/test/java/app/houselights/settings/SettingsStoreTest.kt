package app.houselights.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.houselights.core.Secrets
import app.houselights.core.SecretsUnavailableException
import app.houselights.core.SourceConfig
import app.houselights.core.SubtitleMode
import app.houselights.core.TrackPreferences
import app.houselights.core.TraktSession
import app.houselights.testing.secretsOf
import app.houselights.testing.testKey
import app.houselights.testing.testSecrets
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException
import java.util.Collections
import java.util.concurrent.Executors
import javax.crypto.SecretKey
import kotlin.coroutines.EmptyCoroutineContext

/** Reading the settings when the file or the Keystore fails. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val home = SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "token")
    private val sourceKey = stringPreferencesKey("source")

    /** A settings file whose first [failures] reads fail, then holds [prefs]. */
    private class FlakyStore(private val failures: Int, private val prefs: Preferences) : DataStore<Preferences> {
        var reads = 0

        override val data: Flow<Preferences> = flow {
            if (++reads <= failures) throw IOException("Disk busy")
            emit(prefs)
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(prefs)
    }

    /** A settings file in memory that emits again only when a write changes it, as DataStore does. */
    private class MemoryStore(prefs: Preferences) : DataStore<Preferences> {
        val file = MutableStateFlow(prefs)

        override val data: Flow<Preferences> = file

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(file.value).also { file.value = it }
    }

    /** A Keystore that gives no key while [down], then [key]; [loads] counts its answers. */
    private class FlakyKeystore(val key: SecretKey = testKey(), var down: Boolean = true) {
        var loads = 0
        val secrets = secretsOf {
            loads++
            if (down) throw ProviderException("Keystore operation failed") else key
        }
    }

    private fun stored(secrets: Secrets) = mutablePreferencesOf(sourceKey to secrets.seal(Json.encodeToString<SourceConfig>(home)))

    /** Settings read apart from where they are collected, as on the device, on the test's scheduler for both. */
    private fun TestScope.settingsOf(store: DataStore<Preferences>, secrets: Secrets) =
        SettingsStore(store, backgroundScope, secrets, readOn = StandardTestDispatcher(testScheduler))

    /** Opening secrets loads the Keystore key and calls its daemon: never on the collecting (main) thread. */
    @Test
    fun secretsAreOpenedAwayFromTheThreadThatCollects() = runTest {
        val key = testKey()
        val keyAskedOn = Collections.synchronizedList(mutableListOf<Thread>())
        val reader = secretsOf {
            keyAskedOn += Thread.currentThread()
            key
        }
        val collecting = Collections.synchronizedList(mutableListOf<Thread>())
        val collector = Executors.newSingleThreadExecutor { Thread(it, "settings collector").also(collecting::add) }.asCoroutineDispatcher()
        val scope = CoroutineScope(collector + SupervisorJob())
        try {
            val settings = SettingsStore(FlakyStore(0, stored(secretsOf { key })), scope, reader)
            assertEquals(home, settings.state.first { it.loaded }.source)
            assertTrue("The key was never asked for", keyAskedOn.isNotEmpty())
            assertTrue("Opened on the collector's thread", keyAskedOn.none { it in collecting })
        } finally {
            scope.cancel()
            collector.close()
        }
    }

    /** Settings are read off the main thread, yet home, right after sign-in, gets the source just saved. */
    @Test
    fun aSourceSavedIsReadBeforeItsSaveReturns() = runTest {
        val key = testKey()
        // A Keystore as slow as a player's.
        val slow = secretsOf {
            Thread.sleep(SLOW_KEYSTORE_MS)
            key
        }
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(main + SupervisorJob())
        val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = io, produceFile = { folder.root.resolve("settings.preferences_pb") })
            val settings = SettingsStore(store, scope, slow)
            val read = withContext(main) {
                settings.state.first { it.loaded }
                settings.setSource(home)
                settings.state.value.source
            }
            assertEquals(home, read)
        } finally {
            scope.cancel()
            io.cancel()
            main.close()
        }
    }

    /** A read failing at launch is retried, never replaced by the defaults that open the welcome screen. */
    @Test
    fun aFailedReadIsTriedAgainWithoutTheDefaults() = runTest {
        val secrets = testSecrets()
        val store = FlakyStore(failures = 2, prefs = stored(secrets))
        val settings = settingsOf(store, secrets)
        runCurrent()
        assertFalse("The defaults stood in for the settings", settings.state.value.loaded)
        assertFalse(settings.state.value.readFailed)
        advanceTimeBy(5_000)
        assertTrue(settings.state.value.loaded)
        assertEquals(home, settings.state.value.source)
        assertEquals(3, store.reads)
    }

    @Test
    fun readsThatKeepFailingSaySo() = runTest {
        val secrets = testSecrets()
        val store = FlakyStore(failures = 6, prefs = stored(secrets))
        val settings = settingsOf(store, secrets)
        // Three tries: at once, half a second later, then a second after that.
        advanceTimeBy(1_000)
        assertFalse(settings.state.value.readFailed)
        advanceTimeBy(1_000)
        assertTrue(settings.state.value.readFailed)
        assertFalse(settings.state.value.loaded)
        // Still retried, less and less often, until it reads.
        advanceTimeBy(60_000)
        assertTrue(settings.state.value.loaded)
        assertFalse(settings.state.value.readFailed)
        assertEquals(home, settings.state.value.source)
    }

    /** The Keystore's key is gone: the source reads as not set, and the settings report it lost. */
    @Test
    fun aSecretThatCanNoLongerBeReadIsSaidLost() = runTest {
        val written = testSettings(folder.root, backgroundScope)
        written.setSource(home)
        written.state.first { it.source != null }
        assertEquals(emptySet<SecretSetting>(), written.state.value.lostSecrets)

        val store = FlakyStore(failures = 0, prefs = stored(testSecrets()))
        val other = SettingsStore(store, backgroundScope, testSecrets())
        val read = other.state.first { it.loaded }
        assertEquals(null, read.source)
        assertEquals(setOf(SecretSetting.SOURCE), read.lostSecrets)
        assertTrue(read.lostSource)
    }

    /** Only the TMDB key lost: no connection message on the welcome screen; saving it again clears the loss. */
    @Test
    fun eachSecretLostIsSaidApartAndAGoodOneSavedPutsItBack() = runTest {
        val tmdbKey = stringPreferencesKey("tmdb_api_key")
        val secrets = testSecrets()
        val prefs = mutablePreferencesOf(
            sourceKey to Json.encodeToString<SourceConfig>(SourceConfig.Local),
            // Sealed with a key this device no longer has.
            tmdbKey to testSecrets().seal("k3y"),
        )
        val settings = SettingsStore(FlakyStore(0, prefs), backgroundScope, secrets)
        val read = settings.state.first { it.loaded }
        assertEquals(SourceConfig.Local, read.source)
        assertEquals(setOf(SecretSetting.TMDB_KEY), read.lostSecrets)
        assertFalse(read.lostSource)

        val written = testSettings(folder.root, backgroundScope, secrets)
        written.setTmdbApiKey("n3w")
        assertEquals("n3w", written.state.first { it.tmdbApiKey != null }.tmdbApiKey)
        assertEquals(emptySet<SecretSetting>(), written.state.value.lostSecrets)
    }

    /** Réessayer, on the screen saying the settings could not be read, reads them at once. */
    @Test
    fun readingAgainDoesNotWaitForTheNextTry() = runTest {
        val secrets = testSecrets()
        val store = FlakyStore(failures = 5, prefs = stored(secrets))
        val settings = settingsOf(store, secrets)
        advanceTimeBy(2_000)
        assertTrue(settings.state.value.readFailed)
        val readsThen = store.reads
        // The next retry would come two seconds after the last one.
        settings.readAgain()
        runCurrent()
        assertEquals(readsThen + 1, store.reads)
        assertFalse(settings.reading.value)
    }

    /** The Keystore failing at the first opening, as a restarting daemon does: the read waits and opens it again. */
    @Test
    fun aKeystoreFailingBrieflyIsTriedAgainWithinTheRead() = runTest {
        val keystore = FlakyKeystore()
        val settings = settingsOf(FlakyStore(0, stored(secretsOf { keystore.key })), keystore.secrets)
        runCurrent()
        keystore.down = false
        assertFalse("Said before the Keystore was asked again", settings.state.value.loaded)
        advanceTimeBy(600)
        val read = settings.state.value
        assertEquals(home, read.source)
        assertEquals(emptySet<SecretSetting>(), read.unreadSecrets)
        assertEquals(emptySet<SecretSetting>(), read.lostSecrets)
    }

    /**
     * The Keystore failing for good at launch: after three tries over two seconds the connection is unread, not lost.
     * Nothing rewrites it, a write is read back without waiting for it again, and Réessayer opens it once the Keystore
     * answers.
     */
    @Test
    fun aSecretTheKeystoreDoesNotOpenIsUnreadNotLost() = runTest {
        val keystore = FlakyKeystore()
        val sealed = stored(secretsOf { keystore.key })
        val store = MemoryStore(sealed)
        val settings = settingsOf(store, keystore.secrets)
        advanceTimeBy(1_900)
        assertFalse(settings.state.value.loaded)
        advanceTimeBy(200)
        val read = settings.state.value
        assertTrue(read.loaded)
        assertFalse(read.readFailed)
        assertNull(read.source)
        assertEquals(setOf(SecretSetting.SOURCE), read.unreadSecrets)
        assertTrue(read.unreadSource)
        assertEquals(emptySet<SecretSetting>(), read.lostSecrets)
        assertFalse(read.lostSource)
        assertEquals(3, keystore.loads)

        settings.setForceHttp(true)
        assertTrue(settings.state.value.forceHttp)
        assertTrue(settings.state.value.unreadSource)
        assertEquals("tried once", 4, keystore.loads)
        assertEquals(sealed[sourceKey], store.file.value[sourceKey])

        keystore.down = false
        settings.readAgain()
        assertTrue(settings.reading.value)
        runCurrent()
        assertEquals(home, settings.state.value.source)
        assertEquals(emptySet<SecretSetting>(), settings.state.value.unreadSecrets)
        assertFalse(settings.reading.value)
        // Nothing left to read: nothing is read.
        val loads = keystore.loads
        settings.readAgain()
        runCurrent()
        assertFalse(settings.reading.value)
        assertEquals(loads, keystore.loads)
    }

    /** A Keystore that stays broken locks nothing: the folders can still be chosen, which replace the unread connection. */
    @Test
    fun aKeystoreThatStaysBrokenStillLetsTheFoldersBeChosen() = runTest {
        val keystore = FlakyKeystore()
        val settings = settingsOf(MemoryStore(stored(secretsOf { keystore.key })), keystore.secrets)
        advanceTimeBy(2_100)
        assertTrue(settings.state.value.unreadSource)
        settings.setSource(SourceConfig.Local)
        advanceTimeBy(100)
        assertEquals(SourceConfig.Local, settings.state.value.source)
        assertEquals(emptySet<SecretSetting>(), settings.state.value.unreadSecrets)
    }

    @Test
    fun aKeystoreThatCannotSealLeavesThePlainValue() = runTest {
        val broken = secretsOf { throw ProviderException("Failed to generate key") }
        val plain = mutablePreferencesOf(sourceKey to Json.encodeToString<SourceConfig>(home))
        val migration = SealSecrets(broken)
        assertTrue(migration.shouldMigrate(plain))
        val migrated = migration.migrate(plain)
        assertEquals(plain[sourceKey], migrated[sourceKey])

        val settings = SettingsStore(FlakyStore(0, migrated), backgroundScope, broken)
        assertEquals(home, settings.state.first { it.loaded }.source)
    }

    /** Until chosen, the player picks the tracks; the choice is read back, a language left out follows the app. */
    @Test
    fun theTracksAskedAreWrittenThenReadBack() = runTest {
        val settings = testSettings(folder.root, backgroundScope, testSecrets())
        assertEquals(TrackPreferences(), settings.state.first { it.loaded }.tracks)
        val vost = TrackPreferences(audio = "ja", subtitles = SubtitleMode.FOREIGN)
        settings.setTracks(vost)
        assertEquals(vost, settings.state.first { it.tracks != TrackPreferences() }.tracks)
        settings.setTracks(TrackPreferences(subtitles = SubtitleMode.OFF, subtitleLanguage = "en"))
        assertEquals(TrackPreferences(null, SubtitleMode.OFF, "en"), settings.state.first { it.tracks.audio == null }.tracks)
    }

    /** "My files" holds no secret: chosen while the Keystore fails, it is still written, never a crash. */
    @Test
    fun theFoldersAreChosenWithoutTheKeystore() = runTest {
        val broken = secretsOf { throw ProviderException("Failed to generate key") }
        val settings = testSettings(folder.root, backgroundScope, broken)
        settings.setSource(SourceConfig.Local)
        assertEquals(SourceConfig.Local, settings.state.first { it.source != null }.source)
    }

    /** Trakt's tokens are written sealed, read back, unreadable under another key, and forgotten on disconnecting. */
    @Test
    fun traktsTokensAreSealedThenReadBack() = runTest {
        val traktKey = stringPreferencesKey("trakt")
        val lea = TraktSession("acc3ss", "r3fresh", expiresAt = 1_700_000_000, username = "lea")
        val secrets = testSecrets()
        val file = folder.root.resolve("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val settings = SettingsStore(store, backgroundScope, secrets, readOn = EmptyCoroutineContext)
        settings.state.first { it.loaded }

        settings.setTrakt(lea)
        assertEquals(lea, settings.state.first { it.trakt != null }.trakt)
        val stored = checkNotNull(store.data.first()[traktKey])
        assertTrue(secrets.isSealed(stored))
        assertFalse(stored, "acc3ss" in stored || "r3fresh" in stored)

        val other = SettingsStore(FlakyStore(0, mutablePreferencesOf(traktKey to stored)), backgroundScope, testSecrets())
        val read = other.state.first { it.loaded }
        assertNull(read.trakt)
        assertEquals(setOf(SecretSetting.TRAKT), read.lostSecrets)

        settings.setTrakt(null)
        assertNull(settings.state.first { it.trakt == null }.trakt)
        assertNull(store.data.first()[traktKey])
    }

    /** The server kept beside the folders: written sealed with the source, read back, lost under another key, removed. */
    @Test
    fun theKeptServerIsSealedWithTheSourceThenReadBack() = runTest {
        val keptKey = stringPreferencesKey("kept_server")
        val secrets = testSecrets()
        val file = folder.root.resolve("settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val settings = SettingsStore(store, backgroundScope, secrets, readOn = EmptyCoroutineContext)
        settings.state.first { it.loaded }

        settings.setSource(SourceConfig.Local, kept = home)
        val read = settings.state.first { it.keptServer != null }
        assertEquals(SourceConfig.Local, read.source)
        assertEquals(home, read.keptServer)
        val stored = checkNotNull(store.data.first()[keptKey])
        assertTrue(secrets.isSealed(stored))
        assertFalse(stored, "token" in stored || "nas:8920" in stored)

        val other = SettingsStore(FlakyStore(0, mutablePreferencesOf(keptKey to stored)), backgroundScope, testSecrets())
        val lost = other.state.first { it.loaded }
        assertNull(lost.keptServer)
        assertEquals(setOf(SecretSetting.KEPT_SERVER), lost.lostSecrets)
        assertFalse(lost.lostSource)

        settings.setSource(home, kept = null)
        assertEquals(home, settings.state.first { it.keptServer == null }.source)
        assertNull(store.data.first()[keptKey])
    }

    /** A kept server the Keystore cannot seal leaves the file as it was: the source does not change without it. */
    @Test
    fun aKeptServerTheKeystoreCannotSealWritesNothing() = runTest {
        val broken = secretsOf { throw ProviderException("Failed to generate key") }
        val settings = testSettings(folder.root, backgroundScope, broken)
        val refused = runCatching { settings.setSource(SourceConfig.Local, kept = home) }.exceptionOrNull()
        assertTrue("$refused", refused is SecretsUnavailableException)
        assertNull(settings.state.value.source)
        assertNull(settings.state.value.keptServer)
    }

    private companion object {
        /** Each opening of a secret on a slow player: long enough to be seen after the file is written. */
        const val SLOW_KEYSTORE_MS = 150L
    }
}
