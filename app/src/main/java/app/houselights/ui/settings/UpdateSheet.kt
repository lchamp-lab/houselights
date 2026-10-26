package app.houselights.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.houselights.BuildConfig
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.design.components.ChoiceOption
import app.houselights.ui.design.components.HlChoiceSetting
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.components.OpenSheets
import app.houselights.ui.design.components.ReadingPlate
import app.houselights.ui.design.components.StatusState
import app.houselights.ui.design.revealOnFocus
import app.houselights.ui.util.formatBytes
import app.houselights.ui.util.formatDuration
import app.houselights.update.UpdateControls
import app.houselights.update.UpdateFailure
import app.houselights.update.UpdateOffer
import app.houselights.update.UpdateState
import app.houselights.update.UpdateStep
import app.houselights.update.Updated
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

// The app's updates as the screens show them (DESIGN.md, the update sheet): About's group, the update's sheet wherever
// it opens, home's sheet once per version, and home's word once an update is installed.

/** The updater as the screens show it: its [state] and what their rows ask of it ([controls]). */
@Stable
internal class UpdateUi(val state: StateFlow<UpdateState>, val controls: UpdateControls)

/** Where the update's sheet opened: from About's row, or on home, by the app, once per version. */
internal enum class UpdateOrigin { About, Home }

/**
 * About's group « Mises à jour », between the device's state and the credits: the state row, whose title stays (the
 * installed version) and whose OK always opens the sheet, so focus never jumps (as `JournalRow`); then the automatic
 * check and the versions offered, each choice saved as the other settings are, a failure said on its row.
 */
@Composable
internal fun UpdatesGroup(ui: UpdateUi, rows: PaneRows) {
    val vm = settingsViewModel()
    val state by ui.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val checkFailure = rowFailure(vm.errorOf(SettingAction.UPDATE_CHECK))
    val channelFailure = rowFailure(vm.errorOf(SettingAction.UPDATE_CHANNEL))
    HlSettingGroup(stringResource(R.string.updates), Modifier.revealOnFocus()) {
        val (description, value) = rowWords(state, now())
        HlSettingRow(
            stringResource(R.string.update_row, state.installed),
            rows.focus(UPDATES_ROW),
            value = value,
            description = description,
            valueAttention = attention(state),
            onClick = { open = true },
        )
        HlChoiceSetting(
            stringResource(R.string.update_check),
            options = listOf(
                ChoiceOption(stringResource(R.string.update_check_daily), stringResource(R.string.update_check_daily_desc)),
                ChoiceOption(stringResource(R.string.update_check_never), stringResource(R.string.update_check_never_desc)),
            ),
            selected = if (state.automatic) 0 else 1,
            onSelect = { vm.setUpdateCheck(it == 0) },
            description = stringResource(R.string.update_check_desc),
            attention = checkFailure?.value,
        )
        RowFailureNote(checkFailure)
        HlChoiceSetting(
            stringResource(R.string.update_channel),
            options = listOf(
                ChoiceOption(stringResource(R.string.update_stable), stringResource(R.string.update_stable_desc)),
                ChoiceOption(stringResource(R.string.update_beta), stringResource(R.string.update_beta_desc)),
            ),
            selected = if (state.beta) 1 else 0,
            onSelect = { vm.setUpdatePrereleases(it == 1) },
            description = stringResource(R.string.update_channel_desc),
            attention = channelFailure?.value,
        )
        RowFailureNote(channelFailure)
    }
    if (open) UpdateSheet(state, ui.controls, UpdateOrigin.About, onDismiss = { open = false })
}

/** The state row's value in ember: a failure, or Android's permission to ask; GitHub's wait is only a wait. */
private fun attention(s: UpdateState): Boolean {
    val failed = s.failure != null && s.failure != UpdateFailure.BUSY
    return failed || s.step is UpdateStep.NeedsPermission
}

/**
 * The state row's description and value: when GitHub last answered and « À jour » under two days, what is under way,
 * or what failed; the version just installed for a day.
 */
