package app.houselights.ui.design.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.settings.InterfaceLanguage
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, Secondary screens: every row of a pane or a sheet says what it does in one line, beside the value it shows
 * at rest, so rows keep one height, in each of the app's languages, at the default text size and Android's largest;
 * each row as its pane or its sheet lays it out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so words measure as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaneRowLineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyDescriptionHoldsOnOneLine() = assertOneLine(1f)

    @Test
    fun everyDescriptionHoldsOnOneLineAtTheLargestTextSize() = assertOneLine(LARGEST)

    /** What a row lays beside its words, as its pane or sheet does. */
    private enum class Beside { None, Icon, Check }

    /** A row's description [text], what it is ([name]), beside what, and the [value] it shows. */
    private class Line(val text: String, val name: String, val beside: Beside, val value: String? = null)

    private fun assertOneLine(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val panes = mutableListOf<Line>()
        val sheets = mutableListOf<Line>()
        InterfaceLanguage.LANGUAGES.forEach { tag ->
            val locale = Locale.forLanguageTag(tag)
            val local = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
            panes += paneLines(Words(local, tag, locale))
            sheets += sheetLines(Words(local, tag, locale))
        }
        val widths = mutableMapOf<String, Int>()
        compose.setTvContent {
            val measurer = rememberTextMeasurer()
            (panes + sheets).forEach {
                widths[it.text] = measurer.measure(it.text, Hl.type.meta, softWrap = false, maxLines = 1).size.width
            }
            HlTwoPane(side = {}) { Column { HlSettingGroup(null) { panes.forEach { Row(it) } } } }
            HlSideSheet("Sheet", onDismiss = {}) { HlSettingGroup(null) { sheets.forEach { Row(it) } } }
        }
        compose.waitForIdle()
        val cut = (panes + sheets).mapNotNull { line ->
            val room = compose.onAllNodesWithText(line.text, useUnmergedTree = true).fetchSemanticsNodes().minOf { it.size.width }
            val width = widths.getValue(line.text)
            "${line.name}: $width > $room «${line.text}»".takeIf { width > room }
        }.distinct()
        assertTrue("At $scale, on two lines:\n" + cut.joinToString("\n"), cut.isEmpty())
    }

    private fun name(id: Int) = ApplicationProvider.getApplicationContext<Context>().resources.getResourceEntryName(id)

    /** A language's words: its resources, its tag and its locale. */
    private inner class Words(val local: Context, val tag: String, val locale: Locale) {
        fun line(id: Int, beside: Beside = Beside.None, value: String? = null) =
            Line(local.getString(id), "$tag: ${name(id)}", beside, value)

        /** The language's own name, as a language row shows it. */
        val language: String get() = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

        /** The longest of [ids], as a choice row shows it. */
        fun longest(vararg ids: Int): String = ids.map(local::getString).maxBy { it.length }
    }

    /** A pane's rows, each beside the value it shows at rest. */
    private fun paneLines(w: Words): List<Line> = with(w) {
        listOf(
            line(R.string.scan_now_desc, value = local.getString(R.string.scan_progress, SCANNED, FILES)),
            line(R.string.add_folder_row_desc),
            line(R.string.refresh_metadata_desc),
            line(R.string.error_tmdb_key_refused_title, value = local.getString(R.string.tmdb_personal)),
            line(R.string.error_tmdb_offline_title, value = local.getString(R.string.tmdb_builtin_short)),
            line(R.string.metadata_language_desc, value = language),
            line(
                R.string.collections_setting_desc,
                value = longest(R.string.collections_two, R.string.collections_all, R.string.collections_off),
            ),
            line(R.string.change_source_local_desc, value = local.getString(R.string.source_local)),
            line(R.string.change_source_jellyfin_desc, value = local.getString(R.string.source_jellyfin)),
            line(R.string.trakt_connect_desc),
            line(R.string.trakt_disconnect_desc),
            line(R.string.trakt_sync_now_desc),
            line(R.string.ui_resolution_desc, value = longest(R.string.ui_4k, R.string.ui_system)),
            line(R.string.interface_language_desc, value = language),
            line(R.string.cleanup_desc),
            line(R.string.clear_cache_desc, value = CACHE),
            line(R.string.support_desc),
            line(R.string.network_path_desc, Beside.Check),
            line(R.string.http_stream_desc, Beside.Check),
            line(R.string.add_mapping_desc),
        )
    }

    /** A sheet's rows: a folder's, the source's, Modifier's, the choices'. */
    private fun sheetLines(w: Words): List<Line> = with(w) {
        listOf(
            line(R.string.identify_menu_desc, Beside.Icon),
            line(R.string.scan_now_desc),
            line(R.string.edit_folder_row_desc),
            line(R.string.edit_folder_login_desc),
            line(R.string.edit_folder_unreachable_desc),
            line(R.string.edit_folder_not_found_desc),
            line(R.string.remove_folder_row_desc),
            line(R.string.tmdb_key_revert_desc),
            line(R.string.source_local_first, Beside.Icon),
            Line(
                local.resources.getQuantityString(R.plurals.source_local_folders, MANY, MANY),
                "$tag: source_local_folders",
                Beside.Icon,
            ),
            line(R.string.source_jellyfin_desc, Beside.Icon),
            line(R.string.source_jellyfin_other, Beside.Icon),
            line(R.string.collections_two_desc, Beside.Check),
            line(R.string.collections_all_desc, Beside.Check),
            line(R.string.collections_off_desc, Beside.Check),
            line(R.string.ui_4k_desc, Beside.Check),
            line(R.string.ui_system_desc, Beside.Check),
        )
    }

    @Composable
    private fun Row(line: Line) {
        HlSettingRow(
            "Row",
            description = line.text,
            value = line.value,
            icon = HlIcons.Folder.takeIf { line.beside == Beside.Icon },
            onClick = {},
            trailing = if (line.beside == Beside.Check) ({ ChoiceCheck() }) else null,
        )
    }

    private companion object {
        const val LARGEST = 1.3f
        const val MANY = 12

        /** A scan's count, as long as a library's. */
        const val SCANNED = 1_234
        const val FILES = 5_678

        /** The image cache's size, as the row prints it. */
        const val CACHE = "151,5 Mo"
    }
}
