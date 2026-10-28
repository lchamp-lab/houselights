package app.houselights.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.player.PlayerFacts
import app.houselights.player.ZidooPlayer
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.components.HlSettingGroup
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlSideSheet
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.detail.longDate
import app.houselights.ui.util.ErrorAction
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

// Trusting the new player (#58): on a test-keys firmware, the Zidoo player updated gets passwords again once trusted.

/** What the trust sheet asks of the player playback hands films to. */
interface PlayerTrust {
    /** The player playback refuses as changed, as the sheet names it; null when none is. */
    fun changed(): PlayerFacts?

    /** Pins [seen] if it is still the player playback refuses as changed; false, nothing pinned, when it changed again. */
    fun trust(seen: PlayerFacts): Boolean
}

/** This device's player, as playback decides ([ZidooPlayer.choose]). */
class ZidooTrust(context: Context) : PlayerTrust {
    private val player = ZidooPlayer(context.applicationContext)

    override fun changed(): PlayerFacts? = player.facts(player.choose())

    override fun trust(seen: PlayerFacts): Boolean = player.trust(seen)
}

/**
 * The trust sheet's state: closed, or open on the player [facts] names, the one playback refuses as changed ([ask]).
 * Nothing that opens it trusts anything: only its answer « Faire confiance à ce lecteur » does ([trust]).
 */
@Stable
class TrustAsked(private val player: PlayerTrust) {
    var facts by mutableStateOf<PlayerFacts?>(null)
        private set

    /** Opens the sheet on the player playback refuses as changed; nothing opens when none is. */
    fun ask() {
        facts = player.changed()
    }

    fun close() {
        facts = null
    }

    /**
     * Trusts the player the sheet names, if it is still the one playback refuses; else nothing is pinned and the sheet
     * names what playback finds now, closing when no player is refused any more. Whether it trusted.
     */
    fun trust(): Boolean {
        val seen = facts ?: return false
        if (player.trust(seen)) return true
        facts = player.changed()
        return false
    }

    /** [outer], but « Faire confiance au nouveau lecteur » ([ErrorAction.TRUST_PLAYER]), which opens the sheet ([ask]). */
    fun fixing(outer: (ErrorAction) -> Unit): (ErrorAction) -> Unit = { action ->
        if (action == ErrorAction.TRUST_PLAYER) ask() else outer(action)
    }
}

/**
 * The trust sheet (#58) while [asked] is open, a question of two answers as `HeldSheet`'s: the player by its name and
 * version, as its update declares them, « Autre signature » for one signed otherwise than the player passwords went to;
 * then what trusting it means, the day Android installed it in the sentence, where it never drops, and what it would
 * receive, Jellyfin's access only while Jellyfin is the source ([jellyfin]), the one case its token goes to the player;
 * then the two answers on one plate, « Ne pas lui faire confiance » first and focused, as nothing changes with it,
 * « Faire confiance à ce lecteur » a Down then an OK away, calm as focus arrives on it ([calmOnArrival]), the sheet
 * leaving once it trusted. Never ember: nothing is erased, and the trust ends by itself with the player's next update.
 * A player changed again meanwhile is named anew, the safe answer focused again. Back closes it unanswered. What opened
 * it learns it closed, trusted or not, by [TrustAsked.facts] going back to null.
 */
@Composable
internal fun PlayerTrustSheet(asked: TrustAsked, jellyfin: Boolean) {
    val facts = asked.facts ?: return
    val safe = remember { FocusRequester() }
    var named by remember { mutableStateOf(facts) }
    LaunchedEffect(facts) {
        if (facts != named) {
            named = facts
            safe.tryRequestFocus()
        }
    }
    val other = stringResource(R.string.trust_player_other_signature).takeIf { facts.otherSignature }
    HlSideSheet(
        stringResource(R.string.trust_player),
        onDismiss = asked::close,
        facts = listOf(facts.label, facts.version, other),
        initialFocus = safe,
    ) { sheet ->
        val sentence = stringResource(trustSentence(facts, jellyfin), dateOf(facts.updatedAt))
        Text(sentence, style = Hl.type.body, color = Hl.colors.contentSecondary)
        HlSettingGroup(null) {
            HlSettingRow(
                stringResource(R.string.trust_player_no),
                Modifier.focusRequester(safe),
                description = stringResource(R.string.trust_player_no_desc),
                onClick = { sheet.dismiss() },
            )
            HlSettingRow(
                stringResource(R.string.trust_player_yes),
                Modifier.calmOnArrival(),
                description = stringResource(R.string.trust_player_yes_desc),
                onClick = { if (asked.trust()) sheet.dismiss() },
            )
        }
    }
}

/**
 * What trusting the player means: for the firmware's player updated, that nothing tells this version from Zidoo's and
 * the trust lasts until its next update; for an app in its place, that it is not the player passwords went to. Each
 * names Jellyfin's access only while Jellyfin is the source ([jellyfin]).
 */
@StringRes
private fun trustSentence(facts: PlayerFacts, jellyfin: Boolean): Int = when {
    facts.system && jellyfin -> R.string.trust_player_sheet_desc_jellyfin
    facts.system -> R.string.trust_player_sheet_desc
    jellyfin -> R.string.trust_player_sheet_desc_app_jellyfin
    else -> R.string.trust_player_sheet_desc_app
}

/** [at] (ms since the epoch) as the day it was on this device's clock, in full in the viewer's language. */
@Composable
@ReadOnlyComposable
internal fun dateOf(at: Long): String =
    longDate(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate(), LocalConfiguration.current.locales[0])

/**
 * As `UpdateSheet`'s action row: OK does nothing for [HlMotion.SETTLE] once focus arrives here, nor on a key let go here
 * that went down elsewhere, so that a Down and an OK given in one go, or the OK that opened the sheet given again too
 * soon, never trust.
 */
@Composable
private fun Modifier.calmOnArrival(): Modifier {
    val calm = remember { ArrivalCalm() }
    var arrivals by remember { mutableIntStateOf(0) }
    LaunchedEffect(arrivals) {
        if (arrivals == 0) return@LaunchedEffect
        delay(HlMotion.SETTLE)
        calm.on = false
    }
    return onFocusChanged { state ->
        if (calm.focus(state.isFocused)) arrivals++
    }.onPreviewKeyEvent { event ->
        when {
            event.key !in OK_KEYS -> false
            event.type == KeyEventType.KeyDown -> calm.pressed(event.nativeKeyEvent.repeatCount)
            event.type == KeyEventType.KeyUp -> calm.released()
            else -> false
        }
    }
}

/**
 * Whether focus is on the answer ([focused]), and since too short a while ([on]); [swallowed], a press it took, its
 * release with it; [down], OK went down here. Read by key events, never drawn.
 */
private class ArrivalCalm {
    var on = false
    private var focused = false
    private var swallowed = false
    private var down = false

    /** Focus come to the answer or gone ([now]); true when it just arrived, calm from then. */
    fun focus(now: Boolean): Boolean {
        if (now == focused) return false
        focused = now
        down = false
        if (now) on = true
        return now
    }

    /** OK going down here, its [repeat] count: taken, doing nothing, while calm and through a press it took. */
    fun pressed(repeat: Int): Boolean {
        if (on || swallowed) {
            swallowed = true
            return true
        }
        if (repeat == 0) down = true
        return false
    }

    /** OK let go here: taken, doing nothing, when its press was taken or went down elsewhere. */
    fun released(): Boolean = (swallowed || !down).also {
        swallowed = false
        down = false
    }
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
