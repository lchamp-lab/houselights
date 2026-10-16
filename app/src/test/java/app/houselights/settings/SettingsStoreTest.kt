package app.houselights.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import app.houselights.ui.testing.testSecrets
import app.houselights.ui.testing.testSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException

/** Reading the settings when the file or the Keystore fails. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
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

    private fun stored(secrets: Secrets) = mutablePreferencesOf(sourceKey to secrets.seal(Json.encodeToString<SourceConfig>(home)))

    /** A read that fails once at launch opened the welcome screen, and nothing saved after reached the app. */
    @Test
    fun aFailedReadIsTriedAgainWithoutTheDefaults() = runTest {
        val secrets = testSecrets()
        val store = FlakyStore(failures = 2, prefs = stored(secrets))
        val settings = SettingsStore(store, backgroundScope, secrets)
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
        val settings = SettingsStore(store, backgroundScope, secrets)
        // Three tries: at once, half a second later, then a second after that.
        advanceTimeBy(1_000)
        assertFalse(settings.state.value.readFailed)
        advanceTimeBy(1_000)
        assertTrue(settings.state.value.readFailed)
        assertFalse(settings.state.value.loaded)
        // Still tried, less and less often, until it reads.
        advanceTimeBy(60_000)
        assertTrue(settings.state.value.loaded)
        assertFalse(settings.state.value.readFailed)
        assertEquals(home, settings.state.value.source)
    }

    /** The Keystore's key is gone: the source reads as not set, and the settings say it was lost. */
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

    /**
     * Only the TMDB key is lost: the welcome screen says nothing of a connection, and the key saved
     * again is no longer said lost.
     */
    @Test
    fun eachSecretLostIsSaidApartAndAGoodOneSavedPutsItBack() = runTest {
        val tmdbKey = stringPreferencesKey("tmdb_api_key")
        val secrets = testSecrets()
        val prefs = mutablePreferencesOf(sourceKey to Json.encodeToString<SourceConfig>(SourceConfig.Local), tmdbKey to testSecrets().seal("k3y"))
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

    /** Réessayer, on the screen that says the settings could not be read, reads them at once. */
    @Test
    fun readingAgainDoesNotWaitForTheNextTry() = runTest {
        val secrets = testSecrets()
        val store = FlakyStore(failures = 5, prefs = stored(secrets))
        val settings = SettingsStore(store, backgroundScope, secrets)
        advanceTimeBy(2_000)
        assertTrue(settings.state.value.readFailed)
        val readsThen = store.reads
        // The next try would come two seconds after the last one.
        settings.readAgain()
        runCurrent()
        assertEquals(readsThen + 1, store.reads)
        assertFalse(settings.reading.value)
    }

    /** Sealing in the migration fails: the plain value stays, and the settings still open with it. */
    @Test
    fun aKeystoreThatCannotSealLeavesThePlainValue() = runTest {
        val broken = Secrets { throw ProviderException("Failed to generate key") }
        val plain = mutablePreferencesOf(sourceKey to Json.encodeToString<SourceConfig>(home))
        val migration = SealSecrets(broken)
        assertTrue(migration.shouldMigrate(plain))
        val migrated = migration.migrate(plain)
        assertEquals(plain[sourceKey], migrated[sourceKey])

        val settings = SettingsStore(FlakyStore(0, migrated), backgroundScope, broken)
        assertEquals(home, settings.state.first { it.loaded }.source)
    }

    /** "My files" holds no secret: chosen while the Keystore fails, it is written all the same, never a crash. */
    @Test
    fun theFoldersAreChosenWithoutTheKeystore() = runTest {
        val broken = Secrets { throw ProviderException("Failed to generate key") }
        val settings = testSettings(folder.root, backgroundScope, broken)
        settings.setSource(SourceConfig.Local)
        assertEquals(SourceConfig.Local, settings.state.first { it.source != null }.source)
    }
}
