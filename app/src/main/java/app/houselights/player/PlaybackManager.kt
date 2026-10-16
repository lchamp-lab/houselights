package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import app.houselights.R
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.isWebUrl
import app.houselights.core.suspendRunCatching
import app.houselights.ui.util.UiError
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
    private val status: suspend () -> ZidooPlayState? = ZidooStatus::playStatus,
    /** The reporter of a playback the app started before it was stopped (see [PlaybackPlan.Launch.resumeKey]). */
    private val reporterFor: suspend (String) -> PlaybackReporter?,
) {
    private class Session(val reporter: PlaybackReporter?, var positionMs: Long) {
        /** The length of the file launched, from the player's first status: a status of another length is another file. */
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

    private val _playbackProblem = MutableStateFlow<UiError?>(null)

    /**
     * Why the last title asked for did not play, shown where it was asked for (the detail page) until
     * the next action or the next screen clears it.
     */
    val playbackProblem: StateFlow<UiError?> = _playbackProblem

    fun reportProblem(error: UiError) {
        _playbackProblem.value = error
    }

    fun clearProblem() {
        _playbackProblem.value = null
    }

    /**
     * Builds the intent for the Zidoo player, or for any video player elsewhere.
     *
     * Only the Zidoo player, installed as part of the system, receives a URI with a password: another
     * app answering a generic video intent never does ([CredentialsNotShared]). A URI with a token in
     * its query (Jellyfin's stream) goes elsewhere only through a chooser: the user picks the app,
     * none takes it without being asked.
     */
    fun intentFor(plan: PlaybackPlan.Launch): Intent {
        Log.i(TAG, "Launch ${plan.title} -> ${Redact.uri(plan.uri)}")

        val zidoo = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(ZIDOO_PACKAGE, ZIDOO_ACTIVITY)
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
        val intent = when {
            isSystemZidooPlayer() && zidoo.resolveActivity(context.packageManager) != null -> zidoo
            Redact.hasUserInfo(plan.uri) -> throw CredentialsNotShared()
            else -> {
                val any = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(plan.uri), "video/*")
                    putExtra("title", plan.title)
                    putExtra("position", plan.startMs.toInt())
                    putExtra("return_result", true)
                }
                if (Redact.hasCredentials(plan.uri)) Intent.createChooser(any, context.getString(R.string.play_with)) else any
            }
        }
        session = Session(plan.reporter, plan.startMs)
        // Written before the player takes over: the platform finishes pending apply() writes before our
        // activity stops, which launching the player does, so the resume point survives a killed process.
        saved.edit().putString(KEY_RESUME, plan.resumeKey).putLong(KEY_POSITION, plan.startMs).apply()
        return intent
    }

    /** The player could not be started after all. */
    fun onLaunchFailed() {
        session = null
        _playing.value = false
        saved.edit().clear().apply()
    }

    /** The Zidoo player is a system app; an app installed under its package name elsewhere is not. */
    private fun isSystemZidooPlayer(): Boolean = try {
        context.packageManager.getApplicationInfo(ZIDOO_PACKAGE, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false
    }

    /** Call right after the player activity was started. */
    fun onPlayerLaunched() {
        val s = session ?: return
        _playing.value = true
        s.started = scope.launch { report { s.reporter?.started(s.positionMs) } }
        pollJob?.cancel()
        pollJob = scope.launch { track(s) }
    }

    /**
     * Follows the player through its local API: every [POLL_MS] locally, every few polls to the
     * backend, and only what the player says now, never a position kept from before. It stops once
     * the player has gone on to another file, or once it has said nothing for [SILENT_POLLS] polls
     * after a first status (the player left in the background, an API gone): the session waits
     * for the player's result all the same.
     */
    private suspend fun track(s: Session) {
        delay(FIRST_POLL_MS)
        var tick = 0
        var silent = 0
        while (currentCoroutineContext().isActive && silent < SILENT_POLLS && !s.switched) {
            val state = status()
            if (state == null) {
                // Before a first status, the player may still be opening the file.
                if (s.durationMs != null) silent++
            } else {
                silent = 0
                follow(s, state)
                if (!s.switched && tick % REPORT_EVERY == 0) report { s.reporter?.progress(s.positionMs, s.durationMs, s.paused) }
            }
            tick++
            delay(POLL_MS)
        }
        if (silent >= SILENT_POLLS) {
            Log.i(TAG, "The player says nothing more: tracking stops")
            _playing.value = false
        }
    }

    /** Takes what the player says for the session, unless it now plays a file of another length. */
    private fun follow(s: Session, state: ZidooPlayState) {
        val launched = s.durationMs
        if (launched != null && abs(state.durationMs - launched) > SAME_FILE_MS) {
            Log.i(TAG, "The player went on to another file ($launched -> ${state.durationMs} ms)")
            s.switched = true
            return
        }
        s.positionMs = state.positionMs
        s.durationMs = state.durationMs
        s.paused = state.paused
        saved.edit().putLong(KEY_POSITION, state.positionMs).apply()
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
            val s = live ?: Session(resumeKey?.let { reporterFor(it) }, savedPosition)
            // A position the player returns for another file is not this title's.
            if (returned > 0 && !s.switched) s.positionMs = returned.toLong()
            Log.i(TAG, "Playback ended at ${s.positionMs} / ${s.durationMs} ms")
            report { s.reporter?.stopped(s.positionMs, s.durationMs) }
            _playbackEnded.emit(Unit)
        }
    }

    private suspend fun report(block: suspend () -> Unit) {
        suspendRunCatching { block() }.onFailure { Log.w(TAG, "Progress report failed", it) }
    }

    /** The file needs credentials and only the Zidoo player may receive them. */
    class CredentialsNotShared : Exception("Credentials are only given to the Zidoo player")

    companion object {
        private const val TAG = "Playback"
        private const val KEY_RESUME = "resume_key"
        private const val KEY_POSITION = "position_ms"

        /** The player takes a few seconds to open the file. */
        private const val FIRST_POLL_MS = 4_000L
        private const val POLL_MS = 2_000L

        /** Progress goes to the backend every this many polls: some ten seconds. */
        private const val REPORT_EVERY = 5

        /** Polls without a word, past a first status, before tracking stops: some thirty seconds. */
        private const val SILENT_POLLS = 15

        /** Two statuses of the same file agree on its length within this. */
        private const val SAME_FILE_MS = 2_000L
        const val ZIDOO_PACKAGE = "com.android.gallery3d"
        const val ZIDOO_ACTIVITY = "com.android.gallery3d.app.MovieActivity"
    }
}
