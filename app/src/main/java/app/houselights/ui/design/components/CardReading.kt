package app.houselights.ui.design.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * [cardReading], the same modifier from one composition to the next while what it reads is: the card's surface then
 * skips composing again as focus comes into its row or leaves it.
 */
@Composable
internal fun rememberCardReading(
    readAs: String?,
    stateDescription: String?,
    focused: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
): Modifier = remember(readAs, stateDescription, focused, onClick, onLongClick, longClickLabel) {
    cardReading(readAs, stateDescription, focused, onClick, onLongClick, longClickLabel)
}

/** Clearing semantics for [readAs] drops the clicks, so focus is restated and [onClick] and [onLongClick] re-added. */
@Suppress("LongParameterList") // What is restated, and its clicks.
private fun cardReading(
    readAs: String?,
    stateDescription: String?,
    focused: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    longClickLabel: String?,
): Modifier =
    if (readAs != null) {
        Modifier.clearAndSetSemantics {
            contentDescription = readAs
            // What it is, after its name: a saga's panel, « vu », « à suivre ».
            stateDescription?.let { this.stateDescription = it }
            this.focused = focused
            onClick?.let { act ->
                onClick {
                    act()
                    true
                }
            }
            onLongClick?.let { act ->
                onLongClick(label = longClickLabel) {
                    act()
                    true
                }
            }
        }
    } else {
        Modifier.semantics(mergeDescendants = true) { stateDescription?.let { this.stateDescription = it } }
    }
