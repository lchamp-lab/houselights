package app.houselights.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.player.PlaybackManager
import app.houselights.player.ZidooStatus
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.Wordmark

internal fun LazyListScope.aboutPane(onOpenCatalog: () -> Unit, rows: PaneRows) {
    item { AboutBrand() }
    item { AboutStatus() }
    item { AboutCredits() }
    // For whoever works on the app: the design system on this screen.
    if (BuildConfig.DEBUG) item { HlSettingGroup(null) { HlSettingRow(CATALOG, rows.focus("catalog"), onClick = onOpenCatalog) } }
}

/** The house light and its name, with what this copy is. */
@Composable
private fun AboutBrand() {
    Column {
        Wordmark(style = Hl.type.wordmark)
        Spacer(Modifier.height(HlSpace.xs))
        FactRow(
            listOf(
                stringResource(R.string.nav_version, BuildConfig.VERSION_NAME),
                stringResource(R.string.about_free_software, LICENSE),
            ),
        )
    }
}

@Composable
private fun AboutStatus() {
    val context = LocalContext.current
    var api by remember { mutableStateOf<Boolean?>(null) }
    val player = remember {
        runCatching { context.packageManager.getPackageInfo(PlaybackManager.ZIDOO_PACKAGE, 0) }.isSuccess
    }
    LaunchedEffect(Unit) { api = ZidooStatus.isAvailable() }
    HlSettingGroup(stringResource(R.string.about_status)) {
        HlSettingRow(
            stringResource(R.string.label_player),
            trailing = {
                HlStatus(
                    if (player) StatusState.On else StatusState.Off,
                    stringResource(if (player) R.string.detected else R.string.not_detected),
                )
            },
        )
        HlSettingRow(
            stringResource(R.string.label_api),
            trailing = {
                HlStatus(
                    when (api) {
                        null -> StatusState.Pending
                        true -> StatusState.On
                        false -> StatusState.Off
                    },
                    stringResource(
                        when (api) {
                            null -> R.string.testing
                            true -> R.string.online
                            false -> R.string.offline
                        }
                    ),
                )
            },
        )
    }
}

@Composable
private fun AboutCredits() {
    HlSettingGroup(stringResource(R.string.about_credits)) {
        HlSettingRow(
            stringResource(R.string.credit_data),
            trailing = {
                Image(
                    painterResource(R.drawable.tmdb_logo),
                    contentDescription = "TMDB",
                    modifier = Modifier.height(HlSize.inlineIcon),
                )
            },
        )
        Hint(stringResource(R.string.tmdb_attribution), Modifier.padding(horizontal = HlSpace.l))
        HlSettingRow(stringResource(R.string.credit_font), value = stringResource(R.string.credit_font_value))
    }
}
private const val LICENSE = "GPL-3.0"
/** Debug builds only, not translated. */
private const val CATALOG = "Design system"
