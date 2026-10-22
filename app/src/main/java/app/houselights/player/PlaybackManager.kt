package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import app.houselights.R
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.TrackChoice
import app.houselights.core.TrackPlan
import app.houselights.core.isWebUrl
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Launches the native Zidoo player and reports progress to the backend: polled from its local API while
 * it plays, then from the "position" extra it returns on close.
 */
class PlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val status: suspend () -> PlayerAnswer = ZidooStatus::playStatus,
    /** Monotonic clock (ms) that times the player's silence. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val players: ZidooPlayer = ZidooPlayer(context),
    /** The tracks of the file playing ([ZidooStatus.tracks]) and switching them ([ZidooStatus.select]). */
    private val tracks: suspend () -> PlayerTracks? = ZidooStatus::tracks,
    private val select: suspend (TrackKind, Int) -> Boolean = ZidooStatus::select,
    /** Keeps what the user changed in the player as the title's choice ([TrackPlan.titleKey]). */
    private val remember: suspend (String, TrackChoice) -> Unit = { _, _ -> },
    /** Recovers the reporter of a playback started before the app was stopped (see [PlaybackPlan.Launch.resumeKey]). */
    private val reporterFor: suspend (String) -> PlaybackReporter?,
) {
    private class Session(
        /** Reports the file playing; becomes the next part's reporter once the player moves on to it (see [follow]). */
        var reporter: PlaybackReporter?,
        var positionMs: Long,
        /** A disc: it may open on a menu or an intro, whose length is not the title's (see [follow]). */
        val disc: Boolean = false,
        /** Key of the playing file ([PlaybackPlan.Launch.resumeKey]), for the log. */
        var key: String? = null,
        /** The player a secret goes to, remembered only once it has started ([onPlayerLaunched]). */
        val trusting: ZidooPlayer.Choice? = null,
        /** The tracks asked of the player, checked once it plays ([check]); null when it chose them, or for a next part. */
        var tracks: TrackPlan? = null,
        /** The URI launched, which tells it from another file the player went on to. */
        val file: String? = null,
    ) {
        /** The length of the title, from the player's first status (see [follow]). */
        var durationMs: Long? = null
        var paused = false

        /** The player moved on to another file: its statuses no longer concern this title. */
        var switched = false

        /** The "started" report, which the "stopped" report waits for. */
        var started: Job? = null

        /** Statuses heard while it played, until the tracks are checked ([CHECK_AFTER]). */
        var heard = 0

        /** The check has begun; it is over once [settled]. */
        var checked = false
        var settled = false

        /** The player's lists, read by the check: the numbers it returns are its own. */
        var plays: PlayerTracks? = null

        /** Tracks the player played without the user (asked, its own, switched to), by kind: a return to one is no choice. */
        val usual = mutableMapOf<TrackKind, MutableSet<Int>>()

        fun usual(kind: TrackKind, index: Int?) {
            if (index != null) usual.getOrPut(kind) { mutableSetOf() } += index
        }
    }

    private var session: Session? = null
    private var pollJob: Job? = null

    /** The session, persisted before launch: on a 2 GB player, Android may kill the app during the film. */
    private val saved = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _playing = MutableStateFlow(false)

    /** True while the player shows a film, which then has the NAS and the network to itself. */
    val playing: StateFlow<Boolean> = _playing

    private val _playbackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits when playback ends: screens reload resume points and watched state. */
    val playbackEnded: SharedFlow<Unit> = _playbackEnded

    /**
     * Builds the intent for the Zidoo player, or any video player elsewhere. A URI's secret (an SMB
     * password, or Jellyfin's token, which opens the whole account) goes only to the player [ZidooPlayer]
     * trusts, and only on a Zidoo: a password goes to no other app ([CredentialsNotShared]); a token goes
     * elsewhere only through a chooser, even for a single app ([AUTO_LAUNCH_SINGLE_CHOICE]), and never once
     * the trusted player has changed. A player found under another name gets a stream only if it handles
     * its address ([ZidooPlayer.opens]).
     *
     * Not guarded against: a non-system player installed under a Zidoo package name before the first
     * password (trusted on first use), a system app update signed with the firmware's AOSP test keys, and
     * anyone reading the device log over adb, where Android logs the URI it starts.
     */
    fun intentFor(plan: PlaybackPlan.Launch, tracks: TrackPlan? = null): Intent {
        Log.i(TAG, "Launch ${plan.title} -> ${Redact.uri(plan.uri)}")

        val choice = players.choose()
        val player = choice.player?.takeIf { players.opens(it, plan.uri) }
        val secret = Redact.hasCredentials(plan.uri)
        // A warning, so release builds keep where a secret went, or why not.
        if (secret) {
            val unanswered = if (player == null && choice.player != null) UNANSWERED_STREAM else ""
            Log.w(TAG, "Launch ${Redact.uri(plan.uri)} -> ${players.describe(choice)}$unanswered")
        }
        val changed = choice.decision == ZidooPlayer.Decision.CHANGED
        val intent = when {
            player != null && (!secret || choice.decision.trusted) -> playerIntent(player.component, plan, tracks)
            Redact.hasUserInfo(plan.uri) || secret && changed -> throw CredentialsNotShared(playerChanged = changed)
            else -> anyPlayerIntent(plan, chooser = secret)
        }
        val trusting = choice.takeIf { secret && intent.component != null }
        // Tracks are numbered as the Zidoo player numbers them: asked of it only.
        val asked = tracks?.takeIf { intent.component != null }
        session = Session(plan.reporter, plan.startMs, plan.disc, plan.resumeKey, trusting, asked, plan.uri).apply {
            usual(TrackKind.AUDIO, asked?.pick?.audio)
            usual(TrackKind.SUBTITLE, asked?.pick?.subtitle)
        }
        // Pending apply() writes complete before our activity stops, so the resume point survives a killed process.
        saved.edit().putString(KEY_RESUME, plan.resumeKey).putLong(KEY_POSITION, plan.startMs).apply()
        return intent
    }

    private fun playerIntent(player: ComponentName, plan: PlaybackPlan.Launch, tracks: TrackPlan?) = Intent(Intent.ACTION_VIEW).apply {
        component = player
        setDataAndType(Uri.parse(plan.uri), "video/${plan.container ?: "*"}")
        putExtra("title", plan.title)
        // The player is another app: it can fetch a web address but not one of our files.
        plan.backdropUrl?.takeIf(::isWebUrl)?.let { putExtra("loading_image", it) }
        plan.logoUrl?.takeIf(::isWebUrl)?.let { putExtra("clearlogo_image", it) }
        if (plan.startMs > 0) {
            putExtra("from_start", false)
            putExtra("position", plan.startMs.toInt())
        } else {
            putExtra("from_start", true)
        }
        tracks?.pick?.let { pick ->
            Log.i(TAG, "Tracks asked: audio ${pick.audio}, subtitles ${pick.subtitle}")
            pick.audio?.let { putExtra("audio_idx", it) }
            pick.subtitle?.let { putExtra("subtitle_idx", it) }
        }
        putExtra("return_result", true)
    }

    /** Any video player's intent, through a [chooser] for a URI with a token so that the user picks the app. */
    private fun anyPlayerIntent(plan: PlaybackPlan.Launch, chooser: Boolean): Intent {
        val any = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(plan.uri), "video/*")
            putExtra("title", plan.title)
            putExtra("position", plan.startMs.toInt())
            putExtra("return_result", true)
        }
        if (!chooser) return any
        return Intent.createChooser(any, context.getString(R.string.play_with)).putExtra(AUTO_LAUNCH_SINGLE_CHOICE, false)
    }

    /** The player could not be started: no password reached it, and it is not remembered. */
    fun onLaunchFailed() {
        session = null
        _playing.value = false
        saved.edit().clear().apply()
    }

    /** Call once the player activity has started; remembers a player given a secret ([ZidooPlayer.remember]). */
    fun onPlayerLaunched() {
        val s = session ?: return
        s.trusting?.let(players::remember)
        _playing.value = true
        s.started = scope.launch { report(s, "started") { s.reporter?.started(s.positionMs) } }
        pollJob?.cancel()
        pollJob = scope.launch { track(s) }
    }

    /**
     * Polls the player's local API every [POLL_MS] and reports its current position, never a stale one, every
     * few polls. Stops when the player moves to another file or says nothing plays [SILENT_POLLS]
     * times in a row; an unreachable API is then polled every [SLOW_POLL_MS] until the title can no longer
     * be playing ([silenceAllowedMs]), which also ends [playing] so a vanished player never holds the NAS.
     * The position is persisted ([keep]) only at the backend's pace: each write syncs the file to flash.
     */
    private suspend fun track(s: Session) {
        try {
            poll(s)
        } finally {
            // Skipped once the player's result has ended the session and cleared the saved state.
            if (currentCoroutineContext().isActive) keep(s)
        }
    }

    private suspend fun poll(s: Session) {
        var heardAt = clock()
        delay(FIRST_POLL_MS)
        var tick = 0
        var idle = 0
        var unanswered = 0
        while (currentCoroutineContext().isActive && !s.switched && idle < SILENT_POLLS) {
            val answer = status()
            when (answer) {
                is PlayerAnswer.Playing -> {
                    heardAt = clock()
                    idle = 0
                    unanswered = 0
                    follow(s, answer.state)
                    check(s)
                    if (!s.switched && tick % REPORT_EVERY == 0) {
                        keep(s)
                        report(s, "progress") { s.reporter?.progress(s.positionMs, s.durationMs, s.paused) }
                    }
                }
                // Before a first status, the player may still be opening the file.
                PlayerAnswer.NothingPlays -> if (s.durationMs != null) {
                    idle++
                    unanswered = 0
                }
                PlayerAnswer.NoAnswer -> if (s.durationMs != null) {
                    unanswered++
                    idle = 0
                }
            }
            if (s.silentTooLong(answer, heardAt)) {
                Log.i(TAG, "The player has said nothing of the title for longer than it could play: tracking stops")
                _playing.value = false
                return
            }
            tick++
            delay(if (unanswered >= SILENT_POLLS) SLOW_POLL_MS else POLL_MS)
        }
        if (idle >= SILENT_POLLS) {
            Log.i(TAG, "The player says nothing plays: tracking stops")
            _playing.value = false
        }
    }

    /** No status again, and the last one ([heardAt]) is older than the title could still play. */
    private fun Session.silentTooLong(answer: PlayerAnswer, heardAt: Long) =
        answer !is PlayerAnswer.Playing && clock() - heardAt > silenceAllowedMs()

    /**
     * Silence allowed before the title can no longer be playing: the time left at the last status plus
     * [SILENCE_MARGIN_MS]; [UNHEARD_MAX_MS] before a first status or while a disc shows only a menu or intro.
     */
    private fun Session.silenceAllowedMs(): Long {
        val title = durationMs?.takeUnless { disc && it < TITLE_MIN_MS } ?: return UNHEARD_MAX_MS
        return (title - positionMs).coerceAtLeast(0) + SILENCE_MARGIN_MS
    }

    /**
     * Applies a player status. Another length means another file (the next in its folder, at the end or on
     * "next"), followed if it is the title's next part ([nextPart]). Only a disc changes length within a
     * title, its film following a menu or intro, unless a title had played to its end and the menu returned.
     */
    private suspend fun follow(s: Session, state: ZidooPlayState) {
        val launched = s.durationMs
        if (launched != null && abs(state.durationMs - launched) > maxOf(SAME_FILE_MS, launched / SAME_FILE_SHARE)) {
            val titleEnded = launched >= TITLE_MIN_MS && s.positionMs >= launched - END_POLLS * POLL_MS
            if (!s.disc || titleEnded) {
                val next = s.nextPart(state, titleEnded)
                if (next == null) {
                    Log.i(TAG, "The player went on to another file ($launched -> ${state.durationMs} ms)")
                    s.switched = true
                    return
                }
                Log.i(TAG, "The player went on to the title's next part ($launched -> ${state.durationMs} ms)")
                s.reporter = next.reporter
                // Tracks were asked of the first part only: the next one plays the player's.
                s.tracks = null
                s.key = next.resumeKey
                // So a new process receiving the result reports this part; skipped once the result ended the session.
                if (session === s) saved.edit().putString(KEY_RESUME, next.resumeKey).apply()
            } else {
                Log.i(TAG, "The title is ${state.durationMs} ms long, past a menu or an intro of $launched ms")
            }
        }
        s.positionMs = state.positionMs
        s.durationMs = state.durationMs
        s.paused = state.paused
    }

    /**
     * The title's next part ([PlaybackReporter.next]) if the player moved on to it: matched by file name when
     * the player reports its path, else inferred from the previous file having [ended] (never for a disc).
     */
    private suspend fun Session.nextPart(state: ZidooPlayState, ended: Boolean): PlaybackReporter.Next? {
        val next = suspendRunCatching { reporter?.next() }.getOrNull() ?: return null
        val played = state.path
        return next.takeIf { if (played != null) names(played, next.path) else ended && !disc }
    }

    /** Whether the player's [path] names the file at [relativePath], escaped or not, whatever its mount. */
    private fun names(path: String, relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/')
        val played = path.substringAfterLast('/')
        return played.equals(name, ignoreCase = true) || Uri.decode(played).equals(name, ignoreCase = true)
    }

    /**
     * Once the film has played a few statuses ([CHECK_AFTER]), past the player's own choice of tracks, switches to
     * the tracks asked if it plays others ([TrackCheck]); only once, so that it never undoes the user's choice.
     */
    private suspend fun check(s: Session) {
        val plan = s.tracks ?: return
        if (s.switched || s.checked || ++s.heard < CHECK_AFTER) return
        s.checked = true
        val plays = tracks()
        s.plays = plays
        s.heardFrom(plays)
        val fixes = plays?.let { TrackCheck.fixes(plan, it) }.orEmpty()
        for (fix in fixes) {
            val done = select(fix.kind, fix.index)
            Log.i(TAG, "The player played other tracks: ${fix.kind} ${fix.index}${if (done) "" else ", refused"}")
            if (done) s.usual(fix.kind, fix.index)
        }
        if (fixes.isNotEmpty()) {
            // Read again once applied: another audio may have made the player choose its subtitles anew.
            delay(SETTLE_MS)
            s.heardFrom(tracks())
        }
        s.settled = true
    }

    private fun Session.heardFrom(plays: PlayerTracks?) {
        usual(TrackKind.AUDIO, plays?.audioIndex)
        usual(TrackKind.SUBTITLE, plays?.subtitleIndex)
    }

    /**
     * What the user changed in the player among the tracks asked of it, which becomes the title's choice. Nothing
     * when in doubt: another file, a check under way, or numbers that may not be the file's.
     */
    private fun changed(s: Session, data: Intent?): TrackChoice? {
        val plan = s.tracks?.takeUnless { s.doubtful(data) } ?: return null
        fun returned(kind: TrackKind, extra: String, asked: Int?) =
            data?.getIntExtra(extra, -1)?.takeIf { it >= 0 && asked != null && it !in s.usual[kind].orEmpty() }
        val audio = returned(TrackKind.AUDIO, "audio_idx", plan.pick.audio)
        val subtitle = returned(TrackKind.SUBTITLE, "subtitle_idx", plan.pick.subtitle)
        if (audio == null && subtitle == null) return null
        val choice = s.plays?.let { TrackCheck.choiceOf(it, audio, subtitle) }
            // Unchecked: the file's numbering is the player's, unless a server lists files beside it.
            ?: plan.choiceOf(audio, subtitle).takeUnless { plan.media.hasFilesBeside }
        return choice?.takeUnless { it.isEmpty }
    }

    /** What the player played is in doubt: another file, or a check under way. */
    private fun Session.doubtful(data: Intent?): Boolean {
        // Empty when nothing played; a name of its own when the player went on to the folder's next file.
        val played = data?.getStringExtra("url")?.ifBlank { null }
        return switched || checked && !settled || played != null && file != null && !sameName(played, file)
    }

    private fun sameName(path: String, uri: String): Boolean =
        Uri.decode(path.substringAfterLast('/')).equals(Uri.decode(uri.substringAfterLast('/')), ignoreCase = true)

    /** Persists the session's position for a new process if Android kills this one (see [saved]). */
    private fun keep(s: Session) {
        saved.edit().putLong(KEY_POSITION, s.positionMs).apply()
    }

    /** Player result: final position in ms when the player returns it. */
    fun onPlayerResult(data: Intent?) {
        val live = session
        val resumeKey = saved.getString(KEY_RESUME, null)
        val savedPosition = saved.getLong(KEY_POSITION, 0)
        session = null
        _playing.value = false
        saved.edit().clear().apply()
        if (live == null && resumeKey == null) return
        val poll = pollJob
        pollJob = null
        val returned = data?.getIntExtra("position", -1) ?: -1
        val key = live?.tracks?.titleKey
        val changed = live?.let { changed(it, data) }
        if (changed != null && key != null) {
            // At once, not after the reports, which a server may keep waiting.
            scope.launch {
                Log.i(TAG, "Tracks changed in the player, kept for the title: $changed")
                suspendRunCatching { remember(key, changed) }.onFailure { Log.w(TAG, "Could not keep the tracks chosen", it) }
            }
        }
        scope.launch {
            // The last progress report and the "started" report finish before the final one is sent.
            poll?.cancelAndJoin()
            live?.started?.join()
            // No live session: the app was stopped while the film played.
            val s = live ?: Session(resumeKey?.let { reporterFor(it) }, savedPosition, key = resumeKey)
            // A position the player returns for another file is not this title's.
            if (returned > 0 && !s.switched) s.positionMs = returned.toLong()
            Log.i(TAG, "Playback ended at ${s.positionMs} / ${s.durationMs} ms")
            report(s, "stopped") { s.reporter?.stopped(s.positionMs, s.durationMs) }
            _playbackEnded.emit(Unit)
        }
    }

    /** Reports [s] at [step] (started, progress, stopped); a failure is only logged. */
    private suspend fun report(s: Session, step: String, block: suspend () -> Unit) {
        suspendRunCatching { block() }.onFailure { Log.w(TAG, "Report that ${s.key} $step at ${s.positionMs} ms failed", it) }
    }

    /** The file needs credentials only the trusted Zidoo player may receive; [playerChanged] if it changed. */
    class CredentialsNotShared(val playerChanged: Boolean = false) : Exception("Credentials are only given to the Zidoo player")

    companion object {
        private const val TAG = "Playback"

        /** Appended to the launch warning when the player found does not handle a stream's address. */
        private const val UNANSWERED_STREAM = "; the player does not answer this stream's address: not handed it"

        /** Hidden EXTRA_AUTO_LAUNCH_SINGLE_CHOICE: false makes the chooser list even a single app (Android 9, 11). */
        private const val AUTO_LAUNCH_SINGLE_CHOICE = "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE"
        private const val KEY_RESUME = "resume_key"
        private const val KEY_POSITION = "position_ms"

        /** The player takes a few seconds to open the file. */
        private const val FIRST_POLL_MS = 4_000L

        /** Statuses heard before checking the tracks: the player sets its subtitles 3 s after opening the file. */
        private const val CHECK_AFTER = 3

        /** Time for the player to apply a track switched to before reading what it plays. */
        private const val SETTLE_MS = 1_500L
        private const val POLL_MS = 2_000L

        /** Progress is reported every this many polls, about ten seconds. */
        private const val REPORT_EVERY = 5

        /** Polls saying nothing plays before tracking stops, or unanswered before polling slows: about 30 s. */
        private const val SILENT_POLLS = 15

        /** Poll interval for an API that has not answered for a while. */
        private const val SLOW_POLL_MS = 10_000L

        /** Silence allowed beyond the title's remaining time (see [silenceAllowedMs]). */
        private const val SILENCE_MARGIN_MS = 5 * 60_000L

        /**
         * Silence allowed when the length was never reported: the longest cuts run near four hours, as long
         * as a scan waits for the player. Shorter would share the NAS with a scan; longer would stall it.
         */
        private const val UNHEARD_MAX_MS = 4 * 60 * 60_000L

        /** Within this many polls of its end, a disc title that changes length has played out to its menu. */
        private const val END_POLLS = 3

        /** Shorter than this, what a disc plays first is its menu or an intro, never its title. */
        private const val TITLE_MIN_MS = 5 * 60_000L

        /** Same-file length tolerance, or 1 % of the length: a transport stream's length is refined while reading. */
        private const val SAME_FILE_MS = 2_000L
        private const val SAME_FILE_SHARE = 100
    }
}
