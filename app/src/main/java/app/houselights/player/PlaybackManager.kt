package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
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
        return intent
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

    /** The file needs credentials and only the Zidoo player may receive them. */
    class CredentialsNotShared : Exception("Credentials are only given to the Zidoo player")

    companion object {
        private const val TAG = "Playback"
        const val ZIDOO_PACKAGE = "com.android.gallery3d"
        const val ZIDOO_ACTIVITY = "com.android.gallery3d.app.MovieActivity"
    }
}
