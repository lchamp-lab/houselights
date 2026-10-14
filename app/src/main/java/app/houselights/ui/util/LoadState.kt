package app.houselights.ui.util

/** Something a screen loads: still coming, there, or why it isn't. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Ready<T>(val value: T) : LoadState<T>

    data class Failed(val error: UiError) : LoadState<Nothing>
}
