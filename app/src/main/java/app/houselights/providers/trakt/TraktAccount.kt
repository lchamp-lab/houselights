package app.houselights.providers.trakt

import android.os.SystemClock
import android.util.Log
import app.houselights.core.Http
import app.houselights.core.HttpStatus
import app.houselights.core.TraktSession
import app.houselights.core.suspendRunCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** How a device code ended: approved (the account is connected), or why not. */
enum class DeviceCodeEnd { APPROVED, DENIED, EXPIRED, INVALID, USED }

/** No Trakt account is connected, or Trakt ended its connection: it must be connected again. */
class TraktSignedOutException : IllegalStateException("No Trakt account connected")

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
 * signing out. Its session lives in the settings, sealed: read with [session], written with [save].
 */
class TraktAccount(
    private val keys: TraktKeys,
    private val session: () -> TraktSession?,
    private val save: suspend (TraktSession?) -> Unit,
    http: OkHttpClient = Http.client,
    /** Seconds since the epoch, which tokens' lives count in. */
    private val now: () -> Long = { System.currentTimeMillis() / MS_PER_S },
    /** A clock in milliseconds that runs on in sleep, which a code's life counts in. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : TraktLink {
    private val client by lazy { TraktClient(keys, token = { session()?.accessToken }, base = http) }

    /** Renewing spends the refresh token: one renewal at a time. */
    private val renewing = Mutex()

    override val available: Boolean get() = keys.available

    override suspend fun deviceCode(): DeviceCode = client.auth.deviceCode(DeviceCodeRequest(keys.clientId))

    /**
     * Polls for [code]'s tokens at its interval until it ends; once approved, saves the session and its name. A poll
     * that fails (no network, Trakt busy) is tried again at the next interval; if the code expires meanwhile, that
     * failure is thrown.
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

    private suspend fun signedIn(tokens: TraktTokens) {
        save(TraktSession(tokens.accessToken, tokens.refreshToken, now() + tokens.expiresIn))
        readName()
    }

    /** The name stays unknown when Trakt cannot say it, to be read later; a failure to save it is thrown. */
    override suspend fun readName(): String? = suspendRunCatching { call { it.settings() }.user.username }
        .onFailure { Log.w(TAG, "The Trakt account's name could not be read", it) }
        .getOrNull()
        ?.also { name -> session()?.takeIf { it.username != name }?.let { save(it.copy(username = name)) } }

    /**
     * Runs [block] on the account's API with a live access token: renewed when it is about to end, and once more
     * if Trakt refuses it. Throws [TraktSignedOutException] when there is no account, or Trakt refuses to renew it.
     */
    suspend fun <T> call(block: suspend (TraktApi) -> T): T {
        var current = live()
        if (current.expiresAt - now() < RENEW_BEFORE_S) {
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

    private fun live(): TraktSession = session()?.takeUnless { it.ended } ?: throw TraktSignedOutException()

    /** New tokens for [stale], saved; those of a renewal made meanwhile, whose refresh token [stale] no longer is. */
    private suspend fun renew(stale: TraktSession): TraktSession = renewing.withLock {
        val current = live()
        if (current.refreshToken != stale.refreshToken) return@withLock current
        val tokens = try {
            client.auth.refresh(RefreshRequest(current.refreshToken, keys.clientId, keys.clientSecret, TraktClient.REDIRECT_URI))
        } catch (e: HttpException) {
            if (e.code() !in REFUSED) throw e
            Log.w(TAG, "Trakt refused to renew the account's tokens (${e.code()})")
            save(current.copy(ended = true))
            throw TraktSignedOutException()
        }
        current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken, expiresAt = now() + tokens.expiresIn)
            .also { save(it) }
    }

    /** Revokes the access token on Trakt, giving up after a short wait, then forgets the session. */
    override suspend fun signOut() {
        val current = session() ?: return
        withTimeoutOrNull(SIGN_OUT_MS) {
            try {
                client.auth.revoke(RevokeRequest(current.accessToken, keys.clientId, keys.clientSecret))
            } catch (_: IOException) {
                // The token stays valid until it ends; it can be revoked from Trakt's settings.
            } catch (_: HttpException) {
                // Already revoked.
            }
        }
        save(null)
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

        /** A refresh token Trakt no longer takes (invalid_grant), or an app it no longer lets in. */
        val REFUSED = setOf(BAD_REQUEST, HttpStatus.UNAUTHORIZED)

        /** Access tokens last seven days; renewed in their last. */
        const val RENEW_BEFORE_S = 24 * 60 * 60L

        const val SIGN_OUT_MS = 3_000L
    }
}