@Composable
internal fun rowWords(s: UpdateState, now: Long): Pair<String?, String?> {
    val context = LocalContext.current
    val offer = s.offer
    val step = s.step
    val failure = s.failure
    if (failure != null) {
        val value = if (failure == UpdateFailure.BUSY) waitWords(s, now) else stringResource(R.string.update_failed)
        return stringResource(failure.title) to value
    }
    if (step == UpdateStep.Checking) return stringResource(R.string.checking) to null
    if (offer == null) return upToDate(s, now, context)
    return when (step) {
        is UpdateStep.Downloading -> stringResource(R.string.update_downloading_version, offer.versionName) to bytesOf(step)
        UpdateStep.Verifying -> stringResource(R.string.update_verifying_version, offer.versionName) to null
        UpdateStep.Ready -> stringResource(R.string.update_ready_version, offer.versionName) to null
        is UpdateStep.NeedsPermission -> stringResource(R.string.update_must_allow) to stringResource(R.string.update_required)
        is UpdateStep.Installing -> stringResource(R.string.update_installing_version, offer.versionName) to null
        else -> stringResource(R.string.update_available, offer.versionName) to sizeWords(offer)
    }
}

/** Nothing offered: just installed, checked (« À jour » while the answer is fresh), never, or the check off. */
@Composable
private fun upToDate(s: UpdateState, now: Long, context: Context): Pair<String?, String?> {
    val upToDate = stringResource(R.string.update_up_to_date)
    val installed = s.updated?.takeIf { it.installed && it.versionName == s.installed }
    if (installed != null) {
        return stringResource(R.string.update_installed_ago, relativeTime(context, installed.at, now, short = true)) to upToDate
    }
    val at = s.checkedAt ?: return stringResource(if (s.automatic) R.string.update_never_checked else R.string.update_check_off) to null
    val checked = stringResource(R.string.update_checked, relativeTime(context, at, now, short = true))
    return checked to upToDate.takeIf { now - at in 0 until FRESH_MS }
}

/** « 6,4 Mo », a beta's « Bêta · 6,4 Mo ». */
@Composable
private fun sizeWords(offer: UpdateOffer): String {
    val size = formatBytes(offer.size)
    return if (offer.prerelease) stringResource(R.string.update_beta_mark) + PARTS + size else size
}

/** « 2,1 Mo / 6,4 Mo »: each figure with its unit. */
@Composable
private fun bytesOf(step: UpdateStep.Downloading): String =
    stringResource(R.string.update_bytes_of, formatBytes(step.read), formatBytes(step.total))

/** « Dans 20 min »: GitHub's wait, a minute at least. */
@Composable
private fun waitWords(s: UpdateState, now: Long): String? = s.waitUntil?.takeIf { it > now }?.let {
    stringResource(R.string.update_wait, formatDuration(maxOf(it - now, MINUTE_MS)))
}

