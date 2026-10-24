package app.houselights

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.PlaybackPlan
import app.houselights.player.DeviceIdentity
import app.houselights.player.FakeApps
import app.houselights.player.FakeApps.Companion.GALLERY
import app.houselights.player.FakeApps.Companion.MOVIE_ACTIVITY
import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_BUILD
import app.houselights.player.PlaybackManager
import app.houselights.player.PlayerAnswer
import app.houselights.player.ZidooPlayer
import app.houselights.ui.util.UiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Lire's last step: the intent, the memory freed, the player started, and every way it fails said. */
@RunWith(RobolectricTestRunner::class)
class HandOffTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val apps = FakeApps(context)
    private val session = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private var freed = 0
    private val started = mutableListOf<Intent>()
    private val said = mutableListOf<UiError>()

    private fun TestScope.manager(device: DeviceIdentity = ZIDOO_BUILD) =
        PlaybackManager(context, backgroundScope, status = { PlayerAnswer.NoAnswer }, players = ZidooPlayer(context) { device }) { null }

    private fun plan(uri: String) = PlaybackPlan.Launch(
        uri = uri, container = "mkv", title = "Heat", backdropUrl = null, logoUrl = null, startMs = 0,
        reporter = null, resumeKey = "m1",
    )

    private suspend fun PlaybackManager.hand(uri: String, start: (Intent) -> Unit = { started += it }) =
        handOff(plan(uri), tracks = { null }, freeMemory = { freed++ }, start = start, failed = { said += it })

    @Test
    fun thePlayerStartsOnceTheMemoryIsFreed() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true)
        val manager = manager()
        manager.hand(NFS) { intent ->
            assertEquals("freed first", 1, freed)
            started += intent
        }
        assertEquals(MOVIE_ACTIVITY, started.single().component?.className)
        assertTrue(said.isEmpty())
        assertTrue(manager.playing.value)
    }

    /** A password the player may not have: nothing was started, so the pictures stay in memory. */
    @Test
    fun aRefusedPasswordKeepsThePictures() = runTest {
        manager(OTHER_BUILD).hand(SMB)
        assertEquals(listOf(UiError.CREDENTIALS), said)
        assertEquals(0, freed)
        assertTrue(started.isEmpty())
    }

    @Test
    fun aPlayerGoneIsSaidAndItsSessionForgotten() = runTest {
        val manager = manager()
        manager.hand(NFS) { throw ActivityNotFoundException() }
        assertEquals(listOf(UiError.NO_PLAYER), said)
        assertFalse(manager.playing.value)
        assertTrue(session.all.isEmpty())
    }

    /** Whatever else fails on the way is said, never a crash. */
    @Test
    fun anythingElseIsSaidToo() = runTest {
        val manager = manager()
        manager.handOff(plan(NFS), tracks = { error("unreadable") }, freeMemory = { freed++ }, start = { started += it }) { said += it }
        assertEquals(listOf(UiError.FAILED), said)
        assertTrue(started.isEmpty())

        manager.hand(NFS) { throw IllegalStateException("the activity is gone") }
        assertEquals(listOf(UiError.FAILED, UiError.FAILED), said)
        assertTrue(session.all.isEmpty())
    }

    /** Home pressed while the title's tracks are read, the last wait before the player: nothing opens, no session starts. */
    @Test
    fun homePressedWhileTheTracksAreReadOpensNothing() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true)
        val manager = manager()
        var shown = true
        val launched = manager.handOff(
            plan(NFS),
            tracks = {
                shown = false
                null
            },
            onScreen = { shown },
            freeMemory = { freed++ },
            start = { started += it },
        ) { said += it }
        assertFalse(launched)
        assertTrue(started.isEmpty() && said.isEmpty())
        assertEquals(0, freed)
        assertTrue(session.all.isEmpty())
    }

    @Test
    fun theTracksReadOnScreenGoToThePlayer() = runTest {
        apps.install(GALLERY, MOVIE_ACTIVITY, system = true)
        val launched = manager().handOff(plan(NFS), tracks = { null }, onScreen = { true }, freeMemory = {}, start = { started += it }) {
            said += it
        }
        assertTrue(launched)
        assertEquals(1, started.size)
    }

    @Test
    fun cancellingIsNoFailure() = runTest {
        val manager = manager()
        val thrown = runCatching {
            manager.handOff(plan(NFS), tracks = { throw CancellationException("Back") }, freeMemory = {}, start = {}) { said += it }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(said.isEmpty())
    }

    private companion object {
        const val NFS = "nfs://nas/films/Heat.mkv"
        const val SMB = "smb://zidoo:secret@nas/films/Heat.mkv"
    }
}
