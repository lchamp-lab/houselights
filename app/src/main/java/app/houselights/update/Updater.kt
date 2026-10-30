package app.houselights.update

import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import app.houselights.core.FailureKind
import app.houselights.core.failureKind
import app.houselights.core.suspendRunCatching
import app.houselights.player.Redact
import app.houselights.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/** What the updater works with: GitHub, the choice of a release, the download, its check and Android's installer. */
class UpdateParts(
    val releases: ReleaseSource,
    val choice: ReleaseChoice,
    val download: Downloads,
    val verifier: UpdateVerifier,
    val installer: Installer,
)

/** When the updater may ask GitHub or open Android's window: the app on screen, nothing playing nor scanning, launched. */
class UpdateMoments(
    val onScreen: StateFlow<Boolean>,
    val playing: Flow<Boolean>,
    val scanning: Flow<Boolean>,
    /** Returns once the launch screen has lifted. */
    val launched: suspend () -> Unit,
)

/** What the update's rows and sheets ask of the updater ([Updater]), each a user's action. */
interface UpdateControls {
    fun checkNow()

    fun opened()

    fun closed()

    fun update()

    fun retry()

    fun cancel()

    fun allow()

    fun reopen()

    fun shownOnHome(versionCode: Long)

    fun updatedSaid()
}

/**
 * Updates the app from its releases on GitHub (README, Install), in a release signed with the project's key alone
 * ([InstalledApp.available]). Once a day, while the app is on screen and nothing plays or scans, it asks GitHub whether
 * a newer version exists; it never downloads by itself. On OK it downloads the APK, checks it ([ApkDownload],
 * [UpdateVerifier]) and, only while its sheet is open and the app on screen, hands it to Android, which always asks the
 * user to confirm. No timer ever abandons a session Android holds. Its state lives on the main thread.
 */
