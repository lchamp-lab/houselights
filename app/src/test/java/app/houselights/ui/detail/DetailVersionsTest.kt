package app.houselights.ui.detail

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.Version
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.film
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A film in two copies: the Versions sheet offers a choice only when its source keeps one. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class DetailVersionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Saves at once, on the main thread, as the app's scope does. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val catalog = FakeCatalog().apply {
        copies = mapOf("dune" to listOf(copy("uhd", "2160p", first = true), copy("hd", "1080p", first = false)))
    }

    /** A server's copies: the first is the one it plays. */
    private val provider = FakeProvider().apply {
        items["dune"] = film("dune", "Dune")
        catalog = this@DetailVersionsTest.catalog
        capabilities = capabilities.copy(versions = true)
    }

    @After
    fun stop() = work.cancel()

    private fun copy(key: String, label: String, first: Boolean) =
        Version(film("dune", "Dune"), null, label, null, null, null, chosen = first, best = first, key = key)

    private fun text(id: Int) = context.getString(id)

    /** The film's page, its Versions sheet open, focus on the second copy. */
    private fun versionsOpen() {
        val vm = DetailViewModel("dune", { provider }, FakeEvents(), work)
        compose.setTvContent {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.versions)).requestFocus()
        compose.press(Key.DirectionCenter)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_DOWN)
    }

    private fun choices() = catalog.calls.filter { it.startsWith("choose") }

    @Test
    fun aServerThatKeepsNoChoiceShowsItsCopiesToReadOnly() {
        versionsOpen()
        compose.onNodeWithText(text(R.string.versions_server_first)).assertExists()
        compose.onAllNodes(hasContentDescription(text(R.string.version_chosen), substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(emptyList<String>(), choices())
        compose.onNodeWithText(text(R.string.versions_server_first)).assertExists()
    }

    @Test
    fun aSourceThatKeepsTheChoiceOffersIt() {
        provider.capabilities = provider.capabilities.copy(versionChoice = true)
        versionsOpen()
        compose.onNodeWithText(text(R.string.versions_server_first)).assertDoesNotExist()
        compose.onAllNodes(hasContentDescription(text(R.string.version_chosen), substring = true), useUnmergedTree = true)
            .assertCountEquals(1)
        compose.pressInSheet(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("choose hd"), choices())
    }
}
