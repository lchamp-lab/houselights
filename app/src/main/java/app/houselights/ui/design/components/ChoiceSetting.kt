package app.houselights.ui.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A setting with a few [options]: OK opens an [HlChoiceSheet]; choosing applies and closes it, Back changes
 * nothing. A failed save shows [attention] ("Non enregistré") in ember in place of the value.
 */
@Composable
fun HlChoiceSetting(
    title: String,
    options: List<ChoiceOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    attention: String? = null,
) {
    var choosing by remember { mutableStateOf(false) }
    HlSettingRow(
        title,
        modifier,
        value = attention ?: options[selected].label,
        description = description,
        valueAttention = attention != null,
        onClick = { choosing = true },
    )
    if (choosing) {
        HlChoiceSheet(title, options, selected, onSelect, onDismiss = { choosing = false }, description = description)
    }
}