class Updater(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
    private val store: UpdateStore,
    private val app: InstalledApp,
    private val parts: UpdateParts,
    private val moments: UpdateMoments,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where files are read and written: the device's storage, off the main thread. */
    private val io: CoroutineContext = Dispatchers.IO,
) : UpdateControls {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    /** The download, its check and the commit: one at a time. */
    private var work: Job? = null
    private val checks = Mutex()

    /** When GitHub last answered an ask: another within [SINGLE_FLIGHT_MS] keeps that answer. */
    private var answeredAt = Long.MIN_VALUE
    private var asking: Job? = null

    /** The APK verified, by versionCode: « Installer » hands it to Android without downloading it again. */
    private var ready: Pair<Long, File>? = null

    /** The session Android holds, its confirmation, and whether the app left the screen while it was up. */
    private var session: Int? = null
    private var confirmation: Intent? = null
    private var away = false

    /** Update sheets open: Android's window opens only over one, never by itself. */
    private var sheets = 0

    /** The user is in Android's settings, allowing installs. */
    private var allowing = false

    /** What « Réessayer » asks again: the check (a check failed, which GitHub's next answer puts behind it), or the update. */
    private var retriesCheck = true

    /** What this start found pending, once read: the update that replaced the app, if it was its own. */
    private val started = CompletableDeferred<Pending?>()

    /** The automatic checks that failed in the last day: each waits an hour, three a day at most. */
    private val failedAt = ArrayDeque<Long>()

    private val UpdateState.idle: Boolean get() = step == UpdateStep.Idle || step == UpdateStep.Checking

    /**
     * Runs the updater for the process's life: forgets what a previous one left (downloads, sessions), says what the
     * last update did, then checks once a day, the first time 20 s after the launch screen lifts.
     */
    suspend fun run() {
        if (!app.available) return
        _state.update { it.copy(on = true, installed = app.versionName()) }
        val saved = store.read()
        started.complete(saved.pending)
        withContext(io) { parts.download.clear() }
        parts.installer.abandonAll()
        settle(saved)
        scope.launch { followSettings() }
        scope.launch { followScreen() }
        // What was kept is offered in the channel the settings choose, once read: never in the one before them.
        settings.first { it.loaded }
        restore(saved)
        moments.launched()
        delay(FIRST_CHECK_MS)
        while (true) {
            moments.onScreen.first { it }
            settings.first { it.loaded && it.updateCheck }
            combine(moments.playing, moments.scanning) { playing, scanning -> !playing && !scanning }.first { it }
            val now = clock()
            val due = dueAt(now)
            if (due > now) delay(minOf(due - now, HOUR_MS)) else ask(manual = false)
        }
    }

    /** The install handed to Android before this start: installed, or not, said once. */
    private suspend fun settle(saved: Saved) {
        val now = clock()
        val pending = saved.pending
        val updated = if (pending != null) {
            val installed = app.versionCode() >= pending.versionCode
            Log.i(TAG, "${pending.versionName} ${if (installed) "installed" else "was not installed"}")
            Updated(pending.versionName, installed, now).also {
                store.pending(null)
                store.updated(it)
            }
        } else {
            saved.updated?.takeIf { now - it.at in 0 until DAY_MS }
        }
        // A wait kept from a clock that ran ahead, set right since, is brought back within the hour, and kept so.
        val wait = saved.waitUntil?.takeIf { it > now }?.let { minOf(it, now + MAX_WAIT_MS) }
        if (wait != saved.waitUntil && wait != null) store.waitUntil(wait)
        _state.update {
            it.copy(
                updated = updated,
                checkedAt = saved.checkedAt,
                waitUntil = wait,
                seenOnHome = saved.offeredOnHome,
            )
        }
    }

    /** The offer the last check found, from what was kept; and the sheet where it stood if Android closed the app. */
    private suspend fun restore(saved: Saved) {
        (kept(saved) as? Found.Offer)?.let { offer(it.offer) }
        val trip = saved.trip ?: return
        store.trip(null)
        val offer = state.value.offer
        if (offer?.versionCode != trip.versionCode || clock() - trip.at !in 0..TRIP_MS) return
        val step = if (parts.installer.allowed()) {
            UpdateStep.Idle
        } else {
            UpdateStep.NeedsPermission(parts.installer.permissionScreen(), refused = true)
        }
        _state.update { it.copy(step = step, backOnHome = true) }
    }

    /** The two settings as the state says them; another channel is chosen among what was kept, then asked. */
    private suspend fun followSettings() {
        var first = true
        settings.filter { it.loaded }.map { it.updateCheck to beta(it) }.distinctUntilChanged().collect { (automatic, beta) ->
            val channel = beta != state.value.beta
            _state.update { it.copy(automatic = automatic, beta = beta) }
            if (channel && !first) chooseAgain(automatic)
            first = false
        }
    }

    private suspend fun chooseAgain(automatic: Boolean) {
        if (work?.isActive == true) return
        offer((kept(store.read()) as? Found.Offer)?.offer)
        if (automatic) scope.launch { ask(manual = false) }
    }

    /** What the releases kept from the last check hold, read again without asking GitHub. */
    private suspend fun kept(saved: Saved): Found? =
        saved.releases?.let { listed -> parts.choice.pick(listed, beta(), app.versionCode()) { saved.manifests[it.release.tag] } }

    /** Leaving the screen stops a download, never an install Android holds; the app's return says where it stands. */
    private suspend fun followScreen() {
        moments.onScreen.collect { on ->
            if (on) return@collect
            when (state.value.step) {
                is UpdateStep.Downloading -> cancel()
                is UpdateStep.Installing -> away = true
                else -> Unit
            }
        }
    }

    /** When the next automatic check is due: a day after GitHub's last answer, an hour after a failure, never before its wait. */
    private fun dueAt(now: Long): Long {
        val s = state.value
        // A check from the future (a player's clock set back) is due now.
        var due = s.checkedAt?.takeIf { it <= now }?.plus(DAY_MS) ?: now
        waitFrom(now)?.let { due = maxOf(due, it) }
        failedAt.removeAll { now - it !in 0 until DAY_MS }
        failedAt.lastOrNull()?.let { due = maxOf(due, it + HOUR_MS) }
        if (failedAt.size >= AUTOMATIC_TRIES) due = maxOf(due, failedAt.first() + DAY_MS)
        return due
    }

    private fun beta(s: AppSettings = settings.value): Boolean = s.updatePrereleases ?: state.value.installed.contains('-')

    /**
     * « Rechercher maintenant », « Réessayer », or a sheet opened: Android's permission given meanwhile is seen; an answer
     * under a minute old stands; never while GitHub asks to wait.
     */
    override fun checkNow() {
        if (!state.value.on) return
        allowedMeanwhile()
        if (waits() || asking?.isActive == true) return
        if (clock() - answeredAt in 0 until SINGLE_FLIGHT_MS && state.value.failure == null) return
        asking = scope.launch { ask(manual = true) }
    }

    /** Whether GitHub asked to wait, and the wait is not over ([waitFrom]). */
    private fun waits(): Boolean {
        val now = clock()
        return waitFrom(now)?.let { it > now } == true
    }

    /**
     * GitHub's wait as [now] sees it, never past [MAX_WAIT_MS] from it: one from a clock that ran ahead, set right since,
     * is brought back within the hour, so that it ends rather than hold every check until the clock catches up.
     */
    private fun waitFrom(now: Long): Long? {
        val wait = state.value.waitUntil ?: return null
        val latest = now + MAX_WAIT_MS
        if (wait <= latest) return wait
        _state.update { s -> s.copy(waitUntil = s.waitUntil?.let { minOf(it, latest) }) }
        return latest
    }

    /** Asks GitHub; a check asked shows that it asks and how it failed, the daily one only logs. */
    private suspend fun ask(manual: Boolean) = checks.withLock {
        if (waits()) {
            if (manual) checkFailed(UpdateFailure.BUSY)
            return@withLock
        }
        if (manual) _state.update { it.copy(failure = null, step = if (it.offer == null && it.idle) UpdateStep.Checking else it.step) }
        suspendRunCatching { look() }
            .onSuccess { found -> answered(found, manual) }
            .onFailure { e ->
                currentCoroutineContext().ensureActive()
                val failure = failureOf(e, UpdateFailure.GITHUB_FAILED)
                Log.w(TAG, "${if (manual) "Check" else "Daily check"} failed: $failure", e)
                rememberWait(e)
                if (manual) checkFailed(failure) else failedAt.addLast(clock())
            }
        _state.update { if (it.step == UpdateStep.Checking) it.copy(step = UpdateStep.Idle) else it }
    }

    /**
     * GitHub answered: the version it offers, or none, or one this version cannot read (said when asked); a check's
     * failure, the daily one's included, is behind it, an update's stays as long as its version is offered.
     */
    private fun answered(found: Found, manual: Boolean) {
        answeredAt = clock()
        failedAt.clear()
        if (retriesCheck) _state.update { it.copy(failure = null) }
        when (found) {
            is Found.Offer -> offer(found.offer)
            Found.UpToDate -> offer(null)
            Found.Unreadable -> {
                offer(null)
                if (manual) _state.update { it.copy(failure = UpdateFailure.UNREADABLE) }
            }
        }
    }

    /** A check asked failed: said, and « Réessayer » asks again. */
    private fun checkFailed(failure: UpdateFailure) {
        retriesCheck = true
        _state.update { it.copy(failure = failure) }
    }

    /**
     * GitHub's list, then the manifests of its newest releases of the channel; the one offered is kept for the next start.
     * The list is kept at once, for the next check's 304, but checked only once its manifests are read: a manifest failing
     * is asked again an hour later, as any failure, not a day.
     */
    private suspend fun look(): Found {
        val saved = store.read()
        val listing = parts.releases.list(saved.etag.takeIf { saved.releases != null })
        val now = clock()
        val listed = when (listing) {
            is Listing.Fresh -> listing.releases.also { store.listed(it, listing.etag) }
            Listing.Unchanged -> saved.releases.orEmpty().also { store.listed(null) }
        }
        _state.update { it.copy(waitUntil = null) }
        val read = mutableMapOf<String, String>()
        val found = parts.choice.pick(listed, beta(), app.versionCode()) { candidate ->
            parts.releases.manifest(candidate.manifest.url).also { read[candidate.release.tag] = it }
        }
        if (found is Found.Offer) read[found.offer.tag]?.let { store.manifest(found.offer.tag, it) }
        store.answered(now)
        _state.update { it.copy(checkedAt = now) }
        return found
    }

    /** Offers [offer] (none: up to date), unless an update is under way; what the version before failed with goes with it. */
    private fun offer(offer: UpdateOffer?) {
        _state.update { s ->
            when {
                s.offer == offer -> s
                !s.idle && s.step != UpdateStep.Ready -> s
                offer == null -> s.copy(offer = null, step = UpdateStep.Idle, failure = null)
                else -> s.copy(
                    offer = offer,
                    step = if (ready?.first == offer.versionCode) UpdateStep.Ready else UpdateStep.Idle,
                    failure = s.failure.takeIf { s.offer?.versionCode == offer.versionCode },
                )
            }
        }
    }

    private suspend fun rememberWait(e: Throwable) {
        val wait = (e as? GithubStatusException)?.waitUntil ?: return
        store.waitUntil(wait)
        _state.update { it.copy(waitUntil = wait) }
    }

    /**
     * A sheet opened: it sees Android's permission given meanwhile, and asks GitHub only with the automatic check on, no
     * version offered, and an answer over an hour old.
     */
    override fun opened() {
        sheets++
        allowedMeanwhile()
        val s = state.value
        val stale = s.checkedAt?.let { clock() - it !in 0 until HOUR_MS } ?: true
        // Nothing offered nor under way, nothing failed: an hour-old answer is asked again.
        val quiet = s.offer == null && s.idle && s.failure == null
        if (s.automatic && quiet && stale) checkNow()
    }

    /** A sheet closed: an update under way goes on, and stops at Ready, Android's window never opening by itself. */
    override fun closed() {
        sheets = (sheets - 1).coerceAtLeast(0)
    }

    /**
     * « Mettre à jour » or « Installer »: the permission, the download and its check, then Android's window. The version
     * acted on is seen: home does not offer it again.
     */
    override fun update() {
        val offer = state.value.offer
        if (!state.value.on || offer == null || work?.isActive == true) return
        retriesCheck = false
        shownOnHome(offer.versionCode)
        work = scope.launch { install(offer) }
    }

    /** « Réessayer »: what failed, asked again. */
    override fun retry() = if (retriesCheck) checkNow() else update()

    /** Android's permission given meanwhile another way (adb's line, its settings): the sheet is back to « Mettre à jour ». */
    private fun allowedMeanwhile() {
        if (state.value.step is UpdateStep.NeedsPermission && parts.installer.allowed()) step(UpdateStep.Idle)
    }

    private suspend fun install(offer: UpdateOffer) {
        _state.update { it.copy(failure = null) }
        if (!parts.installer.allowed()) return askPermission()
        val apk = ready?.takeIf { it.first == offer.versionCode && it.second.isFile }?.second ?: fetch(offer) ?: return
        if (sheets > 0 && moments.onScreen.value) commit(offer, apk) else step(UpdateStep.Ready)
    }

    private fun askPermission() = step(UpdateStep.NeedsPermission(parts.installer.permissionScreen()))

    /** [offer]'s APK, downloaded then verified; null when it failed, which the state says. */
    private suspend fun fetch(offer: UpdateOffer): File? {
        step(UpdateStep.Downloading(0, offer.size))
        val file = attempt(UpdateFailure.GITHUB_FAILED) {
            parts.download.fetch(offer) { read, total ->
                _state.update { if (it.step is UpdateStep.Downloading) it.copy(step = UpdateStep.Downloading(read, total)) else it }
            }
        } ?: return null
        step(UpdateStep.Verifying)
        val verified = attempt(UpdateFailure.INSTALL_FAILED) {
            withContext(io) { parts.verifier.verify(file, offer, app.packageName, app.versionCode()) }
        }
        if (verified == null) {
            withContext(io) { file.delete() }
            return null
        }
        ready = offer.versionCode to file
        return file
    }

    /** Hands [apk] to Android: the install is written as pending first, settled at the next start should the app be closed. */
    private suspend fun commit(offer: UpdateOffer, apk: File) {
        if (!parts.installer.allowed()) return askPermission()
        step(UpdateStep.Installing())
        // A previous attempt's session, left in Android's window: abandoned for this one.
        if (session != null) parts.installer.abandonAll()
        val committed = attempt(UpdateFailure.INSTALL_FAILED, installing = true) {
            parts.installer.install(apk) { id ->
                // Before the commit: Android may answer at once.
                session = id
                store.pending(Pending(offer.versionCode, offer.versionName, clock()))
            }
        }
        if (committed == null) ended()
    }

    /** [block]'s value, or null when it failed: the state says why. */
    private suspend fun <T> attempt(otherwise: UpdateFailure, installing: Boolean = false, block: suspend () -> T): T? =
        suspendRunCatching { block() }.getOrElse { e ->
            // Cancelled meanwhile (« Annuler », or the app left the screen): not a failure.
            currentCoroutineContext().ensureActive()
            val failure = if (installing) installFailureOf(e) else failureOf(e, otherwise)
            Log.w(TAG, "Update failed: $failure", e)
            rememberWait(e)
            _state.update { it.copy(step = UpdateStep.Idle, failure = failure) }
            null
        }

    private fun step(step: UpdateStep) = _state.update { it.copy(step = step) }

    /** « Annuler le téléchargement »: what was received is deleted, the sheet back to « Mettre à jour ». */
    override fun cancel() {
        if (state.value.step !is UpdateStep.Downloading) return
        work?.cancel()
        work = null
        step(UpdateStep.Idle)
    }

    /**
     * « Autoriser l'installation »: Android's settings screen that allows it, the user's trip kept should Android close the
     * app meanwhile; without one, or should it not open, the sheet gives the other ways.
     */
    override fun allow() {
        val offer = state.value.offer ?: return
        val screen = parts.installer.permissionScreen()
        if (screen == PermissionScreen.NONE) return step(UpdateStep.NeedsPermission(screen))
        allowing = true
        scope.launch {
            store.trip(Trip(offer.versionCode, clock()))
            if (!parts.installer.open(screen)) {
                allowing = false
                store.trip(null)
                step(UpdateStep.NeedsPermission(screen, refused = true))
            }
        }
    }

    /** Android's confirmation again, which the user left with the Home key. */
    override fun reopen() {
        val intent = confirmation ?: return
        if ((state.value.step as? UpdateStep.Installing)?.reopen != true) return
        step(UpdateStep.Installing(reopen = !parts.installer.confirm(intent)))
    }

    /**
     * The app is back in front (the activity resumed): from Android's settings, or from elsewhere while Android installs
     * or once the permission was given another way.
     */
    fun returned() {
        if (!state.value.on) return
        if (allowing) {
            allowing = false
            scope.launch { store.trip(null) }
            if (parts.installer.allowed()) {
                _state.update { it.copy(step = UpdateStep.Idle, failure = null) }
                update()
            } else {
                step(UpdateStep.NeedsPermission(parts.installer.permissionScreen(), refused = true))
            }
            return
        }
        allowedMeanwhile()
        // Android's window left with the Home key: the session waits for the user, who can open it again.
        val waiting = session?.takeIf { confirmation != null }?.let(parts.installer::holds) == true
        if (state.value.step is UpdateStep.Installing && away && waiting) {
            away = false
            step(UpdateStep.Installing(reopen = true))
        }
    }

    /** Android's answer for [id]: its confirmation to open, or how the install ended ([PackageInstaller.EXTRA_STATUS]). */
    fun onSessionStatus(id: Int, status: Int, message: String?, confirm: Intent?) {
        Log.i(TAG, "Session $id: status $status${message?.let { ", " + Redact.text(it) }.orEmpty()}")
        if (id != session) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = confirm
                val shown = confirm != null && moments.onScreen.value && parts.installer.confirm(confirm)
                step(UpdateStep.Installing(reopen = confirm != null && !shown))
            }
            // Android replaces the app and closes it: its next start says so.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                ended()
                step(if (ready != null) UpdateStep.Ready else UpdateStep.Idle)
            }
            else -> {
                ended()
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) dropReady()
                retriesCheck = false
                _state.update { it.copy(step = UpdateStep.Idle, failure = sessionFailure(status)) }
            }
        }
    }

    /** The session is over without an install: nothing is pending any more. */
    private fun ended() {
        session = null
        confirmation = null
        away = false
        scope.launch { store.pending(null) }
    }

    private fun dropReady() {
        val file = ready?.second
        ready = null
        if (file != null) scope.launch { withContext(io) { file.delete() } }
    }

    /** Home opened [versionCode]'s sheet: it does not open again for it. */
    override fun shownOnHome(versionCode: Long) {
        _state.update { it.copy(seenOnHome = maxOf(it.seenOnHome, versionCode), backOnHome = false) }
        scope.launch { store.offeredOnHome(versionCode) }
    }

    /** Home said the last update: it does not say it again. */
    override fun updatedSaid() {
        val updated = state.value.updated?.takeUnless { it.said }?.copy(said = true) ?: return
        _state.update { it.copy(updated = updated) }
        scope.launch { store.updated(updated) }
    }

    /**
     * The app was just replaced (MY_PACKAGE_REPLACED): whether it updated itself in the last minutes, so reopens; from
     * what this start found pending, which it settles meanwhile.
     */
    suspend fun replaced(): Boolean {
        if (!app.available) return false
        val pending = started.await() ?: return false
        return clock() - pending.at in 0..REOPEN_MS
    }

    private companion object {
        const val TAG = "Updates"
        const val HOUR_MS = 60 * 60_000L
        const val DAY_MS = 24 * HOUR_MS
        const val FIRST_CHECK_MS = 20_000L
        const val SINGLE_FLIGHT_MS = 60_000L
        const val AUTOMATIC_TRIES = 3

        /** A trip to Android's settings older than this is not taken up again at the next start. */
        const val TRIP_MS = HOUR_MS

        /** An update this recent reopens the app once replaced. */
        const val REOPEN_MS = 15 * 60_000L
    }
}

