package app.houselights.providers.trakt

import android.os.SystemClock
import android.util.Log
import app.houselights.core.Http
import app.houselights.core.HttpStatus
import app.houselights.core.TraktSession
import app.houselights.core.serverJson
import app.houselights.core.suspendRunCatching
import app.houselights.core.unreadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** How a device code ended: approved (the account is connected), or why not. */
enum class DeviceCodeEnd { APPROVED, DENIED, EXPIRED, INVALID, USED }

/** No Trakt account is connected, or Trakt ended its connection: it must be connected again. */
class TraktSignedOutException : IllegalStateException("No Trakt account connected")

/**
 * A failure for the log: its status, where an answer could not be read, or its type; never its message, which may
 * quote an answer and its tokens.
 */
internal fun described(e: Throwable): String = when (e) {
    is HttpException -> "HTTP ${e.code()}"
    // Its type alone would be renamed in a release build.
    is SerializationException -> unreadable(e)
    else -> e.javaClass.simpleName
}

/** What connecting and disconnecting an account takes ([TraktAccount]); an interface so tests can stand in for Trakt. */
interface TraktLink {
    /** Whether this build has the keys to reach Trakt. */
    val available: Boolean

    /** New codes for the viewer to activate. */
    suspend fun deviceCode(): DeviceCode

    /** Waits for [code] to end; once approved, the account is connected. */
    suspend fun awaitApproval(code: DeviceCode): DeviceCodeEnd

    /** Reads the account's name into its session; null when Trakt cannot say it now. */
    suspend fun readName(): String?

    /** Disconnects the account: Trakt forgets the device, the device forgets the account. */
    suspend fun signOut()
}

/**
 * The Trakt account of this device: connected with a device code, its tokens renewed before they end, revoked on
 * signing out. Its session lives in the settings, sealed: read with [session] until this account changes it, then
 * held in memory, which every call and its token read, and written with [save]; if that failed, again soon in
 * [retries], as the app leaves the screen, and at the next call.
 */
