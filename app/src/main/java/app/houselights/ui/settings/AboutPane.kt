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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.HlStatus
import app.houselights.ui.design.components.InfoBlock
import app.houselights.ui.design.components.QrCode
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.components.Wordmark
import app.houselights.ui.util.FACT_GAP

internal fun LazyListScope.aboutPane(onOpenCatalog: () -> Unit, rows: PaneRows) {
    item { AboutBrand() }
    item { AboutStatus() }
    item { AboutCredits() }
    item {
        HlSettingGroup(null) {
            SupportRow()
            // Debug builds only: the design system catalogue, last.
            if (BuildConfig.DEBUG) HlSettingRow(CATALOG, rows.focus("catalog"), onClick = onOpenCatalog)
        }
    }
}

/** Support for the project, offered here only, never as a reminder: OK opens a sheet with the Ko-fi QR code. */
@Composable
internal fun SupportRow(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    HlSettingRow(
        stringResource(R.string.support),
        modifier,
        description = stringResource(R.string.support_desc),
        onClick = { open = true },
    )
    if (open) {
        // Nothing in it is focusable: Back is the only key it handles.
        HlSideSheet(stringResource(R.string.support), onDismiss = { open = false }, description = stringResource(R.string.support_scan)) {
            QrCode(R.drawable.support_qr, SUPPORT_ADDRESS)
        }
    }
}

/** The wordmark, version and licence, placed where every pane has its title so nothing jumps between panes. */
@Composable
private fun AboutBrand() {
    Column {
        Spacer(Modifier.height(HlLayout.paneTop))
        Wordmark(HlSize.wordmark)
        Spacer(Modifier.height(HlSpace.xs))
        FactRow(
            listOf(
                stringResource(R.string.nav_version, BuildConfig.VERSION_NAME),
                stringResource(R.string.about_free_software, LICENSE),
            ),
        )
    }
}

/** What the app relies on on this device, each with its status light. */
@Composable
private fun AboutStatus() {
    val context = LocalContext.current.applicationContext
    val vm = viewModel {
        AboutViewModel(
            // The same decision playback makes, so About shows what playback will do on this player.
            playerStatus = { ZidooPlayer(context).status() },
            apiAnswers = ZidooStatus::isAvailable,
        )
    }
    val device = remember { DeviceIdentity.current().name }
    // Checked each time the block comes into view.
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

/** The device's model, and its player as playback decides ([PlayerStatus]): may it get a password, or why not. */
@Composable
internal fun DeviceRows(device: String, player: PlayerStatus) {
    DefinitionRow(stringResource(R.string.label_device)) {
        Text(device, style = Hl.type.meta, color = Hl.colors.content)
    }
    DefinitionRow(stringResource(R.string.label_player)) {
        when (player) {
            is PlayerStatus.Detected -> HlStatus(StatusState.Done, stringResource(R.string.detected), detail = player.packageName)
            is PlayerStatus.Unrecognized -> HlStatus(
                // Not a fault on other devices: no player there gets a password.
                if (player.reason == Decision.NOT_A_ZIDOO) StatusState.Off else StatusState.Attention,
                stringResource(R.string.player_unrecognised),
                detail = stringResource(player.reason.words()) + FACT_GAP + player.packageName,
            )
            PlayerStatus.NotDetected -> HlStatus(StatusState.Off, stringResource(R.string.not_detected))
        }
    }
}

/** Why a detected player gets no password, in a few words. */
@StringRes
private fun Decision.words(): Int = when (this) {
    Decision.NOT_A_ZIDOO -> R.string.player_not_a_zidoo
    Decision.NO_CERTIFICATE -> R.string.player_no_certificate
    // The remaining refusal: a trusted or missing player is reported elsewhere.
    else -> R.string.player_changed_since
}

/** Credits for the work the app builds on: who did what. */
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
            // TMDB's required attribution, under its logo, in the values column.
            Hint(stringResource(R.string.tmdb_attribution), Modifier.padding(start = HlLayout.definitionLabel))
            DefinitionRow(stringResource(R.string.credit_font)) {
                Text(stringResource(R.string.credit_font_value), style = Hl.type.meta, color = Hl.colors.content)
            }
            DefinitionRow(stringResource(R.string.credit_icons)) {
                Text(stringResource(R.string.credit_icons_value), style = Hl.type.meta, color = Hl.colors.content)
            }
        }
    }
}
private const val LICENSE = "GPL-3.0"
/** The address `support_qr` encodes, without its scheme, as a phone shows it and a person types it. */
private const val SUPPORT_ADDRESS = "ko-fi.com/lchamp_lab"
/** Debug builds only, not translated. */
private const val CATALOG = "Design system"
