package app.houselights.ui.testing

import app.houselights.providers.trakt.DeviceCode
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.TraktLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** The code Trakt gives: ABCD1234, for ten minutes. */
val traktCode = DeviceCode("dev-c0de", "ABCD1234", "https://trakt.tv/activate", expiresIn = 600, interval = 5)

/**
 * Trakt as a test drives it: [codeFailure] fails the code's request; each code waits until [end] says how it ended,
 * [onApproved] standing for the session Trakt's tokens make.
 */
class FakeTraktLink(override val available: Boolean = true, private val onApproved: () -> Unit = {}) : TraktLink {
    var codeFailure: Exception? = null
    var signOutFailure: Exception? = null
    var onSignOut: () -> Unit = {}

    var codesAsked = 0
        private set
    var cancelled = 0
        private set
    var signedOut = 0
        private set
    var namesRead = 0
        private set

    private var ending = CompletableDeferred<DeviceCodeEnd>()

    /** True while a code waits for the viewer. */
    var waiting = false
        private set

    override suspend fun deviceCode(): DeviceCode {
        codesAsked++
        codeFailure?.let { throw it }
        return traktCode
    }

    override suspend fun awaitApproval(code: DeviceCode): DeviceCodeEnd {
        ending = CompletableDeferred()
        waiting = true
        return try {
            ending.await().also { if (it == DeviceCodeEnd.APPROVED) onApproved() }
        } catch (e: CancellationException) {
            cancelled++
            throw e
        } finally {
            waiting = false
        }
    }

    /** Ends the code waiting, as the viewer or Trakt did. */
    fun end(how: DeviceCodeEnd) {
        ending.complete(how)
    }

    /** Fails the code waiting, as the network does. */
    fun fail(e: Exception) {
        ending.completeExceptionally(e)
    }

    override suspend fun readName(): String? {
        namesRead++
        return "lea"
    }

    override suspend fun signOut() {
        signedOut++
        signOutFailure?.let { throw it }
        onSignOut()
    }
}
