package app.houselights.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import app.houselights.R
import app.houselights.core.PlaybackPlan
import app.houselights.core.PlaybackReporter
import app.houselights.core.RepeatedFailures
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
import java.io.File
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
        /** Key of the playing file ([PlaybackPlan.Launch.resumeKey]), for the log and [playersOwnTracks]. */
        var key: String? = null,
        /** The player a secret goes to, remembered only once it has started ([onPlayerLaunched]). */
        val trusting: ZidooPlayer.Choice? = null,
        /** The tracks asked of the player, checked once it plays ([check]); null when it chose them, or for a next part. */
        var tracks: TrackPlan? = null,
        /** The URI launched, then the part followed: the player's result is read against it if no status named the file. */
        var file: String? = null,
    ) {
        /** The length of the title, from the player's first status (see [follow]). */
        var durationMs: Long? = null
        var paused = false

        /** The file as the player names it in its first status that does, then the part followed (see [follow]). */
        var path: String? = null

        /** The player moved on to another file: its statuses no longer concern this title. */
        var switched = false

        /** The "started" report, which the "stopped" report waits for. */
        var started: Job? = null

        /** Its failed reports, one every few seconds while a server is away: the first logged whole, the next counted. */
        val failedReports = RepeatedFailures(TAG)

        /** Statuses heard while it played, until the tracks are checked ([CHECK_AFTER]). */
        var heard = 0

        /** Checks made: one per status while the player's lists are missing, [CHECK_TRIES] at most. */
        var tries = 0

        /** The check has begun; it is over once [settled]. */
        var checked = false
        var settled = false

        /** The player's lists, as the check read them: the numbers it returns are its own; null until one is read. */
        var plays: PlayerTracks? = null

        /** Tracks the player played without the user (asked, its own, switched to), by kind: a return to one is no choice. */
        val usual = mutableMapOf<TrackKind, MutableSet<Int>>()

        fun usual(kind: TrackKind, index: Int?) {
            if (index != null) usual.getOrPut(kind) { mutableSetOf() } += index
        }

        fun isUsual(kind: TrackKind, index: Int?): Boolean = index != null && index in usual[kind].orEmpty()
    }

    private var session: Session? = null
    private var pollJob: Job? = null

    /** The session, persisted before launch: on a 2 GB player, Android may kill the app during the film. */
    private val saved = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    /** Files last played while the app was stopped, by [PlaybackPlan.Launch.resumeKey] (see [playersOwnTracks]). */
    private val unfollowed = context.getSharedPreferences("unfollowed", Context.MODE_PRIVATE)

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
     * On a test-keys firmware, the firmware's player once updated is pinned with its build at its first password, and
     * any later update is a change until the user trusts it ([ZidooPlayer.trust]). Not guarded against: a player
     * installed before the first password, under a Zidoo package name or as an update of the system's (trusted on
     * first use: there is nothing to compare), and anyone reading the device log over adb, where Android logs the URI
     * it starts.
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
        // Tracks are numbered as the known Zidoo player numbers them: asked of it only.
        val numbered = tracks?.takeIf { ZidooPlayer.takesTracks(player?.component) }
        val intent = when {
            player != null && (!secret || choice.decision.trusted) -> playerIntent(player.component, plan, numbered)
            Redact.hasUserInfo(plan.uri) || secret && changed -> throw CredentialsNotShared(playerChanged = changed)
            else -> anyPlayerIntent(plan, chooser = secret)
        }
        val trusting = choice.takeIf { secret && intent.component != null }
        val asked = numbered?.takeIf { intent.component != null }
        session = Session(plan.reporter, plan.startMs, plan.disc, plan.resumeKey, trusting, asked, plan.uri).apply {
            usual(TrackKind.AUDIO, asked?.pick?.audio)
            usual(TrackKind.SUBTITLE, asked?.pick?.subtitle)
        }
        // Pending apply() writes complete before our activity stops, so the resume point survives a killed process.
        saved.edit {
            putString(KEY_RESUME, plan.resumeKey).putLong(KEY_POSITION, plan.startMs)
                .putString(KEY_FILE, nameOf(plan.uri).takeUnless { plan.disc })
        }
        return intent
    }

    private fun playerIntent(player: ComponentName, plan: PlaybackPlan.Launch, tracks: TrackPlan?) = Intent(Intent.ACTION_VIEW).apply {
        component = player
        // A path as a URI of its own, never parsed: a colon in a file's name would read as its scheme.
        val data = pathOf(plan.uri)?.let { Uri.Builder().encodedPath(it).build() } ?: plan.uri.toUri()
        setDataAndType(data, "video/${plan.container ?: "*"}")
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
            val beside = if (tracks.subtitleBeside) ", better ones looked for beside the video" else ""
            Log.i(TAG, "Tracks asked: audio ${pick.audio}, subtitles ${pick.subtitle}$beside")
            pick.audio?.let { putExtra("audio_idx", it) }
            pick.subtitle?.let { putExtra("subtitle_idx", it) }
        }
        putExtra("return_result", true)
    }

    /**
     * Any video player's intent, through a [chooser] for a URI with a token so that the user picks the app. A file of
     * the player's own disks goes as a content:// address of the app's own provider (the manifest's, [FILES]), which
     * that app may read for this file alone.
     */
    private fun anyPlayerIntent(plan: PlaybackPlan.Launch, chooser: Boolean): Intent {
        val path = pathOf(plan.uri)
        val any = Intent(Intent.ACTION_VIEW).apply {
            if (path == null) {
                setDataAndType(plan.uri.toUri(), "video/*")
            } else {
                setDataAndType(FileProvider.getUriForFile(context, context.packageName + FILES, File(Uri.decode(path))), "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
        saved.edit { clear() }
    }

    /** Call once the player activity has started; remembers a player given a secret ([ZidooPlayer.remember]). */
    fun onPlayerLaunched() {
        val s = session ?: return
        s.trusting?.let(players::remember)
        // From the start, the player's memory of the file no longer matters.
        if (s.positionMs <= 0) s.key?.let { unfollowed.edit { remove(it) } }
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
     * Applies a player status. Another file ([anotherFile]: the next in its folder, at the end or on "next") is
     * followed if it is the title's next part ([nextPart]). Only a disc changes length within a title, its film
     * following a menu or intro, unless a title had played to its end and the menu returned.
     */
    private suspend fun follow(s: Session, state: ZidooPlayState) {
        val launched = s.durationMs
        if (launched != null && s.anotherFile(state, launched)) {
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
                s.path = state.path
                s.file = next.path
                // So a new process receiving the result reports this part; skipped once the result ended the session.
                if (session === s) saved.edit { putString(KEY_RESUME, next.resumeKey).putString(KEY_FILE, nameOf(next.path)) }
            } else {
                Log.i(TAG, "The title is ${state.durationMs} ms long, past a menu or an intro of $launched ms")
            }
        }
        s.positionMs = state.positionMs
        s.durationMs = state.durationMs
        s.paused = state.paused
        if (s.path == null) s.path = state.path
    }

    /**
     * Whether [state] is another file than the one followed, [launched] long: of another length, or named
     * otherwise. Unnamed, a file as long that starts over right after the end is the folder's next one. A disc
     * changes title and length while it stays the same disc: only its length counts (see [follow]).
     */
    private fun Session.anotherFile(state: ZidooPlayState, launched: Long): Boolean {
        if (abs(state.durationMs - launched) > maxOf(SAME_FILE_MS, launched / SAME_FILE_SHARE)) return true
        val followed = path
        val played = state.path
        return when {
            disc -> false
            followed != null && played != null -> !names(played, followed)
            else -> positionMs >= launched - END_POLLS * POLL_MS && state.positionMs < minOf(positionMs, RESTART_MS)
        }
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

    /** Whether the player's [path] names the file at [other], either escaped or not, whatever their mounts. */
    private fun names(path: String, other: String): Boolean {
        val name = leaf(other)
        val played = leaf(path)
        val decoded = Uri.decode(played)
        return played.equals(name, ignoreCase = true) || decoded.equals(name, ignoreCase = true) ||
            decoded.equals(Uri.decode(name), ignoreCase = true)
    }

    /** The last segment of [path]; a stream's without its query, which the player may give otherwise. */
    private fun leaf(path: String): String {
        val web = path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)
        return (if (web) path.substringBefore('?').substringBefore('#') else path).substringAfterLast('/')
    }

    /** The file's name alone, kept for a new process: never its URI, which may carry a password. */
    private fun nameOf(uri: String): String = leaf(uri)

    /** Whether the player ended on [played], another file than the one followed; a disc's is not compared. */
    private fun Session.endedElsewhere(played: String?): Boolean {
        val followed = (path ?: file)?.takeUnless { disc }
        return played != null && followed != null && !names(played, followed)
    }

    /**
     * Once the film has played a few statuses ([CHECK_AFTER]), past the player's own choice of tracks, switches to
     * the tracks asked if it plays others under their numbers ([TrackCheck]). Tried again at the next statuses until
     * the player gives both lists at once, the subtitles checked against the audio they play with ([CHECK_TRIES] at
     * most); a track the user chose meanwhile is theirs, never undone.
     */
    private suspend fun check(s: Session) {
        val plan = s.tracks ?: return
        if (s.switched || s.settled || ++s.heard < CHECK_AFTER) return
        s.checked = true
        val read = tracks()
        s.plays = (s.plays ?: read)?.with(read)
        if (read != null) switch(s, read, TrackCheck.fixes(plan, read))
        if (read?.complete == true || ++s.tries >= CHECK_TRIES) s.settled = true
    }

    /** Switches the player to [fixes], found in what it played ([read]). */
    private suspend fun switch(s: Session, read: PlayerTracks, fixes: List<TrackFix>) {
        if (fixes.isEmpty()) return
        for (fix in fixes) {
            val done = select(fix.kind, fix.index)
            Log.i(TAG, "The player played other tracks: ${fix.kind} ${fix.index}${if (done) "" else ", refused"}")
            if (done) s.usual(fix.kind, fix.index)
        }
        // Read again once applied: another audio may have made the player choose its subtitles anew, unless the user had.
        delay(SETTLE_MS)
        val now = tracks()
        if (s.isUsual(TrackKind.AUDIO, read.audioIndex)) s.usual(TrackKind.AUDIO, now?.audioIndex)
        if (s.isUsual(TrackKind.SUBTITLE, read.subtitleIndex)) s.usual(TrackKind.SUBTITLE, now?.subtitleIndex)
    }

    /**
     * What the user changed in the player among the tracks asked of it, which becomes the title's choice. Nothing
     * when in doubt: another file, a check under way, or numbers that may not be the file's.
     */
    private fun changed(s: Session, data: Intent?, played: String?): TrackChoice? {
        val plan = s.tracks?.takeUnless { s.doubtful(played) } ?: return null
        fun returned(kind: TrackKind, extra: String, asked: Int?) =
            data?.getIntExtra(extra, -1)?.takeIf { it >= 0 && asked != null && !s.isUsual(kind, it) }
        val audio = returned(TrackKind.AUDIO, "audio_idx", plan.pick.audio)
        val subtitle = returned(TrackKind.SUBTITLE, "subtitle_idx", plan.pick.subtitle)
        if (audio == null && subtitle == null) return null
        // A list the player did not give leaves the file's numbering, its own unless files beside the video count: a
        // server's, or, once the check found no list, ones a scan cannot know of (a subtitle file the player may list).
        val byFile = !plan.media.hasFilesBeside && !(s.checked && plan.media.readFromFile)
        return TrackCheck.choiceOf(plan, s.plays, audio, subtitle, byFile).takeUnless { it.isEmpty }
    }

    /** What the player played is in doubt: another file ([played] the one it ended on), or a check under way. */
    private fun Session.doubtful(played: String?): Boolean = switched || checked && !settled || endedElsewhere(played)

    /** Persists the session's position for a new process if Android kills this one (see [saved]). */
    private fun keep(s: Session) {
        saved.edit { putLong(KEY_POSITION, s.positionMs) }
    }

    /**
     * Whether the file of [resumeKey] was last played while the app was stopped, until it is played from the start:
     * what the user chose in the player then, only its memory of the file keeps ([TrackChooser.plan]).
     */
    fun playersOwnTracks(resumeKey: String): Boolean = unfollowed.contains(resumeKey)

    /** Player result: final position in ms when the player returns it. */
    fun onPlayerResult(data: Intent?) {
        val live = session
        val resumeKey = saved.getString(KEY_RESUME, null)
        val savedPosition = saved.getLong(KEY_POSITION, 0)
        val savedFile = saved.getString(KEY_FILE, null)
        session = null
        _playing.value = false
        saved.edit { clear() }
        if (live == null && resumeKey == null) return
        // Stopped while the film played, the app could not follow what the user chose in the player.
        if (live == null) unfollowed.edit { putBoolean(resumeKey, true) }
        val poll = pollJob
        pollJob = null
        val returned = data?.getIntExtra("position", -1) ?: -1
        // The file the player played last: empty when none, another when it went on to the folder's next file.
        val played = data?.getStringExtra("url")?.ifBlank { null }
        val key = live?.tracks?.titleKey
        val changed = live?.let { changed(it, data, played) }
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
            val s = live ?: Session(resumeKey?.let { reporterFor(it) }, savedPosition, key = resumeKey, file = savedFile)
            // A position the player returns for another file is not this title's.
            if (returned > 0 && !s.switched && !s.endedElsewhere(played)) s.positionMs = returned.toLong()
            Log.i(TAG, "Playback ended at ${s.positionMs} / ${s.durationMs} ms")
            report(s, "stopped") { s.reporter?.stopped(s.positionMs, s.durationMs) }
            s.toldFailedReports()
            _playbackEnded.emit(Unit)
        }
    }

    /** Reports [s] at [step] (started, progress, stopped); a failure is only logged, whole the first time in a row. */
    private suspend fun report(s: Session, step: String, block: suspend () -> Unit) {
        suspendRunCatching { block() }
            .onSuccess {
                s.failedReports.recovered(REPORTS)
                s.toldFailedReports()
            }
            .onFailure { s.failedReports.failed(REPORTS, "Report that ${s.key} $step at ${s.positionMs} ms failed", it) }
    }

    /** One line for the reports that failed after the one logged, once they work again or the title ends. */
    private fun Session.toldFailedReports() = failedReports.summarize { "Reports of $key: $it more failed" }

    /** The file needs credentials only the trusted Zidoo player may receive; [playerChanged] if it changed. */
    class CredentialsNotShared(val playerChanged: Boolean = false) : Exception("Credentials are only given to the Zidoo player")

    companion object {
        private const val TAG = "Playback"

        private const val FILE = "file://"

        /** The authority of the app's provider for its disks' files, after its package name (the manifest's FileProvider). */
        private const val FILES = ".files"

        /**
         * A file of the player's own disks (a file:// URI) as its path, still escaped where a URI would end it ("%23" for
         * "#", as a share's), else null. Android stops an app handing another a file:// URI (FileUriExposedException,
         * from Android 7); the Zidoo player opens the path as it opens a share's address, the subtitles beside the video
         * and a film's next part with it.
         */
        internal fun pathOf(uri: String): String? = if (uri.startsWith(FILE)) uri.removePrefix(FILE) else null

        /** What [RepeatedFailures] keys a session's reports by: all of them, whichever step. */
        private const val REPORTS = "reports"

        /** Appended to the launch warning when the player found does not handle a stream's address. */
        private const val UNANSWERED_STREAM = "; the player does not answer this stream's address: not handed it"

        /** Hidden EXTRA_AUTO_LAUNCH_SINGLE_CHOICE: false makes the chooser list even a single app (Android 9, 11). */
        private const val AUTO_LAUNCH_SINGLE_CHOICE = "android.intent.extra.AUTO_LAUNCH_SINGLE_CHOICE"
        private const val KEY_RESUME = "resume_key"
        private const val KEY_POSITION = "position_ms"
        private const val KEY_FILE = "file_name"

        /** The player takes a few seconds to open the file. */
        private const val FIRST_POLL_MS = 4_000L

        /** Statuses heard before checking the tracks: the player sets its subtitles 3 s after opening the file. */
        private const val CHECK_AFTER = 3

        /** Checks at most, one per status, while the player's lists are missing (a 2 GB player opening a 4K film). */
        private const val CHECK_TRIES = 5

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

        /** Within this many polls of its end, a title has played out: to a disc's menu, or to the folder's next file. */
        private const val END_POLLS = 3

        /** Back within this of the start right after the end, a file the player does not name is the next one. */
        private const val RESTART_MS = 30_000L

        /** Shorter than this, what a disc plays first is its menu or an intro, never its title. */
        private const val TITLE_MIN_MS = 5 * 60_000L

        /** Same-file length tolerance, or 1 % of the length: a transport stream's length is refined while reading. */
        private const val SAME_FILE_MS = 2_000L
        private const val SAME_FILE_SHARE = 100
    }
}
