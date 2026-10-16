package app.houselights.ui.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * A setting chosen among a few [options]: its row says the [selected] one, and OK opens them in an
 * [HlChoiceSheet] headed by the row's [title] and [description]; choosing applies it and closes the
 * sheet, Back changes nothing. A change that did not take says so in place of the choice
 * ([attention]: "Non enregistré"), in ember after its mark.
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
