package app.houselights.ui.settings

import app.houselights.R
import app.houselights.core.SecretsUnavailableException
import app.houselights.providers.trakt.DeviceCodeEnd
import app.houselights.providers.trakt.TraktSignedOutException
import app.houselights.testing.http
import app.houselights.ui.testing.FakeTraktLink
import app.houselights.ui.testing.MainDispatcherRule
import app.houselights.ui.testing.SettleMainLooper
import app.houselights.ui.util.UiError
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Connecting Trakt: the code and its time left, how it ended, Back, and disconnecting. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TraktViewModelTest {
    @get:Rule
    val settle = SettleMainLooper()

    @get:Rule
    val main = MainDispatcherRule()

    private val link = FakeTraktLink()

    private fun TestScope.model() = TraktViewModel(link, backgroundScope, clock = { testScheduler.currentTime })

    @Test
    fun theCodeShowsWithItsTimeLeftUntilApproved() = runTest {
        val vm = model()
        vm.connect()
        assertTrue(vm.connecting)
        runCurrent()
        assertEquals(ShownCode("ABCD1234", 600_000), vm.code)
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
        vm.connect()
        runCurrent()
        vm.connect()
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
    fun aMinuteLeftReadsAsOneUntilTheEnd() {
        assertEquals(0L, ShownCode("A", 0).leftMinutesMs)
        assertEquals(60_000L, ShownCode("A", 1).leftMinutesMs)
        assertEquals(60_000L, ShownCode("A", 60_000).leftMinutesMs)
        assertEquals(120_000L, ShownCode("A", 60_001).leftMinutesMs)
    }
}
