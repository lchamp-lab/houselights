package app.houselights.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.player.DeviceIdentity
import app.houselights.player.PlayerStatus
import app.houselights.player.ZidooPlayer
import app.houselights.player.ZidooPlayer.Decision
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
import app.houselights.ui.util.FACT_GAP

internal fun LazyListScope.aboutPane(onOpenCatalog: () -> Unit, rows: PaneRows) {
    item { AboutBrand() }
    item { AboutStatus() }
    item { AboutCredits() }
    // For whoever works on the app: the design system on this screen.
    if (BuildConfig.DEBUG) item { HlSettingGroup(null) { HlSettingRow(CATALOG, rows.focus("catalog"), onClick = onOpenCatalog) } }
}

/**
 * The house light and its name, with what this copy is. The wordmark stands where every pane has its
 * title, level with the first navigation entry: moving between panes, the title does not jump.
 */
@Composable
private fun AboutBrand() {
    Column {
        Spacer(Modifier.height(HlLayout.paneTop))
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

/** What the app works with on this device, read like the back of a case: each with its light. */
@Composable
private fun AboutStatus() {
    val context = LocalContext.current.applicationContext
    val vm = viewModel {
        AboutViewModel(
            // What playback decides, from the same code: what an evening on a new player starts from.
            playerStatus = { ZidooPlayer(context).status() },
            apiAnswers = ZidooStatus::isAvailable,
        )
    }
    val device = remember { DeviceIdentity.current().name }
    // Asked while the block shows, as it comes into view.
    DisposableEffect(vm) {
        vm.check()
        onDispose { vm.forget() }
    }
    val api = vm.api
    InfoBlock(stringResource(R.string.about_status)) {
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
            DeviceRows(device, vm.player)
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

/**
 * The device, by its maker and model, and its player as playback decides ([PlayerStatus]): the app it
 * hands films to, and whether a password may go to it, or why not.
 */
@Composable
internal fun DeviceRows(device: String, player: PlayerStatus) {
    DefinitionRow(stringResource(R.string.label_device)) {
        Text(device, style = Hl.type.meta, color = Hl.colors.content)
    }
    DefinitionRow(stringResource(R.string.label_player)) {
        when (player) {
            is PlayerStatus.Detected -> HlStatus(StatusState.Done, stringResource(R.string.detected), detail = player.packageName)
            is PlayerStatus.Unrecognized -> HlStatus(
                // Off a Zidoo, nothing is wrong: no player there gets a password.
                if (player.reason == Decision.NOT_A_ZIDOO) StatusState.Off else StatusState.Attention,
                stringResource(R.string.player_unrecognised),
                detail = stringResource(player.reason.words()) + FACT_GAP + player.packageName,
            )
            PlayerStatus.NotDetected -> HlStatus(StatusState.Off, stringResource(R.string.not_detected))
        }
    }
}

/** Why a player found gets no password, in a few words. */
@StringRes
private fun Decision.words(): Int = when (this) {
    Decision.NOT_A_ZIDOO -> R.string.player_not_a_zidoo
    Decision.NO_CERTIFICATE -> R.string.player_no_certificate
    // The one refusal left: a player trusted, or none, is said otherwise.
    else -> R.string.player_changed_since
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
