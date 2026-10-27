package app.houselights.ui.settings

import android.icu.util.ULocale
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.houselights.R
import app.houselights.core.Definition
import app.houselights.settings.AppSettings
import app.houselights.settings.DisplayModes
import app.houselights.settings.InterfaceLanguage
import app.houselights.settings.OutputControl
import app.houselights.settings.TextSize
import app.houselights.settings.outputModes
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.FactPlate
import app.houselights.ui.design.components.HlChoiceSetting
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlGroup
import app.houselights.ui.design.components.HlPlate
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.SpecTable
import app.houselights.ui.design.components.SpecTableRow
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.textScaleOf
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.NBSP
import app.houselights.ui.util.formatDimensions
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the screen receives, stated as a case's back: the current output and the render size; then the display's
 * modes, and the interface's language and text size with its Ultra HD row where it applies. Where the player sets its
 * own output (Zidoo), the pane says so and hides the system's stand-in modes.
 */
internal fun LazyListScope.displayPane(s: AppSettings, rows: PaneRows) {
    item {
        val control = outputControl()
        val sentence = if (control == OutputControl.Player) R.string.display_desc_player else R.string.display_desc_system
        Section(stringResource(R.string.section_display), sentence = stringResource(sentence)) {
            Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                OutputFacts(control)
                if (control != OutputControl.Player) ModesGroup()
                HlSettingGroup(stringResource(R.string.display_interface)) {
                    // Keyed: changing the language makes the screen again, and focus comes back here.
                    InterfaceLanguageRow(rows.focus("interface_language"))
                    TextSizeRow(s.textSize)
                    if (control == OutputControl.Ui4k) InterfaceResolution(s)
                }
            }
        }
    }
}

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
private fun InterfaceLanguageRow(modifier: Modifier = Modifier) {
    val vm = settingsViewModel()
    val chosen by vm.interfaceLanguage.collectAsStateWithLifecycle()
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

/**
 * « Taille du texte »: [current] of three steps, applied once the sheet has gone, so the screen behind it is drawn again
 * at its new size. With Android at its largest, Grande says it adds nothing.
 */
@Composable
private fun TextSizeRow(current: TextSize) {
    val vm = settingsViewModel()
    val failure = rowFailure(vm.errorOf(SettingAction.TEXT_SIZE))
    val system = LocalConfiguration.current.fontScale
    val grandeReached = textScaleOf(system, TextSize.LARGE.step) <= textScaleOf(system)
    val options = TextSize.entries.map { size ->
        when (size) {
            TextSize.COMPACT -> ChoiceOption(stringResource(R.string.text_size_compact), stringResource(R.string.text_size_compact_desc))
            TextSize.STANDARD -> ChoiceOption(stringResource(R.string.text_size_standard), stringResource(R.string.text_size_standard_desc))
            TextSize.LARGE -> ChoiceOption(
                stringResource(R.string.text_size_large),
                stringResource(if (grandeReached) R.string.text_size_large_reached else R.string.text_size_large_desc),
            )
        }
    }
    var choosing by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<TextSize?>(null) }
    val title = stringResource(R.string.text_size)
    HlSettingRow(
        title,
        value = failure?.value ?: options[current.ordinal].label,
        description = stringResource(R.string.text_size_desc),
        valueAttention = failure != null,
        onClick = { choosing = true },
    )
    RowFailureNote(failure)
    if (choosing) {
        HlChoiceSheet(
            title,
            options,
            selected = current.ordinal,
            onSelect = { chosen = TextSize.entries[it] },
            onDismiss = {
                choosing = false
                chosen?.takeIf { it != current }?.let(vm::setTextSize)
                chosen = null
            },
            description = stringResource(R.string.text_size_sheet),
        )
    }
}

/** [tag]'s language named in [locale], by default its own ("Deutsch"), as one name where it has two scripts ("简体中文"). */
private fun interfaceLanguageName(tag: String, locale: Locale = Locale.forLanguageTag(tag)): String =
    ULocale.forLanguageTag(tag).getDisplayNameWithDialect(ULocale.forLocale(locale)).replaceFirstChar { it.titlecase(locale) }

/**
 * The output and the render size: set by the player, with where to change it; else the system's mode, its
 * definition and rate in `spec`, its frame size beside them. The render size is in pixels, not the declared
 * density: every screen is laid out on the same canvas.
 */
@Composable
private fun OutputFacts(control: OutputControl) {
    val view = LocalView.current

    // Read again once laid out: a screen made again with its activity is composed before its window has a size.
    fun rendered() = IntSize(view.rootView.width, view.rootView.height)
    var size by remember { mutableStateOf(rendered()) }
    val mode = view.display?.mode
    val player = control == OutputControl.Player
    FactPlate(
        Modifier.onGloballyPositioned { size = rendered() },
        note = if (player) stringResource(R.string.output_by_player_hint) else null,
    ) {
        when {
            player -> PlateFact(
                stringResource(R.string.current_output),
                stringResource(R.string.output_by_player),
                state = StatusState.Done,
            )
            mode != null -> PlateFact(
                stringResource(R.string.current_output),
                "${definition(mode.physicalWidth, mode.physicalHeight)} ${hz(mode.refreshRate)}${NBSP}Hz",
                technical = true,
                measure = formatDimensions(mode.physicalWidth, mode.physicalHeight),
            )
        }
        PlateFact(
            stringResource(R.string.label_render),
            definition(size.width, size.height),
            technical = true,
            measure = formatDimensions(size.width, size.height),
        )
    }
}

/** The display's modes, one row per frame size with its rates (largest first, current one dotted), to stay short. */
@Composable
private fun ModesGroup() {
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
    HlGroup(stringResource(R.string.label_modes)) {
        HlPlate(Modifier.fillMaxWidth()) { SpecTable(rows, columns = listOf(HlLayout.specFormat)) }
    }
}

/** A frame size by the name on a Blu-ray case ("Ultra HD", "Full HD"), else by its lines ("576p"). */
private fun definition(width: Int, height: Int): String = Definition.of(width)?.label ?: "${height}p"

/** "60", or "59,94" for a fractional rate (in the device's locale). */
private fun hz(hz: Float): String =
    if (abs(hz - hz.roundToInt()) < WHOLE_HZ) "${hz.roundToInt()}" else "%.2f".format(hz)

/** Tolerance for a whole rate: displays report 60 Hz as 59.99999 or 60.00001. */
private const val WHOLE_HZ = 0.005f
