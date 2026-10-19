package app.houselights.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.houselights.providers.local.ScanState
import kotlinx.coroutines.flow.MutableStateFlow

/** Home's bands over [state], no scan running, every action doing nothing. */
@Composable
internal fun HomeBands(state: HomeState) =
    HomeBands(state, remember { MutableStateFlow(ScanState()) }, {}, { _, _ -> }, {}, {}, {}, {})
