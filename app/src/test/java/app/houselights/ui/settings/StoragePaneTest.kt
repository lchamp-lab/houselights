package app.houselights.ui.settings

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.waitWithLooper
import app.houselights.ui.util.formatBytes
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Réglages › Stockage: what each part of the space holds, under its name, and what « Nettoyer maintenant » did. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
class StoragePaneTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SettingsFixture

    @Before
    fun open() {
        settings = SettingsFixture(folder.root)
    }

    @After
    fun close() = settings.close()

    private fun show(storage: StorageStats?) {
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) { LazyColumn { storagePane(storage) } }
        }
        compose.waitForIdle()
    }

    private fun said(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).assertExists()

    private fun said(id: Int) = said(context.getString(id))

    private fun bytes(n: Long) = formatBytes(context, n)

    /** The total, the cache's limit, then each part's figure; the local images, none kept, say so in a word. */
    @Test
    fun eachPartIsCountedUnderItsName() {
        val storage = StorageStats(artworkFiles = 0, artworkBytes = 0, imageCacheBytes = CACHE, imageCacheMax = LIMIT, databaseBytes = BASE)
        show(storage)
        said(bytes(CACHE + BASE))
        said(context.getString(R.string.storage_cache_limit, bytes(LIMIT)))
        said(R.string.storage_image_cache)
        said(R.string.storage_database)
        said(bytes(BASE))
        said(R.string.storage_artwork)
        said(R.string.storage_none)
        // The cache's figure, under its name and on the row that empties it.
        assertTwice(bytes(CACHE))
    }

    /** Local images kept are counted, by their size, as the other parts are. */
    @Test
    fun localImagesKeptAreCounted() {
        show(StorageStats(artworkFiles = 3, artworkBytes = ARTWORK, imageCacheBytes = CACHE, imageCacheMax = LIMIT, databaseBytes = BASE))
        said(bytes(ARTWORK))
        compose.onNodeWithText(context.getString(R.string.storage_none)).assertDoesNotExist()
    }

    /** A cleanup that finds nothing to delete says so on its row, where focus is. */
    @Test
    fun aCleanupWithNothingToDeleteSaysSo() {
        show(StorageStats(artworkFiles = 0, artworkBytes = 0, imageCacheBytes = CACHE, imageCacheMax = LIMIT, databaseBytes = BASE))
        compose.onNode(hasText(context.getString(R.string.cleanup_now)) and hasClickAction()).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.waitWithLooper("the cleanup's result") { settings.vm.cleaned != null }
        compose.onNode(hasText(context.getString(R.string.cleanup_nothing), substring = true) and hasClickAction()).assertExists()
    }

    private fun assertTwice(text: String) {
        val found = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size
        check(found == 2) { "« $text » shown $found times" }
    }

    private companion object {
        const val CACHE = 48_000_000L
        const val LIMIT = 250_000_000L
        const val BASE = 3_500_000L
        const val ARTWORK = 7_200_000L
    }
}
