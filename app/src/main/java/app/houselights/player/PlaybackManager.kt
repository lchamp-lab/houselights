package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Launches the native Zidoo player and feeds playback progress back to the backend.
 *
 * Position is tracked twice: by polling the Zidoo local API while playing, then from the
 * "position" extra the player returns when it closes.
 */
class PlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The reporter of a playback the app started before it was stopped (see [PlaybackPlan.Launch.resumeKey]). */
    private val reporterFor: suspend (String) -> PlaybackReporter?,
) {
    private class Session(val reporter: PlaybackReporter?, var positionMs: Long) {
        var durationMs: Long? = null
        var paused = false
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
     * Only the Zidoo player, installed as part of the system, receives a URI with credentials: another
     * app answering a generic video intent never does. Throws [CredentialsNotShared] in that case.
     */
    fun intentFor(plan: PlaybackPlan.Launch): Intent {
        Log.i(TAG, "Launch ${plan.title} -> ${Redact.uri(plan.uri)}")

        val zidoo = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(ZIDOO_PACKAGE, ZIDOO_ACTIVITY)
            setDataAndType(Uri.parse(plan.uri), "video/${plan.container ?: "*"}")
            putExtra("title", plan.title)
            plan.backdropUrl?.let { putExtra("loading_image", it) }
            plan.logoUrl?.let { putExtra("clearlogo_image", it) }
            if (plan.startMs > 0) {
                putExtra("from_start", false)
                putExtra("position", plan.startMs.toInt())
            } else {
                putExtra("from_start", true)
            }
            putExtra("return_result", true)
        }
        val intent = if (isSystemZidooPlayer() && zidoo.resolveActivity(context.packageManager) != null) {
            zidoo
        } else {
            if (Redact.hasUserInfo(plan.uri)) throw CredentialsNotShared()
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(plan.uri), "video/*")
                putExtra("title", plan.title)
                putExtra("position", plan.startMs.toInt())
                putExtra("return_result", true)
            }
        }
        session = Session(plan.reporter, plan.startMs)
        saved.edit().putString(KEY_RESUME, plan.resumeKey).putLong(KEY_POSITION, plan.startMs).commit()
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
        scope.launch { report { s.reporter?.started(s.positionMs) } }
        pollJob?.cancel()
        pollJob = scope.launch {
            delay(4_000)
            var ticks = 0
            while (isActive) {
                ZidooStatus.playStatus()?.let { state ->
                    s.positionMs = state.positionMs
                    s.durationMs = state.durationMs
                    s.paused = state.paused
                    saved.edit().putLong(KEY_POSITION, state.positionMs).apply()
                }
                // Poll locally every 2 s, report to the backend every ~10 s.
                if (ticks++ % 5 == 0) report { s.reporter?.progress(s.positionMs, s.durationMs, s.paused) }
                delay(2_000)
            }
        }
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
            // The last progress report is done before the final one is sent.
            poll?.cancelAndJoin()
            // No live session: the app was stopped while the film played.
            val s = live ?: Session(resumeKey?.let { reporterFor(it) }, savedPosition)
            if (returned > 0) s.positionMs = returned.toLong()
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
        const val ZIDOO_PACKAGE = "com.android.gallery3d"
        const val ZIDOO_ACTIVITY = "com.android.gallery3d.app.MovieActivity"
    }
}
