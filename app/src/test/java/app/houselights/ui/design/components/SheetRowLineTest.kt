package app.houselights.ui.design.components

import android.content.Context
import android.content.res.Configuration
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * DESIGN.md, Sheets: every action row describes itself in one line, so rows keep one height, in each of the app's
 * languages, at the default text size and Android's largest; each row as its sheet lays it out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = TV)
// Real fonts, so words measure as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetRowLineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyDescriptionHoldsOnOneLine() = assertOneLine(1f)

    @Test
    fun everyDescriptionHoldsOnOneLineAtTheLargestTextSize() = assertOneLine(LARGEST)

    /** What a row lays beside its words, as its sheet does. */
    private enum class Lead { None, Preview, Icon }

    private class Line(val text: String, val name: String, val lead: Lead, val checked: Boolean)

    private fun assertOneLine(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lines = InterfaceLanguage.LANGUAGES.flatMap { tag ->
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val local = context.createConfigurationContext(config)
            val folders = local.resources.getQuantityString(R.plurals.source_local_folders, MANY, MANY)
            ROWS.map { (id, lead, checked) -> Line(local.getString(id), "$tag: ${name(id)}", lead, checked) } +
                Line(folders, "$tag: source_local_folders", Lead.Icon, checked = true)
        }
        val widths = mutableMapOf<String, Int>()
        compose.setTvContent {
            HlSideSheet("Sheet", onDismiss = {}) {
                val measurer = rememberTextMeasurer()
                lines.forEach { line ->
                    widths[line.text] = measurer.measure(line.text, Hl.type.meta, softWrap = false, maxLines = 1).size.width
                    Row(line)
                }
            }
        }
        compose.waitForIdle()
        val cut = lines.mapNotNull { line ->
            val room = compose.onAllNodesWithText(line.text, useUnmergedTree = true).fetchSemanticsNodes().minOf { it.size.width }
            val width = widths.getValue(line.text)
            "${line.name}: $width > $room «${line.text}»".takeIf { width > room }
        }
        assertTrue("At $scale, on two lines:\n" + cut.joinToString("\n"), cut.isEmpty())
    }

    private fun name(id: Int) = ApplicationProvider.getApplicationContext<Context>().resources.getResourceEntryName(id)

    @Composable
    private fun Row(line: Line) {
        HlSettingRow(
            "Row",
            description = line.text,
            icon = HlIcons.Folder.takeIf { line.lead == Lead.Icon },
            onClick = {},
            trailing = if (line.checked) ({ ChoiceCheck() }) else null,
            leading = if (line.lead == Lead.Preview) ({ RowPreview(null, PreviewShape.Backdrop) }) else null,
        )
    }

    private companion object {
        const val LARGEST = 1.3f
        const val MANY = 12

        /** Every description a sheet row says, with what it lays beside its words: Modifier, a folder, the source, choices. */
        val ROWS = listOf(
            Triple(R.string.artwork_poster_desc, Lead.Preview, false),
            Triple(R.string.artwork_backdrop_desc, Lead.Preview, false),
            Triple(R.string.artwork_logo_desc, Lead.Preview, false),
            Triple(R.string.identify_menu_desc, Lead.Preview, false),
            Triple(R.string.scan_now_desc, Lead.None, false),
            Triple(R.string.edit_folder_row_desc, Lead.None, false),
            Triple(R.string.edit_folder_login_desc, Lead.None, false),
            Triple(R.string.edit_folder_unreachable_desc, Lead.None, false),
            Triple(R.string.edit_folder_not_found_desc, Lead.None, false),
            Triple(R.string.remove_folder_row_desc, Lead.None, false),
            Triple(R.string.tmdb_key_revert_desc, Lead.None, false),
            Triple(R.string.source_local_first, Lead.Icon, true),
            Triple(R.string.source_jellyfin_desc, Lead.Icon, true),
            Triple(R.string.source_jellyfin_other, Lead.Icon, true),
            Triple(R.string.collections_two_desc, Lead.None, true),
            Triple(R.string.collections_all_desc, Lead.None, true),
            Triple(R.string.collections_off_desc, Lead.None, true),
            Triple(R.string.ui_4k_desc, Lead.None, true),
            Triple(R.string.ui_system_desc, Lead.None, true),
            Triple(R.string.network_path_desc, Lead.None, true),
            Triple(R.string.http_stream_desc, Lead.None, true),
        )
    }
}
