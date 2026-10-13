package app.posterwall.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.posterwall.core.PlaybackPlan
import app.posterwall.core.PlaybackReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Launches the native Zidoo player and feeds playback progress back to the backend.
 *
 * Position is tracked twice: by polling the Zidoo local API while playing, then from the
 * "position" extra the player returns when it closes.
 */
class PlaybackManager(private val context: Context, private val scope: CoroutineScope) {
    private class Session(val reporter: PlaybackReporter?, var positionMs: Long) {
        var durationMs: Long? = null
        var paused = false
    }

    private var session: Session? = null
    private var pollJob: Job? = null

    private val _playbackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Emits when playback ends: screens reload resume points and watched state. */
    val playbackEnded: SharedFlow<Unit> = _playbackEnded

    /** Builds the intent for the Zidoo player, or the system video player elsewhere. */
    fun intentFor(plan: PlaybackPlan.Launch): Intent {
        session = Session(plan.reporter, plan.startMs)
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
        if (zidoo.resolveActivity(context.packageManager) != null) return zidoo

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(plan.uri), "video/*")
            putExtra("title", plan.title)
            putExtra("position", plan.startMs.toInt())
            putExtra("return_result", true)
        }
    }

    /** Call right after the player activity was started. */
    fun onPlayerLaunched() {
        val s = session ?: return
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
                }
                // Poll locally every 2 s, report to the backend every ~10 s.
                if (ticks++ % 5 == 0) report { s.reporter?.progress(s.positionMs, s.durationMs, s.paused) }
                delay(2_000)
            }
        }
    }

    /** Player result: final position in ms when the player returns it. */
    fun onPlayerResult(data: Intent?) {
        val s = session ?: return
        session = null
        pollJob?.cancel()
        val returned = data?.getIntExtra("position", -1) ?: -1
        if (returned > 0) s.positionMs = returned.toLong()
        Log.i(TAG, "Playback ended at ${s.positionMs} / ${s.durationMs} ms")
        scope.launch {
            report { s.reporter?.stopped(s.positionMs, s.durationMs) }
            _playbackEnded.emit(Unit)
        }
    }

    /** For delegated playback, refresh screens when the user comes back. */
    fun notifyChanged() {
        _playbackEnded.tryEmit(Unit)
    }

    private suspend fun report(block: suspend () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "Progress report failed", it) }
    }

    companion object {
        private const val TAG = "Playback"
        const val ZIDOO_PACKAGE = "com.android.gallery3d"
        const val ZIDOO_ACTIVITY = "com.android.gallery3d.app.MovieActivity"
    }
}