/** What failed while asking GitHub or downloading, [otherwise] when nothing names it. */
internal fun failureOf(e: Throwable, otherwise: UpdateFailure): UpdateFailure = when (e) {
    is UpdateException -> e.failure
    is GithubStatusException -> if (e.waitUntil != null) UpdateFailure.BUSY else UpdateFailure.GITHUB_FAILED
    is RefusedHopException, is TooLargeException -> UpdateFailure.GITHUB_FAILED
    else -> when (e.failureKind()) {
        FailureKind.UNREACHABLE, FailureKind.IO -> UpdateFailure.OFFLINE
        FailureKind.CERTIFICATE -> UpdateFailure.CERTIFICATE
        FailureKind.STORAGE_FULL -> UpdateFailure.STORAGE
        FailureKind.UNEXPECTED_ANSWER -> UpdateFailure.UNREADABLE
        else -> otherwise
    }
}

/** What failed while handing the APK to Android: the device full, else Android's refusal. */
internal fun installFailureOf(e: Throwable): UpdateFailure =
    if (e.failureKind() == FailureKind.STORAGE_FULL) UpdateFailure.STORAGE else UpdateFailure.INSTALL_FAILED

/** How Android ended a session other than by installing ([PackageInstaller.EXTRA_STATUS]). */
internal fun sessionFailure(status: Int): UpdateFailure = when (status) {
    PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateFailure.BLOCKED
    PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateFailure.CONFLICT
    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFailure.STORAGE
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateFailure.INCOMPATIBLE
    else -> UpdateFailure.INSTALL_FAILED
}
