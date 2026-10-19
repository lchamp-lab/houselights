package app.houselights.ui.settings

import android.icu.util.ULocale
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.settings.AppSettings
import app.houselights.settings.DisplayModes
import app.houselights.settings.InterfaceLanguage
import app.houselights.settings.OutputControl
import app.houselights.settings.outputModes
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.HlChoiceSetting
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.InfoBlock
import app.houselights.ui.design.components.SpecTable
import app.houselights.ui.design.components.SpecTableRow
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.appContainer
import app.houselights.ui.util.formatDimensions
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the screen receives: current output, render size, the display's modes; then the interface's language, and
 * its Ultra HD row where it applies. Where the player sets its own output (Zidoo), the pane says so and hides the
 * system's stand-in modes.
 */
internal fun LazyListScope.displayPane(s: AppSettings, rows: PaneRows) {
    item {
        val control = outputControl()
        Section(stringResource(R.string.section_display)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                when (control) {
                    OutputControl.Player -> PlayerOutput()
                    OutputControl.System, OutputControl.Ui4k -> SystemOutput()
                }
                RenderBlock()
                if (control != OutputControl.Player) ModesBlock()
                HlSettingGroup(null) {
                    // Keyed: changing the language makes the screen again, and focus comes back here.
                    InterfaceLanguageRow(rows.focus("interface_language"))
                    if (control == OutputControl.Ui4k) InterfaceResolution(s)
                }
            }
        }
    }
}

/** The Affichage value shown under its name: never a mode the app cannot request. */
@Composable
internal fun displaySummary(s: AppSettings): String = stringResource(
    when (outputControl()) {
        OutputControl.Ui4k -> if (s.ui4k) R.string.ui_4k else R.string.ui_system
        OutputControl.System -> R.string.ui_system
        OutputControl.Player -> R.string.display_by_player
    },
)

/** Who sets the output on this device, judged from the modes its display lists. */
@Composable
private fun outputControl(): OutputControl {
    val display = LocalView.current.display
    return remember(display) { DisplayModes.control(display?.outputModes().orEmpty()) }
}

/** "Ultra HD 60 Hz" or the system's mode: offered only where the system lists Ultra HD at 60 Hz. */
@Composable
private fun InterfaceResolution(s: AppSettings) {
    val vm = settingsViewModel()
    val failure = rowFailure(vm.errorOf(SettingAction.UI_RESOLUTION))
    HlChoiceSetting(
        stringResource(R.string.ui_resolution),
        options = listOf(
            ChoiceOption(stringResource(R.string.ui_4k), stringResource(R.string.ui_4k_desc)),
            ChoiceOption(stringResource(R.string.ui_system), stringResource(R.string.ui_system_desc)),
        ),
        selected = if (s.ui4k) 0 else 1,
        onSelect = { vm.setUi4k(it == 0) },
        description = stringResource(R.string.ui_resolution_desc),
        attention = failure?.value,
    )
    RowFailureNote(failure)
}

/** The app's language: the device's, or one of [InterfaceLanguage.LANGUAGES]; the screens are made again in it. */
@Composable
private fun InterfaceLanguageRow(modifier: Modifier) {
    val vm = settingsViewModel()
    val chosen by appContainer().language.chosen.collectAsStateWithLifecycle()
    val tags = remember { InterfaceLanguage.LANGUAGES.sortedWith(compareBy(Collator.getInstance(Locale.ROOT), ::interfaceLanguageName)) }
    var choosing by remember { mutableStateOf(false) }
    val failure = rowFailure(vm.errorOf(SettingAction.INTERFACE_LANGUAGE))
    val title = stringResource(R.string.interface_language)
    val description = stringResource(R.string.interface_language_desc)
    HlSettingRow(
        title,
        modifier,
        value = failure?.value ?: interfaceLanguageName(chosen ?: InterfaceLanguage.deviceLanguage()),
        description = description,
        valueAttention = failure != null,
        onClick = { choosing = true },
    )
    RowFailureNote(failure)
    if (choosing) {
        // Each language under its own name, then in the app's when that differs.
        val device = ChoiceOption(stringResource(R.string.device_language), interfaceLanguageName(InterfaceLanguage.deviceLanguage()))
        val options = listOf(device) +
            tags.map { tag ->
                val own = interfaceLanguageName(tag)
                ChoiceOption(own, interfaceLanguageName(tag, Locale.getDefault()).takeUnless { it.equals(own, ignoreCase = true) })
            }
        HlChoiceSheet(
            title,
            options,
            selected = chosen?.let { tags.indexOf(it) + 1 } ?: 0,
            onSelect = { i -> vm.setInterfaceLanguage(tags.getOrNull(i - 1)) },
            onDismiss = { choosing = false },
            description = description,
        )
    }
}

