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
 * Launches the native Zidoo player and feeds playback progress back to the backend.
 *
 * Position is tracked twice: by polling the Zidoo local API while playing, then from the
 * "position" extra the player returns when it closes.
 */
class PlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** What the player says it plays now: the Zidoo local API. */
    private val status: suspend () -> PlayerAnswer = ZidooStatus::playStatus,
    /** Milliseconds that only go forward: how long the player has said nothing of its title. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Finds the player a film is handed to. */
    private val players: ZidooPlayer = ZidooPlayer(context),
    /** The reporter of a playback the app started before it was stopped (see [PlaybackPlan.Launch.resumeKey]). */
    private val reporterFor: suspend (String) -> PlaybackReporter?,
) {
    private class Session(
        /** What reports the file playing: a film's next part's, once the player went on to it (see [follow]). */
        var reporter: PlaybackReporter?,
        var positionMs: Long,
        /** A disc: it may open on a menu or an intro, whose length is not the title's (see [follow]). */
        val disc: Boolean = false,
        /** What names the file playing, as [reporter]'s source knows it ([PlaybackPlan.Launch.resumeKey]): for the log. */
        var key: String? = null,
        /** The player a secret goes to: remembered once it has started ([onPlayerLaunched]), not if it could not be. */
        val trusting: ZidooPlayer.Choice? = null,
    ) {
        /** The length of the title, from the player's first status (see [follow]). */
        var durationMs: Long? = null
        var paused = false

        /** The player went on to another file (the next one of its folder): what it says is no longer this title's. */
        var switched = false

        /** The report that playback started: the one that it stopped waits for it. */
        var started: Job? = null
    }

    private var session: Session? = null
    private var pollJob: Job? = null

    /**
     * The session, written down before the player starts: on a 2 GB player, Android may stop the app
     * while the film plays. The result then reaches a new process, which finds what was playing here.
     */
    private val saved = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private val _playing = MutableStateFlow(false)

    /** True while the player shows a film: the NAS and the network are its own. */
    val playing: StateFlow<Boolean> = _playing

    private val _playbackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits when playback ends: screens reload resume points and watched state. */
    val playbackEnded: SharedFlow<Unit> = _playbackEnded

    /**
     * Builds the intent for the Zidoo player, or for any video player elsewhere.
     *
     * What the app keeps from other apps is a URI's secret: an SMB folder's password, or Jellyfin's
     * token, which opens the whole account. It goes only to the player [ZidooPlayer] trusts, and only
     * on a Zidoo: a system app, which no ordinary app can replace; an app signed like a system app of
     * Zidoo's; else the player a secret last went to, or the first one, as long as neither its package
     * nor its certificate changes. Off a Zidoo, no app is trusted, whatever its name: any app may take one.
     * A password goes to no other app ([CredentialsNotShared]); a token goes elsewhere only through a
     * chooser, where the user picks the app, even the only one ([AUTO_LAUNCH_SINGLE_CHOICE]), and not
     * once the player trusted before has changed: the app put in its place would be in the chooser. A
     * URI with no secret goes to the player found, trusted or not, with what it is told of the film.
     *
     * Not guarded against: an app installed on a Zidoo whose player is no system app, under a name of
     * Zidoo's or the old player's, before a first password goes to the player (trusted on first use,
     * it would be the one; beside a system player, it comes after it); an update of a system app
     * signed with the AOSP test keys of the firmware's build; and whoever reads the device's log
     * through adb, where Android writes the address it starts.
     */
    fun intentFor(plan: PlaybackPlan.Launch): Intent {
        Log.i(TAG, "Launch ${plan.title} -> ${Redact.uri(plan.uri)}")

        val choice = players.choose()
        val player = choice.player
        val secret = Redact.hasCredentials(plan.uri)
        // A warning, kept in every build: where a secret goes, or why not, is what an evening on a new player needs.
        if (secret) Log.w(TAG, "Launch ${Redact.uri(plan.uri)} -> ${players.describe(choice)}")
        val changed = choice.decision == ZidooPlayer.Decision.CHANGED
        val intent = when {
            player != null && (!secret || choice.decision.trusted) -> playerIntent(player.component, plan)
            Redact.hasUserInfo(plan.uri) || secret && changed -> throw CredentialsNotShared(playerChanged = changed)
            else -> anyPlayerIntent(plan, chooser = secret)
        }
        val trusting = choice.takeIf { secret && intent.component != null }
        session = Session(plan.reporter, plan.startMs, plan.disc, plan.resumeKey, trusting)
        // Written before the player takes over: the platform finishes pending apply() writes before our
        // activity stops, which launching the player does, so the resume point survives a killed process.
        saved.edit().putString(KEY_RESUME, plan.resumeKey).putLong(KEY_POSITION, plan.startMs).apply()
        return intent
    }

    /** The player's intent: the file, and what the player shows while it opens it and where it starts. */
    private fun playerIntent(player: ComponentName, plan: PlaybackPlan.Launch) = Intent(Intent.ACTION_VIEW).apply {
        component = player
        setDataAndType(Uri.parse(plan.uri), "video/${plan.container ?: "*"}")
        putExtra("title", plan.title)
        // The player is another app: a file of ours is out of its reach, a web address is not.
        plan.backdropUrl?.takeIf(::isWebUrl)?.let { putExtra("loading_image", it) }
        plan.logoUrl?.takeIf(::isWebUrl)?.let { putExtra("clearlogo_image", it) }
        if (plan.startMs > 0) {
            putExtra("from_start", false)
            putExtra("position", plan.startMs.toInt())
        } else {
            putExtra("from_start", true)
        }
        putExtra("return_result", true)
    }

    /** Any video player's intent; through a [chooser] for a URI with a token, so that the user picks the app. */
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

    /** The player could not be started after all: no password went to it, and it is not remembered. */
    fun onLaunchFailed() {
        session = null
        _playing.value = false
        saved.edit().clear().apply()
    }

    /** Call right after the player activity was started: a player a secret went to is remembered ([ZidooPlayer.remember]). */
    fun onPlayerLaunched() {
        val s = session ?: return
        s.trusting?.let(players::remember)
        _playing.value = true
        s.started = scope.launch { report(s, "started") { s.reporter?.started(s.positionMs) } }
        pollJob?.cancel()
        pollJob = scope.launch { track(s) }
    }

    /**
     * Follows the player through its local API: every [POLL_MS] locally, every few polls to the
     * backend, and only what the player says now, never a position kept from before. It stops once
     * the player has gone on to another file, or after [SILENT_POLLS] polls in a row past a first
     * status that the player says nothing plays: the session waits for the player's result all the
     * same. An API out of reach says nothing of the film, whose NAS stays its own: past [SILENT_POLLS]
     * polls without an answer, it is asked every [SLOW_POLL_MS] only, so that what it says next still
     * counts, until the title can no longer be playing ([silenceAllowedMs]). That also ends [playing]:
     * an API gone with its player never holds the NAS for good.
     *
     * The position is written down ([keep]) at the backend's pace, and once more when tracking stops
     * while the session goes on: each write rewrites the file and syncs it to the flash.
     */
    private suspend fun track(s: Session) {
        try {
            poll(s)
        } finally {
            // Not once the player's result has ended the session: it cleared what was written down.
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

    /** [answer] says nothing of the title again, and the player last spoke of it ([heardAt]) longer ago than it could still play. */
    private fun Session.silentTooLong(answer: PlayerAnswer, heardAt: Long) =
        answer !is PlayerAnswer.Playing && clock() - heardAt > silenceAllowedMs()

    /**
     * How long the player may say nothing of the title (no answer, or nothing playing) before the title
     * can no longer be playing: what was left of it at the last status, and [SILENCE_MARGIN_MS] for a
     * pause or a seek back. Before a first status its length is unknown: [UNHEARD_MAX_MS]. So it is
     * when a disc has only shown a menu or an intro so far: what was left of it says nothing of the
     * film to come.
     */
    private fun Session.silenceAllowedMs(): Long {
        val title = durationMs?.takeUnless { disc && it < TITLE_MIN_MS } ?: return UNHEARD_MAX_MS
        return (title - positionMs).coerceAtLeast(0) + SILENCE_MARGIN_MS
    }

    /**
     * Takes what the player says for the session. A status of another length is another file: the
     * player went on to the next one of its folder, at the end of the file or as "next" was pressed.
     * When that file is the title's next part ([nextPart]), the session follows it as it did the first,
     * reported by its own reporter. Only a disc plays several lengths as one title: its film comes after
     * a menu or an intro, whose length gives way to the film's, unless what played was a title, played to
     * its end (the disc's menu comes back after its film).
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
                s.key = next.resumeKey
                // A new process that gets the player's result reports this part, not the one before; not
                // once the result has ended the session and cleared what was written down.
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
     * The title's next part ([PlaybackReporter.next]), when it is what the player went on to: its file's
     * name, when the player says what it plays; else the end of the file before ([ended]), where the
     * player goes on by itself to the next file of its folder, which a film's next part is. Only a path
     * tells a disc's next part from its menu.
     */
    private suspend fun Session.nextPart(state: ZidooPlayState, ended: Boolean): PlaybackReporter.Next? {
        val next = suspendRunCatching { reporter?.next() }.getOrNull() ?: return null
        val played = state.path
        return next.takeIf { if (played != null) names(played, next.path) else ended && !disc }
    }

    /** The player's [path] names the file at [relativePath]: by its name, escaped or not, whatever its folder or mount. */
    private fun names(path: String, relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/')
        val played = path.substringAfterLast('/')
        return played.equals(name, ignoreCase = true) || Uri.decode(played).equals(name, ignoreCase = true)
    }

    /** Writes down where the session is: a new process finds it there if Android stops this one (see [saved]). */
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
        scope.launch {
            // The last progress report is done, and the one that playback started, before the final one is sent.
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

    /** Tells the backend where [s] stands ([step]: started, progress, stopped); a failure is logged, with the file and the step. */
    private suspend fun report(s: Session, step: String, block: suspend () -> Unit) {
        suspendRunCatching { block() }.onFailure { Log.w(TAG, "Report that ${s.key} $step at ${s.positionMs} ms failed", it) }
    }

    /**
     * The file needs credentials and only the Zidoo player may receive them; [playerChanged]: the
     * player is no longer the one trusted with them before.
     */
    class CredentialsNotShared(val playerChanged: Boolean = false) : Exception("Credentials are only given to the Zidoo player")

    companion object {
        private const val TAG = "Playback"

        /**
         * Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, which the SDK hides: false, the chooser lists even a
         * single app instead of opening it unasked (its ChooserActivity reads it, on Android 9 and 11).
         */
        private const val AUTO_LAUNCH_SINGLE_CHOICE = "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE"
        private const val KEY_RESUME = "resume_key"
        private const val KEY_POSITION = "position_ms"

        /** The player takes a few seconds to open the file. */
        private const val FIRST_POLL_MS = 4_000L
        private const val POLL_MS = 2_000L

        /** Progress goes to the backend every this many polls: some ten seconds. */
        private const val REPORT_EVERY = 5

        /**
         * Polls in a row, past a first status, that the player says nothing plays before tracking
         * stops, or does not answer before it is asked less often: some thirty seconds.
         */
        private const val SILENT_POLLS = 15

        /** How often an API that has not answered a while is asked. */
        private const val SLOW_POLL_MS = 10_000L

        /** Past what was left of the title, how long the player may still say nothing of it (see [silenceAllowedMs]). */
        private const val SILENCE_MARGIN_MS = 5 * 60_000L

        /**
         * How long a player that never gave the title's length may say nothing before it is taken to
         * play no more: the longest cuts people watch run near four hours (and the scan waits for a
         * player as long). Shorter, a long film whose player's API never answers would share its NAS
         * with a scan; longer, a player that is gone would hold the scan for nothing.
         */
        private const val UNHEARD_MAX_MS = 4 * 60 * 60_000L

        /**
         * How close to its end, in polls, a disc's title was when the player shows another length: it
         * played to its end, and the disc's menu came back.
         */
        private const val END_POLLS = 3

        /** Shorter than this, what a disc plays first is its menu or an intro, never its title. */
        private const val TITLE_MIN_MS = 5 * 60_000L

        /**
         * Two statuses of the same file agree on its length within this, or within a hundredth of it,
         * room for a length the player refines as it reads (a transport stream's).
         */
        private const val SAME_FILE_MS = 2_000L
        private const val SAME_FILE_SHARE = 100
    }
}
