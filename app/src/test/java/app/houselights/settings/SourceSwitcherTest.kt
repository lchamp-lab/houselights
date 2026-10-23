package app.houselights.settings

import app.houselights.core.SecretsUnavailableException
import app.houselights.core.SourceConfig
import app.houselights.testing.http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.ProviderException

/**
 * Switching the library source: the new one saved first; a server left for the folders kept signed in, one at most;
 * a token revoked only once nothing uses or keeps it.
 */
@RunWith(RobolectricTestRunner::class)
class SourceSwitcherTest {
    private val home = SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "old-token")
    private val office = SourceConfig.Jellyfin("https://office:8920", "Bureau", "u2", "lea", "office-token")

    /** What the switcher did, in order. */
    private val done = mutableListOf<String>()

    private fun switcher(
        from: SourceConfig?,
        scope: CoroutineScope,
        kept: SourceConfig.Jellyfin? = null,
        check: suspend (SourceConfig.Jellyfin) -> Unit = { done += "check ${it.accessToken}" },
        save: suspend (SourceConfig, SourceConfig.Jellyfin?) -> Unit = { source, keeps ->
            done += "save ${source.name()}" + keeps?.let { ", keep ${it.accessToken}" }.orEmpty()
        },
    ) = SourceSwitcher(
        current = { AppSettings(loaded = true, source = from, keptServer = kept, deviceId = "device") },
        save = save,
        scope = scope,
        signOut = { server, deviceId -> done += "sign out ${server.accessToken} on $deviceId" },
        check = { server, _ -> check(server) },
    )

    private fun SourceConfig.name() = when (this) {
        SourceConfig.Local -> "folders"
        is SourceConfig.Jellyfin -> accessToken
    }

    /** Switches, then lets what the switch started in the background finish. */
    private suspend fun TestScope.switch(from: SourceConfig?, to: SourceConfig, kept: SourceConfig.Jellyfin? = null, keep: Boolean = true) {
        switcher(from, this, kept).switchTo(to, keep)
        advanceUntilIdle()
    }

    @Test
    fun aServerLeftForTheFoldersStaysSignedInKept() = runTest {
        switch(from = home, to = SourceConfig.Local)
        assertEquals(listOf("save folders, keep old-token"), done)
    }

    /** Trakt, which keeps the folders' history, is no business of the switch: it pauses under a server, connected. */
    @Test
    fun theFoldersLeftForAServerOnlySaveIt() = runTest {
        switch(from = SourceConfig.Local, to = home)
        assertEquals(listOf("save old-token"), done)
    }

    @Test
    fun theKeptServerResumedIsNeverSignedOut() = runTest {
        switch(from = SourceConfig.Local, to = home, kept = home)
        assertEquals(listOf("save old-token"), done)
    }

    @Test
    fun signingOutSavesTheFoldersThenRevokesTheToken() = runTest {
        switch(from = home, to = SourceConfig.Local, keep = false)
        assertEquals(listOf("save folders", "sign out old-token on device"), done)
    }

    @Test
    fun anotherServerSignedInReplacesTheKeptOneAndRevokesIt() = runTest {
        switch(from = SourceConfig.Local, to = office, kept = home)
        assertEquals(listOf("save office-token", "sign out old-token on device"), done)
    }

    @Test
    fun anotherTokenOnTheSameServerRevokesTheOldOne() = runTest {
        switch(from = home, to = home.copy(accessToken = "new-token"))
        assertEquals(listOf("save new-token", "sign out old-token on device"), done)
    }

    @Test
    fun theSameTokenHandedBackStaysValid() = runTest {
        switch(from = home, to = home.copy(userName = "Lea"))
        assertEquals(listOf("save old-token"), done)
    }

    @Test
    fun theFoldersKeptOrChosenRevokeNothing() = runTest {
        switch(from = SourceConfig.Local, to = SourceConfig.Local, kept = home)
        switch(from = null, to = SourceConfig.Local)
        switch(from = null, to = home)
        assertEquals(listOf("save folders, keep old-token", "save folders", "save old-token"), done)
    }

    @Test
    fun aSourceThatCannotBeSavedLeavesEveryServerSignedIn() = runTest {
        val full = IOException("disk full")
        val failing = switcher(home, this) { _, _ -> throw full }
        assertSame(full, runCatching { failing.switchTo(SourceConfig.Local, keepServer = false) }.exceptionOrNull())
        runCatching { switcher(SourceConfig.Local, this, kept = home) { _, _ -> throw full }.switchTo(office) }
        advanceUntilIdle()
        assertEquals(emptyList<String>(), done)
    }

    /** The folders never wait on the Keystore: a server it cannot seal to keep is signed out of instead. */
    @Test
    fun aServerTheKeystoreCannotKeepIsSignedOutOf() = runTest {
        val keystore = SecretsUnavailableException(ProviderException("Keystore operation failed"))
        val sealing = switcher(home, this) { source, keeps ->
            if (keeps != null) throw keystore
            done += "save ${source.name()}"
        }
        sealing.switchTo(SourceConfig.Local)
        advanceUntilIdle()
        assertEquals(listOf("save folders", "sign out old-token on device"), done)
    }

    @Test
    fun forgettingTheKeptServerRevokesItOnceForgotten() = runTest {
        switcher(SourceConfig.Local, this, kept = home).forget()
        advanceUntilIdle()
        assertEquals(listOf("save folders", "sign out old-token on device"), done)
    }

    @Test
    fun resumingAsksTheServerFirstThenMakesItTheSource() = runTest {
        assertEquals(Resumed.Done, switcher(SourceConfig.Local, this, kept = home).resume(home))
        advanceUntilIdle()
        assertEquals(listOf("check old-token", "save old-token"), done)
    }

    /** A token the server refuses is no longer kept, and never sent again, not even to revoke it. */
    @Test
    fun aRefusedTokenIsForgottenAndTheSignInAsked() = runTest {
        val refusing = switcher(SourceConfig.Local, this, kept = home, check = { throw http(401) })
        assertEquals(Resumed.Refused(home), refusing.resume(home))
        advanceUntilIdle()
        assertEquals(listOf("save folders"), done)
    }

    @Test
    fun aServerForgottenMeanwhileIsNeverResumed() = runTest {
        assertEquals(Resumed.Refused(home), switcher(SourceConfig.Local, this, kept = null).resume(home))
        assertEquals(emptyList<String>(), done)
    }

    /** A sleeping server says so where the choice was made: nothing changes, and it stays kept. */
    @Test
    fun aServerThatDoesNotAnswerStaysKept() = runTest {
        val silent = IOException("Connection timed out")
        val sleeping = switcher(SourceConfig.Local, this, kept = home, check = { throw silent })
        assertSame(silent, runCatching { sleeping.resume(home) }.exceptionOrNull())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), done)
    }
}
