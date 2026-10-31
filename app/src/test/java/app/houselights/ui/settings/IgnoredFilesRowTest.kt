package app.houselights.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.SourceConfig
import app.houselights.providers.local.IgnoredFileRow
import app.houselights.providers.local.IgnoredReason
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.nfsFolder
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.InterfaceLanguage
import app.houselights.settings.SettingsStore
import app.houselights.ui.design.FocusMemory
import app.houselights.ui.design.RestoreFocus
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.idleLibrary
import app.houselights.ui.testing.press
import app.houselights.ui.testing.pressInSheet
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import app.houselights.ui.util.FACT_GAP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Réglages › Bibliothèque: the videos the shows folders place nowhere are the last row of the library's actions, after
 * « À identifier », while there are some, never in ember; OK lists them, ten by their name over why, their folder and
 * where they lie, the rest counted, and how to fix it. Gone from under focus, or while its list was open, focus is on
 * « Analyser maintenant »; the list open keeps the files it shows until Back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
class IgnoredFilesRowTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val folder = TemporaryFolder()

    private val files = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val screens = ViewModelStore()
    private val toIdentify = MutableStateFlow(0)
    private val ignored = MutableStateFlow(emptyList<IgnoredFileRow>())
    private lateinit var db: LibraryDatabase
    private lateinit var store: SettingsStore
    private lateinit var vm: SettingsViewModel

    @Before
    fun open() = runBlocking {
        db = testDatabase { onCallingThread() }
        val library = idleLibrary(db.dao())
        store = testSettings(folder.root, files)
        vm = settingsModel(screens, store, library, work, toIdentifyCount = toIdentify, ignoredFiles = ignored)
        db.dao().insertSource(nfsFolder("Shows"))
        store.setSource(SourceConfig.Local, null)
        store.state.first { it.source == SourceConfig.Local }
        Unit
    }

    @After
    fun close() {
        screens.clear()
        work.cancel()
        files.cancel()
        db.close()
    }

    /** The library pane, focus restored from [memory] as Réglages restores it. */
    private fun show(memory: FocusMemory = FocusMemory()) {
        compose.setTvContent {
            CompositionLocalProvider(LocalSettingsViewModel provides vm) {
                val s by vm.settings.collectAsStateWithLifecycle()
                val rows = PaneRows(memory, PANE)
                val list = rememberLazyListState()
                RestoreFocus(memory, fallback = null) { key -> revealRow(list, key, memory) }
                LazyColumn(state = list) { libraryPane(s, { _, _, _ -> }, SourceDoors({}, {}, {}), rows) }
            }
        }
        compose.waitForIdle()
    }

    private fun row(count: String = "skipped") = compose.onNode(hasText(count, substring = true) and hasClickAction())

    private fun focusIsOn(name: String) = compose.onNode(isFocused()).assert(hasText(name, substring = true))

    private fun text(name: String) = compose.onNodeWithText(name, substring = true, useUnmergedTree = true)

    /** A file's line under its name, its [parts] spaced as facts. */
    private fun line(vararg parts: String) = compose.onNodeWithText(parts.joinToString(FACT_GAP), useUnmergedTree = true)

    /** OK on the row: its list opens. */
    private fun list() {
        row().requestFocus()
        compose.press(Key.DirectionCenter)
    }

    @Test
    fun theRowComesLastAndSaysHowManyOnlyWhileFilesAreLeftOut() {
        toIdentify.value = 2
        show()
        row().assertDoesNotExist()
        ignored.value = listOf(SHOGUN, NO_SHOW, ROOT)
        compose.waitForIdle()
        row("3 files skipped").assert(hasText("Their names don’t say where they belong"))
        val identify = compose.onNode(hasText("To identify", substring = true) and hasClickAction()).getUnclippedBoundsInRoot()
        assertTrue("after « À identifier »", row().getUnclippedBoundsInRoot().top > identify.top)
        ignored.value = emptyList()
        compose.waitForIdle()
        row().assertDoesNotExist()
    }

    @Test
    fun oneFileIsSaidAsOne() {
        ignored.value = listOf(SHOGUN)
        show()
        row("1 file skipped").assert(hasText("Its name doesn’t say where it belongs"))
        list()
        text("This video is in a shows folder").assertExists()
        text("To fix: name it after its show").assertExists()
    }

    /** Each file by its name over why first, then its folder and where it lies in it; one at the root, its folder alone. */
    @Test
    fun theListSaysEachFileWhyItsFolderAndWhereItLiesThenHowToFixIt() {
        ignored.value = listOf(SHOGUN, NO_SHOW, ROOT)
        show()
        list()
        text("These videos are in a shows folder").assertExists()
        text("Shōgun - Bonus.mkv").assertExists()
        line("No episode number", "Shows", "Shōgun (2024)/Season 1").assertExists()
        line("No show name", "Shows", "Misc").assertExists()
        line("No episode number", "Shows").assertExists()
        text("To fix: name them after their show and their number").assertExists()
    }

    @Test
    fun tenAreNamedAndTheRestCounted() {
        ignored.value = (1..600).map { IgnoredFileRow(SOURCE, "Shows", "Extras/Clip %03d.mkv".format(it), IgnoredReason.NO_NUMBER) }
        show()
        list()
        text("Clip 010.mkv").assertExists()
        text("Clip 011.mkv").assertDoesNotExist()
        text("And 590 more").assertExists()
    }

    @Test
    fun backFromTheListGivesFocusBackToTheRow() {
        ignored.value = listOf(SHOGUN)
        show()
        list()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        text("This video is in a shows folder").assertDoesNotExist()
        focusIsOn("1 file skipped")
    }

    @Test
    fun theRowLeavingFromUnderFocusLeavesItOnScanNow() {
        ignored.value = listOf(SHOGUN)
        show()
        row().requestFocus()
        ignored.value = emptyList()
        compose.waitForIdle()
        row().assertDoesNotExist()
        focusIsOn("Scan now")
    }

    /** A scan placing every file while their list is open: it keeps them until Back, which finds « Analyser maintenant ». */
    @Test
    fun theListOpenKeepsItsFilesWhenAScanPlacesThemAndBackFindsScanNow() {
        ignored.value = listOf(SHOGUN, NO_SHOW)
        show()
        list()
        ignored.value = emptyList()
        compose.waitForIdle()
        text("2 files skipped").assertExists()
        text("Shōgun - Bonus.mkv").assertExists()
        compose.pressInSheet(KeyEvent.KEYCODE_BACK)
        row().assertDoesNotExist()
        focusIsOn("Scan now")
    }

    /** Back to the pane once no file is left out: the row's place gives focus to « Analyser maintenant ». */
    @Test
    fun backToThePaneWithTheRowGoneFocusIsOnScanNow() {
        show(FocusMemory(initial = "pane:$PANE/row:actions/row:ignored_files"))
        focusIsOn("Scan now")
    }

    /** The fix is led by its label, set apart, and its examples never break at a line's end, in every language. */
    @Test
    fun theFixStartsWithItsLabelAndKeepsItsExamplesWholeInEveryLanguage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InterfaceLanguage.LANGUAGES.forEach { tag ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val local = context.createConfigurationContext(config).resources
            val label = local.getString(R.string.ignored_files_fix_label)
            listOf(R.string.ignored_files_fix_one, R.string.ignored_files_fix).map(local::getString).forEach { fix ->
                assertTrue("$tag: «$fix» after «$label»", fix.startsWith(label) && fix.length > label.length)
                assertTrue("$tag: «$fix» breaks an example", "S01E02" in fix && " - " !in fix && "- " !in fix)
            }
        }
    }

    private companion object {
        const val PANE = "library"
        const val SOURCE = 1L
        val SHOGUN = IgnoredFileRow(SOURCE, "Shows", "Shōgun (2024)/Season 1/Shōgun - Bonus.mkv", IgnoredReason.NO_NUMBER)
        val NO_SHOW = IgnoredFileRow(SOURCE, "Shows", "Misc/E05.mkv", IgnoredReason.NO_SHOW)
        val ROOT = IgnoredFileRow(SOURCE, "Shows", "Trailer.mkv", IgnoredReason.NO_NUMBER)
    }
}
