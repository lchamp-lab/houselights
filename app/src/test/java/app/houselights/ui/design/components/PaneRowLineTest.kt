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
import app.houselights.ui.settings.relativeTime
import app.houselights.ui.settings.title
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.util.formatBytes as bytes
import app.houselights.ui.util.formatDuration
import app.houselights.update.UpdateFailure
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

    /** A row's description [text], what it is ([name]), beside what, and the [value] it shows, a figure in [jaquette]. */
    private class Line(val text: String, val name: String, val beside: Beside, val value: String? = null, val jaquette: Boolean = false)

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
        fun line(id: Int, beside: Beside = Beside.None, value: String? = null, jaquette: Boolean = false) =
            Line(local.getString(id), "$tag: ${name(id)}", beside, value, jaquette)

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
            line(R.string.to_identify_row_desc, value = TO_IDENTIFY, jaquette = true),
            line(R.string.ignored_files_desc_one),
            line(R.string.ignored_files_desc),
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
            Line(local.resources.getString(R.string.trakt_skipped_desc_one), "$tag: trakt_skipped_desc (1)", Beside.None),
            Line(local.resources.getString(R.string.trakt_skipped_desc), "$tag: trakt_skipped_desc", Beside.None),
            line(R.string.ui_resolution_desc, value = longest(R.string.ui_4k, R.string.ui_system)),
            line(R.string.interface_language_desc, value = language),
            line(
                R.string.text_size_desc,
                value = longest(R.string.text_size_compact, R.string.text_size_standard, R.string.text_size_large),
            ),
            line(R.string.cleanup_desc),
            line(R.string.clear_cache_desc, value = CACHE),
            line(R.string.support_desc),
            line(R.string.journal_desc),
            line(R.string.network_path_desc, Beside.Check),
            line(R.string.http_stream_desc, Beside.Check),
            line(R.string.add_mapping_desc),
        ) + updateLines(w)
    }

    /** About's « Mises à jour »: its two settings beside their longest choice, and every state its row says. */
    private fun updateLines(w: Words): List<Line> = with(w) {
        val now = System.currentTimeMillis()
        val upToDate = local.getString(R.string.update_up_to_date)
        fun said(id: Int, vararg args: Any, value: String? = null) =
            Line(local.getString(id, *args), "$tag: ${name(id)}", Beside.None, value)
        val failed = local.getString(R.string.update_failed)
        listOf(
            line(R.string.update_check_desc, value = longest(R.string.update_check_daily, R.string.update_check_never)),
            line(R.string.update_channel_desc, value = longest(R.string.update_stable, R.string.update_beta)),
            said(R.string.update_checked, relativeTime(local, now - 2 * HOUR_MS, now, short = true), value = upToDate),
            said(R.string.update_installed_ago, relativeTime(local, now - 3 * MINUTE_MS, now, short = true), value = upToDate),
            line(R.string.update_never_checked),
            line(R.string.update_check_off),
            said(R.string.update_available, BETA, value = local.getString(R.string.update_beta_mark) + DOT + bytes(local, APK)),
            said(
                R.string.update_downloading_version,
                BETA,
                value = local.getString(R.string.update_bytes_of, bytes(local, READ), bytes(local, APK)),
            ),
            said(R.string.update_verifying_version, BETA),
            said(R.string.update_ready_version, BETA),
            said(R.string.update_installing_version, BETA),
            line(R.string.update_must_allow, value = local.getString(R.string.update_required)),
            line(R.string.update_busy_title, value = local.getString(R.string.update_wait, formatDuration(20 * MINUTE_MS))),
        ) + UpdateFailure.entries.map { line(it.title, value = failed) }
    }

    /** A sheet's rows: a folder's, the source's, Modifier's, the choices', the answers for titles not found. */
    private fun sheetLines(w: Words): List<Line> = with(w) {
        listOf(
            line(R.string.identify_menu_desc, Beside.Icon),
            line(R.string.identify_menu_desc_none, Beside.Icon),
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
            Line(
                local.resources.getQuantityString(R.plurals.folder_count, MANY, MANY) + DOT +
                    local.getString(R.string.source_trakt_resumes),
                "$tag: folder_count, source_trakt_resumes",
                Beside.Icon,
            ),
            line(R.string.source_jellyfin_desc, Beside.Icon),
            Line("Jellyfin$DOT$USER$DOT" + local.getString(R.string.source_kept_resume), "$tag: source_kept_resume", Beside.Icon),
            line(R.string.source_jellyfin_another_desc, Beside.Icon),
            line(R.string.forget_server_desc, Beside.Icon),
            line(R.string.sign_out_server_desc, Beside.Icon),
            line(R.string.collections_two_desc, Beside.Check),
            line(R.string.collections_all_desc, Beside.Check),
            line(R.string.collections_off_desc, Beside.Check),
            line(R.string.ui_4k_desc, Beside.Check),
            line(R.string.ui_system_desc, Beside.Check),
            line(R.string.text_size_compact_desc, Beside.Check),
            line(R.string.text_size_standard_desc, Beside.Check),
            line(R.string.text_size_large_desc, Beside.Check),
            line(R.string.text_size_large_reached, Beside.Check),
            Line(local.getString(R.string.update_from, bytes(local, APK)), "$tag: update_from", Beside.None),
            line(R.string.update_later_desc),
            line(R.string.update_cancel_desc),
            line(R.string.update_install_desc),
            line(R.string.update_allow_desc),
            line(R.string.update_check_now_desc),
            line(R.string.update_retry_desc),
            line(R.string.update_check_daily_desc, Beside.Check),
            line(R.string.update_check_never_desc, Beside.Check),
            line(R.string.update_stable_desc, Beside.Check),
            line(R.string.update_beta_desc, Beside.Check),
        ) + orderLines(w) + listOf(
            // Titles not found: their answers, and the row of their folder's sheet.
            line(R.string.held_keep_desc),
            line(R.string.held_keep_desc_one),
            line(R.string.held_remove_desc),
            line(R.string.held_row_desc),
            line(R.string.held_row_desc_one),
        )
    }

    /**
     * A show's order of episodes: Modifier's row saying the one in use, by its icon, and the choices' lines beside their
     * check, with TMDB's longest usual name of a group and a long show's counts, in German and Polish too.
     */
    private fun orderLines(w: Words): List<Line> = with(w) {
        val fromNfo = local.getString(R.string.episode_order_auto_nfo, local.getString(R.string.episode_order_dvd))
        val episodes = local.resources.getQuantityString(R.plurals.numbers_episodes, EPISODES, "1 234")
        val seasons = local.resources.getQuantityString(R.plurals.season_count, MANY, MANY)
        listOf(
            Line(local.getString(R.string.episode_order_auto) + DOT + fromNfo, "$tag: episode_order (Modifier, NFO)", Beside.Icon),
            Line(local.getString(R.string.episode_order_dvd) + DOT + GROUP, "$tag: episode_order (Modifier, a group)", Beside.Icon),
            Line(fromNfo, "$tag: episode_order_auto_nfo", Beside.Check),
            Line(seasons + DOT + episodes, "$tag: episode_order_aired", Beside.Check),
            Line(GROUP + DOT + episodes, "$tag: an episode group", Beside.Check),
        )
    }

    @Composable
    private fun Row(line: Line) {
        HlSettingRow(
            "Row",
            description = line.text,
            value = line.value,
            valueStyle = if (line.jaquette) Hl.type.jaquette else Hl.type.meta,
            icon = HlIcons.Folder.takeIf { line.beside == Beside.Icon },
            onClick = {},
            trailing = if (line.beside == Beside.Check) ({ ChoiceCheck() }) else null,
        )
    }

    private companion object {
        const val LARGEST = 1.3f
        const val MANY = 12

        /** An episode group as TMDB names it, the longest of its usual names, and a long show's episodes. */
        const val GROUP = "Original Air Date"
        const val EPISODES = 1_234

        /** Between the parts of a source's line, and an account's name; a longer one ends in an ellipsis. */
        const val DOT = " · "
        const val USER = "léa"

        /** A scan's count, as long as a library's. */
        const val SCANNED = 1_234
        const val FILES = 5_678

        /** The image cache's size, as the row prints it. */
        const val CACHE = "151,5 Mo"

        /** How many titles are to identify, as many as a large library may leave. */
        const val TO_IDENTIFY = "1 234"

        /** An update as long as its version names get: a beta's, its APK and what of it was received. */
        const val BETA = "0.4.1-beta.2"
        const val APK = 6_444_633L
        const val READ = 2_100_000L
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 60 * MINUTE_MS
    }
}