/** [tag]'s language named in [locale], by default its own ("Deutsch"), as one name where it has two scripts ("简体中文"). */
private fun interfaceLanguageName(tag: String, locale: Locale = Locale.forLanguageTag(tag)): String =
    ULocale.forLanguageTag(tag).getDisplayNameWithDialect(ULocale.forLocale(locale)).replaceFirstChar { it.titlecase(locale) }

/** Says the player sets its output in its own settings, and where; no mode is shown, as the system's are stand-ins. */
@Composable
private fun PlayerOutput() {
    InfoBlock(stringResource(R.string.current_output)) {
        Column {
            Text(stringResource(R.string.output_by_player), style = Hl.type.meta, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.xs))
            Hint(stringResource(R.string.output_by_player_hint))
        }
    }
}

/** The current mode: its definition and rate in the spec style, its frame size beside them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemOutput() {
    val mode = LocalView.current.display?.mode ?: return
    InfoBlock(stringResource(R.string.current_output)) {
        // Short of room, the frame size wraps below as a whole, so a number never breaks.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.fact)) {
            Text(
                "${definition(mode.physicalWidth, mode.physicalHeight)} ${hz(mode.refreshRate)}${NBSP}Hz",
                style = Hl.type.spec,
                color = Hl.colors.content,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                formatDimensions(mode.physicalWidth, mode.physicalHeight),
                style = Hl.type.meta,
                color = Hl.colors.contentSecondary,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** The render size in pixels, not the declared density: every screen is laid out on the same canvas. */
@Composable
private fun RenderBlock() {
    val view = LocalView.current
    // Read again once laid out: a screen made again with its activity is composed before its window has a size.
    fun rendered() = IntSize(view.rootView.width, view.rootView.height)
    var size by remember { mutableStateOf(rendered()) }
    InfoBlock(stringResource(R.string.label_render), Modifier.onGloballyPositioned { size = rendered() }) {
        Text(formatDimensions(size.width, size.height), style = Hl.type.meta, color = Hl.colors.content)
    }
}

/** The display's modes, one row per frame size with its rates (largest first, current one dotted), to stay short. */
@Composable
private fun ModesBlock() {
    val display: Display = LocalView.current.display ?: return
    val current = display.mode
    val rows = display.supportedModes
        .groupBy { it.physicalWidth to it.physicalHeight }
        .entries
        .sortedByDescending { (size, _) -> size.first * size.second }
        .map { (size, modes) ->
            SpecTableRow(
                name = definition(size.first, size.second),
                values = listOf(
                    formatDimensions(size.first, size.second),
                    modes.map { it.refreshRate }.distinctBy(::hz).sorted().joinToString(FACT_GAP, transform = ::hz) + "${NBSP}Hz",
                ),
                inUse = size.first == current.physicalWidth && size.second == current.physicalHeight,
            )
        }
    InfoBlock(stringResource(R.string.label_modes)) {
        SpecTable(rows, columns = listOf(HlLayout.specFormat))
    }
}

/** A frame size by the name on a Blu-ray case ("Ultra HD", "Full HD"), else by its lines ("576p"). */
private fun definition(width: Int, height: Int): String = Definition.of(width)?.label ?: "${height}p"

/** "60", or "59,94" for a fractional rate (in the device's locale). */
private fun hz(hz: Float): String =
    if (abs(hz - hz.roundToInt()) < WHOLE_HZ) "${hz.roundToInt()}" else "%.2f".format(hz)

/** Tolerance for a whole rate: displays report 60 Hz as 59.99999 or 60.00001. */
private const val WHOLE_HZ = 0.005f
