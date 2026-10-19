package app.houselights.ui.testing

import androidx.lifecycle.SavedStateHandle

/** What the platform hands a new process of this saved state once Android stopped the app: its values alone. */
fun SavedStateHandle.afterProcessDeath() = SavedStateHandle(keys().associateWith { get<Any?>(it) })