/** Now, by the device's clock, again each half minute while shown: « Vérifié il y a 2 h », « Dans 20 min ». */
@Composable
private fun now(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The update's sheet, one component wherever it opens: from About, « Mises à jour »; on home, opened by the app once per
 * version, « Une nouvelle version est disponible ». Its head names the version and how it stands, a failure or the
 * permission saying under it what to do; then what is new, on a plate that takes focus only when the sheet scrolls; the
 * action row, focused, whose words follow the step; under it « Annuler le téléchargement » while it downloads, reached
 * with Down, and on home « Plus tard », which Back is too. Back closes the sheet in every step and cancels nothing.
 */
@Composable
internal fun UpdateSheet(state: UpdateState, controls: UpdateControls, origin: UpdateOrigin, onDismiss: () -> Unit) {
    val action = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val now = now()
    DisposableEffect(controls) {
        controls.opened()
        onDispose { controls.closed() }
    }
    val offer = state.offer
    val step = state.step
    val stuck = (step as? UpdateStep.NeedsPermission)?.stuck == true
    val byHand = stuck || state.failure?.byHand == true
    val (stateText, light) = headState(state, origin, now)
    HlSideSheet(
        stringResource(if (origin == UpdateOrigin.Home && offer != null) R.string.update_popup_title else R.string.updates),
        onDismiss,
        facts = listOf(stringResource(R.string.update_version, offer?.versionName ?: state.installed)),
        badges = listOfNotNull(stringResource(R.string.update_beta_mark).takeIf { offer?.prerelease == true }),
        stateText = stateText,
        state = light,
        description = headWords(state, now),
        initialFocus = action,
        scroll = scroll,
    ) { sheet ->
        if (byHand) {
            ByHand(adb = stuck, scroll)
        } else if (offer != null) {
            WhatsNew(offer, scroll)
        }
        val doing = actionOf(state, now)
        HlSettingGroup(null) {
            ActionRow(state, doing, controls, Modifier.focusRequester(action))
            when {
                doing == UpdateAction.DOWNLOADING -> HlSettingRow(
                    stringResource(R.string.update_cancel),
                    description = stringResource(R.string.update_cancel_desc),
                    onClick = controls::cancel,
                )
                doing == UpdateAction.UPDATE && origin == UpdateOrigin.Home -> HlSettingRow(
                    stringResource(R.string.update_later),
                    description = stringResource(R.string.update_later_desc),
                    onClick = { sheet.dismiss() },
                )
            }
        }
        footWords(doing)?.let { Text(it, style = Hl.type.meta, color = Hl.colors.contentSecondary) }
    }
}

/** How the update stands, by its light, in the head: none while the row says it, nor on home's sheet while offered. */
@Composable
private fun headState(s: UpdateState, origin: UpdateOrigin, now: Long): Pair<String?, StatusState> {
    val step = s.step
    val failure = s.failure
    return when {
        failure != null -> stringResource(failure.title) to StatusState.Attention
        step is UpdateStep.NeedsPermission -> stringResource(R.string.update_permission) to StatusState.Attention
        step == UpdateStep.Checking -> stringResource(R.string.checking) to StatusState.Pending
        s.offer == null -> {
            val fresh = s.checkedAt?.let { now - it in 0 until FRESH_MS } == true
            stringResource(R.string.update_up_to_date).takeIf { fresh } to StatusState.Done
        }
        step == UpdateStep.Ready -> stringResource(R.string.update_ready) to StatusState.On
        step != UpdateStep.Idle -> null to StatusState.Pending
        origin == UpdateOrigin.Home -> null to StatusState.On
        else -> stringResource(R.string.update_offered) to StatusState.On
    }
}

/** What to do, under the head: the failure's remedy in words, or how to allow the install. */
@Composable
private fun headWords(s: UpdateState, now: Long): String? {
    val failure = s.failure
    val step = s.step
    return when {
        failure == UpdateFailure.BUSY ->
            stringResource(failure.message, formatDuration(maxOf((s.waitUntil ?: now) - now, MINUTE_MS)))
        failure == UpdateFailure.STORAGE ->
            stringResource(failure.message, formatBytes(s.offer?.spaceNeeded ?: 0L))
        failure != null -> stringResource(failure.message)
        step is UpdateStep.NeedsPermission -> stringResource(if (step.stuck) R.string.update_no_screen else R.string.update_permission_how)
        else -> null
    }
}

/** What Android will do, under the action row: ask to confirm, then close the app (and on Android 9 open it again). */
@Composable
private fun footWords(doing: UpdateAction): String? = when (doing) {
    UpdateAction.INSTALL, UpdateAction.REOPEN -> stringResource(R.string.update_kept)
    UpdateAction.INSTALLING -> stringResource(
        // From Android 10, an app no longer opens itself from the background once replaced.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) R.string.update_confirm_reopen else R.string.update_confirm_reopens,
    )
    else -> null
}

/**
 * The action row, its words and what OK does following [doing]; OK does nothing while it only says what is under way
 * (GitHub's wait is said under the head), and for a moment after its words change ([calmAfterChange]). Downloading,
 * its count follows the line.
 */
@Composable
private fun ActionRow(state: UpdateState, doing: UpdateAction, controls: UpdateControls, modifier: Modifier) {
    val downloading = (state.step as? UpdateStep.Downloading)?.takeIf { doing == UpdateAction.DOWNLOADING }
    HlSettingRow(
        stringResource(doing.title),
        modifier.calmAfterChange(doing),
        description = actionWords(doing, state),
        progress = downloading?.let { it.read.toFloat() / it.total },
        onClick = { act(doing, controls) },
    )
}

/** The action row's name for [this] action. */
@get:StringRes
private val UpdateAction.title: Int
    get() = when (this) {
        UpdateAction.CHECK, UpdateAction.CHECKING -> R.string.update_check_now
        UpdateAction.RETRY, UpdateAction.WAIT -> R.string.retry
        UpdateAction.ALLOW -> R.string.update_allow
        UpdateAction.UPDATE -> R.string.update_now
        UpdateAction.DOWNLOADING -> R.string.update_downloading
        UpdateAction.VERIFYING -> R.string.update_verifying
        UpdateAction.INSTALL, UpdateAction.REOPEN -> R.string.update_install
        UpdateAction.INSTALLING -> R.string.update_installing
    }

/** The action row's description: where it asks, what it downloads and how far, or the file it hands over. */
@Composable
private fun actionWords(doing: UpdateAction, state: UpdateState): String? = when (doing) {
    UpdateAction.CHECK, UpdateAction.CHECKING -> stringResource(R.string.update_check_now_desc)
    UpdateAction.RETRY, UpdateAction.WAIT -> stringResource(R.string.update_retry_desc)
    UpdateAction.ALLOW -> stringResource(R.string.update_allow_desc)
    UpdateAction.UPDATE -> state.offer?.let { stringResource(R.string.update_from, formatBytes(it.size)) }
    UpdateAction.DOWNLOADING -> (state.step as? UpdateStep.Downloading)?.let { bytesOf(it) }
    UpdateAction.INSTALL, UpdateAction.REOPEN -> stringResource(R.string.update_install_desc)
    UpdateAction.VERIFYING, UpdateAction.INSTALLING -> null
}

/** What OK on the action row asks; nothing where it only says what is under way. */
private fun act(doing: UpdateAction, controls: UpdateControls) {
    when (doing) {
        UpdateAction.CHECK -> controls.checkNow()
        UpdateAction.RETRY -> controls.retry()
        UpdateAction.ALLOW -> controls.allow()
        UpdateAction.UPDATE, UpdateAction.INSTALL -> controls.update()
        UpdateAction.REOPEN -> controls.reopen()
        UpdateAction.CHECKING, UpdateAction.WAIT, UpdateAction.DOWNLOADING, UpdateAction.VERIFYING, UpdateAction.INSTALLING -> Unit
    }
}

/**
 * OK does nothing for [HlMotion.SETTLE] after [doing] changed under it, nor the key's repeats or release: a second press
 * given too soon, or a key held, never does twice what the first asked, nor undoes it.
 */
@Composable
private fun Modifier.calmAfterChange(doing: UpdateAction): Modifier {
    var calm by remember { mutableStateOf(false) }
    var swallowed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(doing) }
    LaunchedEffect(doing) {
        if (doing == shown) return@LaunchedEffect
        shown = doing
        calm = true
        delay(HlMotion.SETTLE)
        calm = false
    }
    return onPreviewKeyEvent { event ->
        if (event.key !in OK_KEYS) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyDown && (calm || swallowed) -> {
                swallowed = true
                true
            }
            event.type == KeyEventType.KeyUp && swallowed -> {
                swallowed = false
                true
            }
            else -> false
        }
    }
}

