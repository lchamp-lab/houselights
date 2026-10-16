package app.houselights.ui.util

import android.database.sqlite.SQLiteFullException
import app.houselights.core.SecretsUnavailableException
import app.houselights.providers.local.TmdbUnreachableException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException

/** A write launched from a screen: its failure is said, never a crash; its cancellation is no failure. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActionsTest {
    @Test
    fun aFailedWriteIsSaidNeverThrown() = runTest {
        val said = mutableListOf<UiError>()
        launchAction("Test", "Writing", onError = { said += it }, errorOf = Throwable::toWriteError) {
            throw IOException("No space left on device")
        }
        advanceUntilIdle()
        assertEquals(listOf(UiError.NOT_SAVED), said)
    }

    @Test
    fun aCancelledWriteSaysNothing() = runTest {
        val said = mutableListOf<UiError>()
        val job = launchAction("Test", "Writing", onError = { said += it }) { awaitCancellation() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertEquals(emptyList<UiError>(), said)
    }

    /** The device's own storage is not the network: a full disk never reads as the NAS gone. */
    @Test
    fun aWriteToTheDeviceSaysWhatFailed() {
        assertEquals(UiError.NOT_SAVED, IOException("ENOSPC").toWriteError())
        assertEquals(UiError.NOT_SAVED, SQLiteFullException("database or disk is full").toWriteError())
        assertEquals(UiError.KEYSTORE, SecretsUnavailableException(ProviderException()).toWriteError())
        // TMDB asked on the way is still TMDB.
        assertEquals(UiError.TMDB_OFFLINE, TmdbUnreachableException(IOException("timeout")).toWriteError())
    }
}
