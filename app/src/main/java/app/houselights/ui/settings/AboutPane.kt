package app.houselights.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.DefinitionPlate
import app.houselights.ui.design.components.FactPlate
import app.houselights.ui.design.components.HlGroup
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.JaquetteLine
import app.houselights.ui.design.components.PaneLockup
import app.houselights.ui.design.components.PlateDefinition
import app.houselights.ui.design.components.PlateFact
import app.houselights.ui.design.components.QrCode
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.FACT_GAP

internal fun LazyListScope.aboutPane(onOpenCatalog: () -> Unit, rows: PaneRows) {
    item { AboutBrand() }
    item { AboutStatus() }
    item { AboutCredits() }
    item {
        HlSettingGroup(null, Modifier.revealOnFocus()) {
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

/** The lockup where every pane has its title, so nothing jumps between panes; its version and licence printed under it. */
@Composable
private fun AboutBrand() {
    Column {
        PaneLockup()
        Spacer(Modifier.height(HlSpace.l))
        JaquetteLine(
            listOf(
                stringResource(R.string.label_version) to BuildConfig.VERSION_NAME,
                stringResource(R.string.label_licence) to stringResource(R.string.licence_free, LICENSE),
            ),
        )
    }
}

/** What the app relies on on this device, each with its state, checked each time it comes into view. */
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
    DisposableEffect(vm) {
        vm.check()
        onDispose { vm.forget() }
    }
    HlGroup(stringResource(R.string.about_status)) { DeviceFacts(device, vm.player, vm.api) }
}

/**
 * The device's model, its player as playback decides ([PlayerStatus]) and whether its control API answers ([api],
 * null while asked); why a player found is not recognised is said under them.
 */
@Composable
internal fun DeviceFacts(device: String, player: PlayerStatus, api: Boolean?) {
    val why = (player as? PlayerStatus.Unrecognized)?.let { stringResource(it.reason.words()) + FACT_GAP + it.packageName }
    FactPlate(note = why) {
        PlateFact(stringResource(R.string.label_device), device, Modifier.weight(1f, fill = false))
        val (words, state) = when (player) {
            is PlayerStatus.Detected -> R.string.detected to StatusState.Done
            // Not a fault on other devices: no player there gets a password.
            is PlayerStatus.Unrecognized ->
                R.string.player_unrecognised to if (player.reason == Decision.NOT_A_ZIDOO) StatusState.Off else StatusState.Attention
            PlayerStatus.NotDetected -> R.string.not_detected to StatusState.Off
        }
        PlateFact(stringResource(R.string.label_player), stringResource(words), state = state)
        PlateFact(
            stringResource(R.string.label_api),
            stringResource(
                when (api) {
                    null -> R.string.testing
                    true -> R.string.online
                    false -> R.string.offline
                }
            ),
            state = when (api) {
                null -> StatusState.Pending
                true -> StatusState.On
                false -> StatusState.Off
            },
        )
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

/** Credits for the work the app builds on: who did what, TMDB by its logo and the attribution it asks for. */
@Composable
private fun AboutCredits() {
    HlGroup(stringResource(R.string.about_credits)) {
        DefinitionPlate {
            // The logo has no baseline: it sits level with the top of its label.
            PlateDefinition(stringResource(R.string.credit_data), note = stringResource(R.string.tmdb_attribution)) {
                Image(painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB", modifier = Modifier.height(HlSize.inlineIcon))
            }
            PlateDefinition(stringResource(R.string.credit_font)) {
                Text(stringResource(R.string.credit_font_value), style = Hl.type.lead, color = Hl.colors.content)
            }
            PlateDefinition(stringResource(R.string.credit_icons)) {
                Text(stringResource(R.string.credit_icons_value), style = Hl.type.lead, color = Hl.colors.content)
            }
        }
    }
}

private const val LICENSE = "GPL-3.0"
/** The address `support_qr` encodes, without its scheme, as a phone shows it and a person types it. */
private const val SUPPORT_ADDRESS = "ko-fi.com/lchamp_lab"
/** Debug builds only, not translated. */
private const val CATALOG = "Design system"