class TraktAccount(
    private val keys: TraktKeys,
    private val session: () -> TraktSession?,
    private val save: suspend (TraktSession?) -> Unit,
    /** The client its calls derive theirs from; null for the app's ([Http.client]), not built before the first call. */
    http: OkHttpClient? = null,
    /** Seconds since the epoch, which tokens' lives count in. */
    private val now: () -> Long = { System.currentTimeMillis() / MS_PER_S },
    /** A clock in milliseconds that runs on in sleep, which a code's life counts in. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Where a failed save is tried again ([RETRIES_MS]); without it, only at the next call. */
    private val retries: CoroutineScope? = null,
    /** Whether the app is on screen: leaving it, the process may be stopped, and a session held only in memory lost. */
    started: Flow<Boolean> = emptyFlow(),
) : TraktLink {
    private val client by lazy { TraktClient(keys, token = { current()?.accessToken }, base = http ?: Http.client) }

    /** Every change of the session, one at a time: renewing spends the refresh token. */
    private val lock = Mutex()

    /** The session as this account last made it, [saved] or not yet; null until it makes one. */
    private class Held(val session: TraktSession?, val saved: Boolean)

    @Volatile
    private var held: Held? = null

    /** The save tried again after a failure, while it waits. */
    private var resaving: Job? = null

    init {
        retries?.launch { started.distinctUntilChanged().filter { !it }.collect { savePending() } }
    }

    /** The session in memory, else the settings'. */
    private fun current(): TraktSession? = held.let { if (it != null) it.session else session() }

    override val available: Boolean get() = keys.available

    override suspend fun deviceCode(): DeviceCode = client.auth.deviceCode(DeviceCodeRequest(keys.clientId))

    /**
     * Polls for [code]'s tokens at its interval until it ends; once approved, saves the session, its name read
     * later ([readName]). A poll that fails (no network, Trakt busy) is tried again at the next interval; if the
     * code expires meanwhile, that failure is thrown, as is a status the flow does not know.
     */
    override suspend fun awaitApproval(code: DeviceCode): DeviceCodeEnd {
        val ends = clock() + code.expiresIn * MS_PER_S
        var interval = code.interval.coerceAtLeast(1) * MS_PER_S
        var failed: Exception? = null
        var end: DeviceCodeEnd? = null
        // Past its end, a code answers EXPIRED: the last poll may follow it.
        while (end == null && clock() < ends) {
            delay(interval)
            val answer = try {
                client.auth.deviceToken(DeviceTokenRequest(code.deviceCode, keys.clientId, keys.clientSecret))
            } catch (e: IOException) {
                failed = e
                continue
            }
            failed = null
            end = endOf(answer)
            when (answer.code()) {
                HttpStatus.OK -> signedIn(checkNotNull(answer.body()) { "Trakt approved the code without tokens" })
                SLOW_DOWN -> interval += SLOW_DOWN_MS
                in BUSY -> failed = HttpException(answer)
            }
        }
        failed?.let { throw it }
        return end ?: DeviceCodeEnd.EXPIRED
    }

    /** How [answer] ends a code; null while it may still be approved. A status the flow does not know is thrown. */
    private fun endOf(answer: Response<*>): DeviceCodeEnd? = when (answer.code()) {
        HttpStatus.OK -> DeviceCodeEnd.APPROVED
        PENDING, SLOW_DOWN, in BUSY -> null
        HttpStatus.NOT_FOUND -> DeviceCodeEnd.INVALID
        USED -> DeviceCodeEnd.USED
        EXPIRED -> DeviceCodeEnd.EXPIRED
        DENIED -> DeviceCodeEnd.DENIED
        else -> throw HttpException(answer)
    }

    /** The new session, saved; if it cannot be, the account stays as it was and the failure is thrown. */
    private suspend fun signedIn(tokens: TraktTokens) = lock.withLock {
        val before = held
        val next = TraktSession(tokens.accessToken, tokens.refreshToken, now() + tokens.expiresIn, tokens.expiresIn)
        keep(next).onFailure { held = before }.getOrThrow()
    }

    /** The name stays unknown when Trakt cannot say it, to be read later; a failure to save it is thrown. */
    override suspend fun readName(): String? {
        val name = suspendRunCatching { call { it.settings() }.user.username }
            .onFailure { Log.w(TAG, "The Trakt account's name could not be read: ${described(it)}") }
            .getOrNull() ?: return null
        lock.withLock {
            current()?.takeIf { !it.ended && it.username != name }?.let { keep(it.copy(username = name)).getOrThrow() }
        }
        return name
    }

    /**
     * Runs [block] on the account's API with a live access token: renewed when it is about to end, and once more
     * if Trakt refuses it. Throws [TraktSignedOutException] when there is no account, or Trakt refuses to renew it.
     */
    suspend fun <T> call(block: suspend (TraktApi) -> T): T {
        if (held?.saved == false) savePending()
        var current = live()
        if (current.expiresAt - now() < current.lifetime / RENEW_PART) {
            current = suspendRunCatching { renew(current) }.getOrElse { e ->
                // Not renewed for now (no network, Trakt busy): the token still works until it ends.
                if (e is TraktSignedOutException || current.expiresAt <= now()) throw e
                current
            }
        }
        return try {
            block(client.api)
        } catch (e: HttpException) {
            if (e.code() != HttpStatus.UNAUTHORIZED) throw e
            renew(current)
            block(client.api)
        }
    }

    private fun live(): TraktSession = current()?.takeUnless { it.ended } ?: throw TraktSignedOutException()

    /**
     * New tokens for [stale], kept; or those of a renewal made meanwhile, which spent [stale]'s refresh token. Only a
     * refresh token Trakt refuses (invalid_grant) ends the connection; any other refusal is thrown as it is.
     */
    private suspend fun renew(stale: TraktSession): TraktSession = lock.withLock {
        val current = live()
        if (current.refreshToken != stale.refreshToken) return@withLock current
        val tokens = try {
            client.auth.refresh(RefreshRequest(current.refreshToken, keys.clientId, keys.clientSecret, keys.redirectUri))
        } catch (e: HttpException) {
            if (!grantRefused(e)) throw e
            Log.w(TAG, "Trakt refuses the account's refresh token (HTTP ${e.code()})")
            ended(current)
            throw TraktSignedOutException()
        }
        // Trakt has spent the old refresh token: the new one is kept in memory even if it cannot be saved yet.
        current.copy(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAt = now() + tokens.expiresIn,
            lifetime = tokens.expiresIn,
        ).also { keep(it) }
    }

    /** Trakt ended the connection: its access token is revoked, and only the fact is kept. */
    private suspend fun ended(current: TraktSession) {
        revoke(current.accessToken)
        keep(current.copy(accessToken = "", refreshToken = "", ended = true))
    }

    /**
     * Revokes the access token on Trakt, giving up after a short wait, then forgets the session; one the Keystore could
     * not open, read as none, is forgotten all the same.
     */
    override suspend fun signOut() = lock.withLock {
        current()?.let { revoke(it.accessToken) }
        keep(null).getOrThrow()
    }

    /** Revokes [token], waiting [SIGN_OUT_MS] at most, in real time whatever the caller's clock. */
    private suspend fun revoke(token: String) {
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(SIGN_OUT_MS) {
                try {
                    client.auth.revoke(RevokeRequest(token, keys.clientId, keys.clientSecret))
                } catch (_: IOException) {
                    // The token stays valid until it ends; it can be revoked from Trakt's settings.
                } catch (_: HttpException) {
                    // Already revoked.
                }
            }
        }
    }

    /** Makes [next] the session, then saves it; a failed save is logged, kept in memory and tried again. */
    private suspend fun keep(next: TraktSession?): Result<Unit> {
        held = Held(next, saved = false)
        return suspendRunCatching { save(next) }
            .onSuccess { if (held?.session === next) held = Held(next, saved = true) }
            .onFailure {
                Log.w(TAG, "The Trakt session could not be saved: ${described(it)}")
                saveSoon()
            }
    }

    /**
     * Tries the save again after each of [RETRIES_MS] until it is made: once Trakt has spent the old refresh token,
     * a stopped process would lose the new one, and the account with it. Called under [lock], as [keep] is.
     */
    private fun saveSoon() {
        val scope = retries ?: return
        if (resaving?.isActive == true) return
        resaving = scope.launch {
            for (wait in RETRIES_MS) {
                delay(wait)
                if (savePending()) return@launch
            }
        }
    }

    /** Saves the session held in memory if it is not yet; true once none waits. */
    private suspend fun savePending(): Boolean = lock.withLock {
        val pending = held?.takeUnless { it.saved } ?: return@withLock true
        keep(pending.session).isSuccess
    }

    /** Trakt refuses the refresh token itself (invalid_grant), not the request. */
    private fun grantRefused(e: HttpException): Boolean {
        if (e.code() != BAD_REQUEST && e.code() != HttpStatus.UNAUTHORIZED) return false
        val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull() ?: return false
        return runCatching { serverJson.decodeFromString<OAuthError>(body).error }.getOrNull() == INVALID_GRANT
    }

    private companion object {
        const val TAG = "Trakt"
        const val MS_PER_S = 1_000L

        const val BAD_REQUEST = 400

        /** The device token's statuses: waiting for the viewer, already used, expired, denied, polling too fast. */
        const val PENDING = BAD_REQUEST
        const val USED = 409
        const val EXPIRED = 410
        const val DENIED = 418
        const val SLOW_DOWN = HttpStatus.TOO_MANY_REQUESTS

        /** What Trakt adds to the interval when asked to slow down, as OAuth's device flow does. */
        const val SLOW_DOWN_MS = 5_000L

        /** Trakt overloaded or behind Cloudflare: try again. */
        val BUSY = 500..599

        const val INVALID_GRANT = "invalid_grant"

        /** A token is renewed once less than this part of its life is left: the last 6 h of Trakt's 24. */
        const val RENEW_PART = 4

        const val SIGN_OUT_MS = 3_000L

        /** The waits before each new try of a failed save: 5 s, 30 s, 2 min. */
        val RETRIES_MS = longArrayOf(5_000L, 30_000L, 120_000L)
    }
}
