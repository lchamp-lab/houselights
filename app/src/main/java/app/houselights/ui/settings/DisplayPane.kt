package app.houselights.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.Badge
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlChoiceSheet
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.util.FACT_GAP
import app.houselights.ui.util.formatDimensions
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun LazyListScope.displayPane(s: AppSettings) {
    item {
        val vm = settingsViewModel()
        var choose by remember { mutableStateOf(false) }
        Section(stringResource(R.string.section_display)) {
            HlSettingGroup(null) {
                HlSettingRow(
                    stringResource(R.string.ui_resolution),
                    value = stringResource(if (s.ui4k) R.string.ui_4k else R.string.ui_system),
                    description = stringResource(R.string.ui_resolution_desc),
                    onClick = { choose = true },
                )
            }
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
            )
        }
    }
    item { CurrentOutput() }
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
        val output = "${mode.physicalHeight}p$FACT_GAP${hz(mode.refreshRate)}${Typography.nbsp}Hz"
        HlSettingRow(stringResource(R.string.label_hdmi), value = output)
        HlSettingRow(
            stringResource(R.string.label_render),
            trailing = {
                FactRow(
                    listOf(
                        formatDimensions(view.rootView.width, view.rootView.height),
                        "${view.resources.displayMetrics.densityDpi}${Typography.nbsp}dpi",
                    ),
                )
            },
        )
        Column(Modifier.padding(horizontal = HlSpace.l, vertical = HlSpace.s)) {
            Text(stringResource(R.string.label_modes), style = Hl.type.label, color = Hl.colors.content)
            Spacer(Modifier.height(HlSpace.s))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.xs), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                modes.forEach { Badge(it) }
            }
        }
    }
}

/** "60", or "59,94" for a fractional rate (written in the device's language). */
private fun hz(hz: Float): String =
    if (abs(hz - hz.roundToInt()) < WHOLE_HZ) "${hz.roundToInt()}" else "%.2f".format(hz)

/** Displays report 60 Hz as 59.99999 or 60.00001. */
private const val WHOLE_HZ = 0.005f