/** What is new in the version offered, a note a paragraph, in the app's language else English; read through when it scrolls. */
@Composable
private fun WhatsNew(offer: UpdateOffer, scroll: ScrollState) {
    val language = LocalContext.current.resources.configuration.locales[0].language
    val notes = remember(offer, language) { offer.notesIn(language) }
    if (notes.isEmpty()) return
    val c = Hl.colors
    ReadingPlate(Modifier.fillMaxWidth().focusProperties { canFocus = scroll.maxValue > 0 }.readsThrough(scroll)) {
        Text(stringResource(R.string.update_notes), style = Hl.type.group, color = c.contentSecondary)
        Spacer(Modifier.height(HlSpace.m))
        Column(verticalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            notes.forEach { Text(it, style = Hl.type.body, color = c.contentSecondary) }
        }
    }
}

/**
 * The ways nothing in the app opens, as text, read through when the sheet scrolls: the release page's address, as a
 * phone shows it; then, where Android cannot be asked here ([adb]), the line a computer gives it.
 */
@Composable
private fun ByHand(adb: Boolean, scroll: ScrollState) {
    val c = Hl.colors
    ReadingPlate(Modifier.fillMaxWidth().focusProperties { canFocus = scroll.maxValue > 0 }.readsThrough(scroll)) {
        Text(stringResource(R.string.update_by_hand), style = Hl.type.meta, color = c.contentSecondary)
        Spacer(Modifier.height(HlSpace.xs))
        Text(RELEASES, style = Hl.type.jaquette, color = c.content)
        if (adb) {
            Spacer(Modifier.height(HlSpace.l))
            Text(stringResource(R.string.update_by_adb), style = Hl.type.meta, color = c.contentSecondary)
            Spacer(Modifier.height(HlSpace.xs))
            Text(stringResource(R.string.update_adb_line), style = Hl.type.code, color = c.content)
        }
    }
}

