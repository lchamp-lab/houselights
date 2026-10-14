package app.houselights.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.player.PlaybackManager
import app.houselights.player.ZidooStatus
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.DefinitionRow
import app.houselights.ui.design.components.FactRow
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.InfoBlock
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
        Wordmark(style = Hl.type.wordmarkHero)
        Spacer(Modifier.height(HlSpace.xs))
        FactRow(
            listOf(
                stringResource(R.string.nav_version, BuildConfig.VERSION_NAME),
                stringResource(R.string.about_free_software, LICENSE),
            ),
        )
    }
}

/** What the app works with on this device, read like the back of a case: each with its light. */
@Composable
private fun AboutStatus() {
    val context = LocalContext.current
    var api by remember { mutableStateOf<Boolean?>(null) }
    val player = remember {
        runCatching { context.packageManager.getPackageInfo(PlaybackManager.ZIDOO_PACKAGE, 0) }.isSuccess
    }
    LaunchedEffect(Unit) { api = ZidooStatus.isAvailable() }
    InfoBlock(stringResource(R.string.about_status)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            DefinitionRow(stringResource(R.string.label_player)) {
                HlStatus(
                    if (player) StatusState.On else StatusState.Off,
                    stringResource(if (player) R.string.detected else R.string.not_detected),
                )
            }
            DefinitionRow(stringResource(R.string.label_api)) {
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
            }
        }
    }
}

/** Whose work the app stands on, as the credits at the foot of a poster read: who did what. */
@Composable
private fun AboutCredits() {
    InfoBlock(stringResource(R.string.about_credits)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            // The logo has no baseline: it sits level with the top of its label.
            DefinitionRow(stringResource(R.string.credit_data)) {
                Image(
                    painterResource(R.drawable.tmdb_logo),
                    contentDescription = "TMDB",
                    modifier = Modifier.height(HlSize.inlineIcon),
                )
            }
            // What TMDB asks to be said, under its logo, in the column of values.
            Hint(stringResource(R.string.tmdb_attribution), Modifier.padding(start = HlLayout.definitionLabel))
            DefinitionRow(stringResource(R.string.credit_font)) {
                Text(stringResource(R.string.credit_font_value), style = Hl.type.meta, color = Hl.colors.content)
            }
        }
    }
}
private const val LICENSE = "GPL-3.0"
/** Debug builds only, not translated. */
private const val CATALOG = "Design system"
