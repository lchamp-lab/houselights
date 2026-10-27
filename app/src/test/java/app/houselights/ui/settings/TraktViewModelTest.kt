package app.houselights.ui.settings

import app.houselights.R
import app.houselights.core.SecretsUnavailableException
import app.houselights.providers.local.TraktSyncError
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.TraktSignedOutException
import app.houselights.testing.http
import app.houselights.ui.testing.FakeTraktLink
import app.houselights.ui.testing.FakeTraktSync
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.util.UiError
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException
import java.security.ProviderException
import javax.net.ssl.SSLPeerUnverifiedException

/** Connecting Trakt: the code and its time left, how it ended, Back, and disconnecting. */
@RunWith(RobolectricTestRunner::class)
class TraktViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val link = FakeTraktLink()
    private val sync = FakeTraktSync()

    private fun TestScope.model() = TraktViewModel(link, sync, backgroundScope, clock = { testScheduler.currentTime })

    @Test
    fun theCodeShowsWithItsTimeLeftUntilApproved() = runTest {
        val vm = model()
        vm.connect()
        assertTrue(vm.connecting)
        runCurrent()
        // Trakt's page as it names it.
        assertEquals(ShownCode("ABCD1234", 600_000, "trakt.tv/activate"), vm.code)
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(539_000L, vm.code?.leftMs)
        // Read in whole minutes, rounded up.
        assertEquals(540_000L, vm.code?.leftMinutesMs)

        link.end(DeviceCodeEnd.APPROVED)
        runCurrent()
        assertFalse(vm.connecting)
        assertNull(vm.code)
        assertNull(vm.note)
    }

    @Test
    fun howACodeEndedIsSaid() = runTest {
        val notes = mapOf(
            DeviceCodeEnd.DENIED to R.string.trakt_denied,
            DeviceCodeEnd.EXPIRED to R.string.trakt_expired,
            DeviceCodeEnd.USED to R.string.trakt_failed,
            DeviceCodeEnd.INVALID to R.string.trakt_failed,
        )
        val vm = model()
        notes.forEach { (end, note) ->
            vm.connect()
            runCurrent()
            link.end(end)
            runCurrent()
            assertEquals("$end", note, vm.note)
            assertFalse(vm.connecting)
        }
    }

    @Test
    fun aFailureIsSaidByWhatFailed() = runTest {
        val failures = mapOf(
            UnknownHostException("auth.trakt.tv") to R.string.trakt_offline,
            SSLPeerUnverifiedException("auth.trakt.tv") to R.string.trakt_certificate,
            http(403) to R.string.trakt_refused,
            http(503) to R.string.trakt_failed,
            SecretsUnavailableException(ProviderException("Keystore")) to UiError.KEYSTORE.message,
            IOException("No space left on device") to UiError.NOT_SAVED.message,
            TraktSignedOutException() to R.string.trakt_ended,
        )
        val vm = model()
        failures.forEach { (failure, note) ->
            vm.connect()
            runCurrent()
            link.fail(failure)
            runCurrent()
            assertEquals("$failure", note, vm.note)
        }
        // Asking for the code fails the same way.
        link.codeFailure = UnknownHostException("auth.trakt.tv")
        vm.connect()
        runCurrent()
        assertEquals(R.string.trakt_offline, vm.note)
        assertFalse(vm.connecting)
    }

    @Test
    fun backStopsWaitingAndSaysNothing() = runTest {
        val vm = model()
        vm.connect()
        runCurrent()
        assertTrue(link.waiting)
        vm.cancel()
        runCurrent()
        assertEquals(1, link.cancelled)
        assertFalse(vm.connecting)
        assertNull(vm.code)
        assertNull(vm.note)
    }

    @Test
    fun oneCodeAtATime() = runTest {
        val vm = model()
        assertTrue(vm.connect())
        runCurrent()
        assertFalse(vm.connect())
        runCurrent()
        assertEquals(1, link.codesAsked)
        // The countdown runs while the code waits: virtual time would never end.
        vm.cancel()
    }

    @Test
    fun aNewAttemptClearsTheLastFailure() = runTest {
        val vm = model()
        vm.connect()
        runCurrent()
        link.end(DeviceCodeEnd.DENIED)
        runCurrent()
        vm.connect()
        assertNull(vm.note)
        vm.cancel()
    }

    @Test
    fun disconnectingForgetsTheAccountOrSaysWhyNot() = runTest {
        val vm = model()
        vm.disconnect()
        assertTrue(vm.disconnecting)
        runCurrent()
        assertEquals(1, link.signedOut)
        assertFalse(vm.disconnecting)
        assertNull(vm.note)

        link.signOutFailure = IOException("No space left on device")
        vm.disconnect()
        runCurrent()
        assertEquals(UiError.NOT_SAVED.message, vm.note)
    }

    @Test
    fun syncingAsksOnceWhileASyncRuns() = runTest {
        val vm = model()
        vm.syncNow()
        vm.syncNow()
        assertEquals(1, sync.asked)
        assertTrue(vm.syncState.value.running)
    }

    @Test
    fun aFailedExchangeIsSaidByWhatFailed() {
        val notes = mapOf(
            TraktSyncError.OFFLINE to R.string.trakt_sync_offline,
            TraktSyncError.BUSY to R.string.trakt_sync_busy,
            TraktSyncError.REFUSED to R.string.trakt_sync_refused,
            TraktSyncError.CERTIFICATE to R.string.trakt_certificate,
            TraktSyncError.UNREADABLE to R.string.trakt_sync_unreadable,
            TraktSyncError.FAILED to R.string.trakt_sync_failed,
            TraktSyncError.STORAGE to UiError.NOT_SAVED.message,
        )
        notes.forEach { (error, note) -> assertEquals("$error", note, noteOf(error)) }
    }

    @Test
    fun aMinuteLeftReadsAsOneUntilTheEnd() {
        // Its last poll too.
        assertEquals(60_000L, ShownCode("A", 0).leftMinutesMs)
        assertEquals(60_000L, ShownCode("A", 1).leftMinutesMs)
        assertEquals(60_000L, ShownCode("A", 60_000).leftMinutesMs)
        assertEquals(120_000L, ShownCode("A", 60_001).leftMinutesMs)
    }
}