/**
 * Home's sheet: the version offered, opened by the app once home is drawn and in front ([shown]), the launch screen gone,
 * no other sheet open, and once per version (« Plus tard » and Back wait for a newer one); never with the check off.
 */
@Composable
internal fun UpdateOnHome(ui: UpdateUi?, shown: Boolean) {
    if (ui == null) return
    val state by ui.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val launch = LocalLaunchScreen.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val offer = state.offer?.takeIf { state.offeredOnHome }
    val ready = shown && !open && offer != null && OpenSheets.count == 0
    LaunchedEffect(ready, offer?.versionCode) {
        if (!ready || offer == null) return@LaunchedEffect
        // In front, not under the launch screen nor behind the player, and settled.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        while (launch.covers) delay(LAUNCH_POLL_MS)
        delay(HlMotion.SETTLE)
        open = true
        ui.controls.shownOnHome(offer.versionCode)
    }
    if (open) UpdateSheet(state, ui.controls, UpdateOrigin.Home, onDismiss = { open = false })
}

/**
 * Home's word once the app is back from an update, at its head for 2.5 s, once: « Houselights 0.4.1 installé », its white
 * dot, or « La version 0.4.1 n'a pas été installée », its attention mark; null otherwise.
 */
@Composable
internal fun updateSaid(ui: UpdateUi?): Pair<String, StatusState>? {
    if (ui == null) return null
    val state by ui.state.collectAsStateWithLifecycle()
    val updated = state.updated?.takeUnless { it.said || !state.on }
    var saying by remember { mutableStateOf<Updated?>(null) }
    val launch = LocalLaunchScreen.current
    val controls by rememberUpdatedState(ui.controls)
    LaunchedEffect(updated) {
        if (updated == null) return@LaunchedEffect
        while (launch.covers) delay(LAUNCH_POLL_MS)
        saying = updated
        delay(SAID_MS)
        saying = null
        controls.updatedSaid()
    }
    val shown = saying ?: return null
    return if (shown.installed) {
        stringResource(R.string.update_installed_home, shown.versionName) to StatusState.Done
    } else {
        stringResource(R.string.update_not_installed, shown.versionName) to StatusState.Attention
    }
}

private const val UPDATES_ROW = "updates"

/** Between a value's parts (« Bêta · 6,4 Mo »). */
private const val PARTS = " · "

/** The release page's address, without its scheme, as a phone shows it and a person types it. */
private const val RELEASES = "github.com/${BuildConfig.UPDATE_REPO}/releases"

/** « À jour » only while GitHub answered less than two days ago; then the row says only when it last did. */
private const val FRESH_MS = 48 * 60 * 60_000L
private const val MINUTE_MS = 60_000L
private const val TICK_MS = 30_000L

/** How long home's word stays, as Back's once does. */
private const val SAID_MS = 2_500L

/** How often home looks whether the launch screen has lifted, before it opens a sheet or says a word. */
private const val LAUNCH_POLL_MS = 100L

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
