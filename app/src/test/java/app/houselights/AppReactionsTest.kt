package app.houselights

import app.houselights.core.SourceConfig
import app.houselights.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the app does on its own when a setting changes, as its container starts it. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppReactionsTest {
    private val folders = AppSettings(loaded = true, source = SourceConfig.Local, tmdbApiKey = "k0")

    /** A new key, or back to the built-in one, retries the folders' unmatched titles; the key read at launch does not. */
    @Test
    fun aNewTmdbKeyRetriesTheFolders() = runTest {
        val settings = MutableStateFlow(AppSettings())
        var retries = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { retryOnTmdbKeyChange(settings) { retries++ } }

        settings.value = folders
        settings.value = folders.copy(forceHttp = true)
        assertEquals("the key read at launch, then another setting", 0, retries)
        settings.value = folders.copy(tmdbApiKey = "k1")
        assertEquals(1, retries)
        settings.value = folders.copy(tmdbApiKey = null)
        assertEquals("back to the built-in key", 2, retries)
    }

    /** On a server, a new key retries nothing: the folders are not the source. */
    @Test
    fun aNewTmdbKeyOnAServerRetriesNothing() = runTest {
        val server = folders.copy(source = SourceConfig.Jellyfin("https://nas", null, "u1", null, "t"))
        val settings = MutableStateFlow(server)
        var retries = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { retryOnTmdbKeyChange(settings) { retries++ } }

        settings.value = server.copy(tmdbApiKey = "k1")
        assertEquals(0, retries)
        settings.value = folders.copy(tmdbApiKey = "k2")
        assertEquals("the next key, once back on the folders", 1, retries)
    }

    /** Each new interface language rewords the library; the one the app starts in does not. */
    @Test
    fun aNewInterfaceLanguageRewordsTheLibrary() = runTest {
        val chosen = MutableStateFlow<String?>("fr")
        var changes = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rewordOnLanguageChange(chosen) { changes++ } }

        assertEquals(0, changes)
        chosen.value = "en"
        chosen.value = "en"
        assertEquals(1, changes)
        chosen.value = null
        assertEquals("back to the device's language", 2, changes)
    }
}
