package app.houselights.ui.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.LibraryDao
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.SourceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The library over [dao] as a screen reads it, whose scans never run (they would go to the network): a scan asked, as
 * after a folder saved, is dropped. [forget] closes a folder's sessions, as the app's does on its address.
 */
fun idleLibrary(dao: LibraryDao, forget: suspend (SourceEntity) -> Unit = {}): LocalLibrary {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
    val idle = CoroutineScope(Job().apply { cancel() })
    val scanner = LibraryScanner(dao, { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
    return LocalLibrary(context, dao, scanner, artwork, forget)
}
