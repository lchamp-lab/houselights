package app.houselights.settings

import app.houselights.core.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/** Switching the library source: the new one saved first, then the old server's token revoked if no longer in use. */
class SourceSwitcherTest {
    private val home = SourceConfig.Jellyfin("https://nas:8920", "Maison", "u1", "lea", "old-token")

    /** What the switcher did, in order. */
    private val done = mutableListOf<String>()

    private fun switcher(
        from: SourceConfig?,
        scope: CoroutineScope,
        save: suspend (SourceConfig) -> Unit = { done += "save ${it.name()}" },
    ) = SourceSwitcher(
        current = { AppSettings(loaded = true, source = from, deviceId = "device") },
        save = save,
        scope = scope,
        signOut = { server, deviceId -> done += "sign out ${server.accessToken} on $deviceId" },
    )

    private fun SourceConfig.name() = when (this) {
        SourceConfig.Local -> "folders"
        is SourceConfig.Jellyfin -> accessToken
    }

    /** Switches, then lets what the switch started in the background finish. */
    private suspend fun TestScope.switch(from: SourceConfig?, to: SourceConfig) {
        switcher(from, this).switchTo(to)
        advanceUntilIdle()
    }

    @Test
    fun theNewSourceIsSavedBeforeTheServerForgetsTheOldToken() = runTest {
        switch(from = home, to = SourceConfig.Local)
        assertEquals(listOf("save folders", "sign out old-token on device"), done)
    }

    @Test
    fun aSourceThatCannotBeSavedLeavesTheServerSignedIn() = runTest {
        val full = IOException("disk full")
        val failing = switcher(home, this) { throw full }
        assertSame(full, runCatching { failing.switchTo(SourceConfig.Local) }.exceptionOrNull())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), done)
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
    fun leavingFoldersOrNothingSignsNothingOut() = runTest {
        switch(from = SourceConfig.Local, to = home)
        switch(from = null, to = SourceConfig.Local)
        assertEquals(listOf("save old-token", "save folders"), done)
    }
}
