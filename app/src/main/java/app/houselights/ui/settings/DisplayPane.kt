package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import android.view.Display
import app.houselights.R
import app.houselights.settings.AppSettings
import app.houselights.settings.DisplayModes
import app.houselights.settings.OutputControl
import app.houselights.settings.outputModes
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDimensions
import kotlin.math.abs
import kotlin.math.roundToInt
import app.houselights.ui.util.NBSP

/**
 * What the screen receives, as far as the app can know it. Where the player sets its own output
 * (the Zidoo), the pane says so and where to change it, and shows no mode the system makes up.
 */
internal fun LazyListScope.displayPane(s: AppSettings) {
    item {
        val control = outputControl()
        Section(stringResource(R.string.section_display)) {
            when (control) {
                OutputControl.Player -> PlayerOutput()
                OutputControl.System -> CurrentOutput()
                OutputControl.Ui4k -> Column(verticalArrangement = Arrangement.spacedBy(HlSpace.l)) {
                    InterfaceResolution(s)
                    CurrentOutput()
                }
            }
        }
    }
}

/** What Affichage is set to, under its name: never a mode the app cannot ask for. */
@Composable
internal fun displaySummary(s: AppSettings): String = stringResource(
    when (outputControl()) {
        OutputControl.Ui4k -> if (s.ui4k) R.string.ui_4k else R.string.ui_system
        OutputControl.System -> R.string.ui_system
        OutputControl.Player -> R.string.display_by_player
    },
)

/** Who sets the output on this device, from the modes its display lists. */
@Composable
private fun outputControl(): OutputControl {
    val display = LocalView.current.display
    return remember(display) { DisplayModes.control(display?.outputModes().orEmpty()) }
}

/** "4K 60 Hz" or the system's mode: offered only where the system lists 4K at 60 Hz. */
@Composable
private fun InterfaceResolution(s: AppSettings) {
    val vm = settingsViewModel()
    var choose by remember { mutableStateOf(false) }
    HlSettingGroup(null) {
        HlSettingRow(
            stringResource(R.string.ui_resolution),
            value = stringResource(if (s.ui4k) R.string.ui_4k else R.string.ui_system),
            description = stringResource(R.string.ui_resolution_desc),
            onClick = { choose = true },
        )
    }
    if (choose) {
        HlChoiceSheet(
            title = stringResource(R.string.ui_resolution),
            options = listOf(
                ChoiceOption(stringResource(R.string.ui_4k), stringResource(R.string.ui_4k_desc)),
                ChoiceOption(stringResource(R.string.ui_system), stringResource(R.string.ui_system_desc)),
            ),
            selected = if (s.ui4k) 0 else 1,
            onSelect = { vm.setUi4k(it == 0) },
            onDismiss = { choose = false },
            description = stringResource(R.string.ui_resolution_desc),
        )
    }
}

/**
 * The player sets its output in its own settings and scales the interface to it: who sets it,
 * where, and the size the interface is drawn at. No mode and no rate: those listed are stand-ins.
 */
@Composable
private fun PlayerOutput() {
    HlSettingGroup(null) {
        HlSettingRow(stringResource(R.string.label_hdmi), value = stringResource(R.string.output_by_player))
        RenderRow()
        Hint(stringResource(R.string.output_by_player_hint), Modifier.padding(horizontal = HlSpace.l))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentOutput() {
    val view = LocalView.current
    val display: Display = view.display ?: return
    val mode = display.mode
    val modes = display.supportedModes
        .sortedWith(compareByDescending<Display.Mode> { it.physicalWidth }.thenByDescending { it.refreshRate })
        .map { "${it.physicalHeight}p${hz(it.refreshRate)}" }
        .distinct()
    HlSettingGroup(stringResource(R.string.current_output)) {
        val output = "${mode.physicalHeight}p$FACT_GAP${hz(mode.refreshRate)}${NBSP}Hz"
        HlSettingRow(stringResource(R.string.label_hdmi), value = output)
        RenderRow()
        Column(Modifier.padding(horizontal = HlSpace.l, vertical = HlSpace.s)) {
            Text(stringResource(R.string.label_modes), style = Hl.type.entry, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                modes.forEach { Badge(it) }
            }
        }
    }
}

/**
 * The size the interface is drawn at, in pixels. Not the density the device declares: every
 * screen is laid out on the same canvas, whatever it says.
 */
@Composable
private fun RenderRow() {
    val view = LocalView.current
    HlSettingRow(stringResource(R.string.label_render), value = formatDimensions(view.rootView.width, view.rootView.height))
}

/** "60", or "59,94" for a fractional rate (written in the device's language). */
private fun hz(hz: Float): String =
    if (abs(hz - hz.roundToInt()) < WHOLE_HZ) "${hz.roundToInt()}" else "%.2f".format(hz)

/** Displays report 60 Hz as 59.99999 or 60.00001. */
private const val WHOLE_HZ = 0.005f
