package app.houselights.providers.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** How sessions and mounts are kept between files, closed, and made again, and what waits for what. */
class KeptConnectionsTest {
    /** A connection: what closed it, broken or not, in order. */
    private class Connection(val n: Int) {
        var hungUp = false
        val closes = mutableListOf<Boolean>()
    }

    private var now = 1_000_000L
    private val made = mutableListOf<Connection>()
    private val kept = KeptConnections<Connection>(
        close = { connection, broken -> connection.closes += broken },
        isOpen = { !it.hungUp && it.closes.isEmpty() },
        // As the browsers tell them: the network failed, not the file.
        isBroken = { it is IOException && it !is FileNotFoundException },
        clock = { now },
    )

    private fun connect() = synchronized(made) { Connection(made.size).also { made += it } }

    private fun use(key: String = "nas/films", block: (Connection) -> Unit = {}) =
        runBlocking { kept.use(key, ::connect) { block(it); it } }

    @Test
    fun aConnectionServesTheNextFile() {
        val first = use()
        now += KeptConnections.IDLE_MS - 1
        assertSame(first, use())
        assertEquals(1, made.size)
    }

    @Test
    fun aConnectionLeftIdleIsClosedNotDropped() {
        val first = use()
        now += KeptConnections.IDLE_MS
        val second = use()
        assertEquals(2, made.size)
        assertEquals("the NAS is told, and frees the session", listOf(false), first.closes)
        assertEquals(emptyList<Boolean>(), second.closes)
    }

    @Test
    fun aConnectionTheNasHungUpIsMadeAgain() {
        val first = use()
        first.hungUp = true
        use()
        assertEquals(2, made.size)
        assertEquals("not asked to sign off: nothing would answer", listOf(true), first.closes)
    }

    @Test
    fun aConnectionTheNasHungUpIsOnlyHungUpWhenThePassEnds() {
        val first = use()
        first.hungUp = true
        kept.closeIdle()
        assertEquals(listOf(true), first.closes)
    }

    @Test
    fun aKeptConnectionThatBreaksIsMadeAgainOnce() {
        val first = use()
        var tries = 0
        val second = runBlocking {
            kept.use("nas/films", ::connect) {
                if (tries++ == 0) throw IOException("Connection reset")
                it
            }
        }
        assertEquals(listOf(true), first.closes)
        assertEquals(emptyList<Boolean>(), second.closes)
        assertSame("kept in its place", second, use())
    }

    @Test
    fun aNewConnectionThatBreaksIsClosedAndTheFailureTold() {
        assertThrows(IOException::class.java) { use { throw IOException("Connection reset") } }
        assertEquals(1, made.size)
        assertEquals(listOf(true), made.single().closes)
    }

    @Test
    fun aFileThatFailsLeavesTheConnectionKept() {
        val first = use()
        assertThrows(FileNotFoundException::class.java) { use { throw FileNotFoundException("movie.nfo") } }
        assertEquals(emptyList<Boolean>(), first.closes)
        assertSame(first, use())
    }

    @Test
    fun aConnectionThatCannotBeMadeIsNotTriedTwice() {
        var tries = 0
        assertThrows(IOException::class.java) {
            runBlocking { kept.use("nas/films", { tries++; throw IOException("Connection refused") }) {} }
        }
        assertEquals(1, tries)
    }

    @Test
    fun aConnectionOnItsOwnIsNeitherKeptNorTakenFromThoseKept() {
        val first = use()
        val own = kept.once(::connect) { it }
        assertEquals(2, made.size)
        assertEquals(listOf(false), own.closes)
        assertEquals(emptyList<Boolean>(), first.closes)
        assertSame(first, use())
    }

    @Test
    fun aConnectionOnItsOwnThatBreaksIsOnlyHungUp() {
        assertThrows(IOException::class.java) { kept.once(::connect) { throw IOException("Connection reset") } }
        assertEquals(listOf(true), made.single().closes)
        kept.once(::connect) { it.hungUp = true }
        assertEquals(listOf(true), made[1].closes)
    }

    @Test
    fun forgettingClosesTheConnectionsAsked() {
        val films = use("kodi@/nas/films")
        val guest = use("@/nas/films")
        val shows = use("kodi@/nas/shows")
        runBlocking { kept.forget { it.endsWith("/nas/films") } }
        assertEquals(listOf(false), films.closes)
        assertEquals(listOf(false), guest.closes)
        assertEquals(emptyList<Boolean>(), shows.closes)
        assertSame(shows, use("kodi@/nas/shows"))
    }

    /**
     * Runs an operation for [key] on [connections] on a thread of its own, held in its block (or,
     * [connecting], in making its connection: a NAS that does not answer), and [meanwhile] while it
     * is held, then lets it end. Whether [meanwhile] returned before the operation ended.
     */
    private fun whileInUse(
        meanwhile: () -> Unit,
        waitMs: Long,
        connections: KeptConnections<Connection> = kept,
        key: String = "nas:/volume1/media",
        connecting: Boolean = false,
    ): Boolean {
        val inUse = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun hold() {
            inUse.countDown()
            release.await()
        }
        fun connectAfterHold(): Connection {
            if (connecting) hold()
            return connect()
        }
        val operation = thread {
            runCatching {
                runBlocking { connections.use(key, ::connectAfterHold) { if (!connecting) hold() } }
            }
        }
        assertTrue(inUse.await(5, TimeUnit.SECONDS))
        val other = thread { meanwhile() }
        other.join(waitMs)
        val returned = !other.isAlive
        release.countDown()
        operation.join(5_000)
        other.join(5_000)
        return returned
    }

    /** An SMB session signs off: not under a read. */
    @Test
    fun forgettingASessionWaitsForTheOperationUnderWay() {
        assertFalse(whileInUse({ runBlocking { kept.forget { true } } }, waitMs = 200))
        assertEquals("signed off once the operation ended", listOf(false), made.single().closes)
    }

    /** An NFS mount closes by being dropped: a folder's new address is saved while the old one is listed. */
    @Test
    fun forgettingAMountWaitsForNoOperation() {
        val mounts = KeptConnections<Connection>(
            close = { connection, broken -> connection.closes += broken },
            isOpen = { !it.hungUp && it.closes.isEmpty() },
            isBroken = { it is IOException },
            clock = { now },
            closingWaits = false,
        )
        assertTrue(whileInUse({ runBlocking { mounts.forget { it.endsWith("/volume1/media") } } }, waitMs = 5_000, connections = mounts))
        assertEquals("closed as its operation ends, not kept", listOf(false), made.single().closes)
        runBlocking { mounts.use("nas:/volume1/media", ::connect) {} }
        assertEquals(2, made.size)
    }

    /** A scan listing one share, and a folder of another share saved meanwhile with a new password: nothing to wait for. */
    @Test
    fun forgettingWaitsForNoOtherKeysOperation() {
        val shows = use("kodi@/nas/shows")
        assertTrue(whileInUse({ runBlocking { kept.forget { it.endsWith("/nas/shows") } } }, waitMs = 5_000, key = "kodi@/nas/films"))
        assertEquals(listOf(false), shows.closes)
    }

    /** Two shares, two NAS: what one reads, the other does not wait for. */
    @Test
    fun anOperationWaitsForNoOtherKeys() {
        var other: Connection? = null
        assertTrue(whileInUse({ other = use("kodi@/nas/shows") }, waitMs = 5_000, key = "kodi@/nas/films"))
        assertEquals(emptyList<Boolean>(), other?.closes)
    }

    /** A NAS that does not answer (off, or its address changed) holds the connecting to it, not to another. */
    @Test
    fun connectingToANasHoldsNoOther() {
        assertTrue(whileInUse({ use("kodi@/other-nas/films") }, waitMs = 5_000, key = "kodi@/nas/films", connecting = true))
    }

    /** Leaving the screen of a folder stops a wait for the operation under way on its share. */
    @Test
    fun waitingForAnOperationStopsWithTheCoroutineThatWaits() {
        var gaveUp = false
        assertTrue(
            whileInUse(
                { gaveUp = runBlocking { withTimeoutOrNull(100) { kept.use("nas:/volume1/media", ::connect) {} } } == null },
                waitMs = 5_000,
            ),
        )
        assertTrue(gaveUp)
    }

    /** Leaving the screen while a new password is saved stops the wait: the session signed in with the old one is not kept for all that. */
    @Test
    fun aWaitToForgetCutShortKeepsNothing() {
        val shows = use("nas/shows")
        var gaveUp = false
        assertTrue(whileInUse({ gaveUp = runBlocking { withTimeoutOrNull(100) { kept.forget { true } } } == null }, waitMs = 5_000))
        assertTrue(gaveUp)
        assertEquals(listOf(false), shows.closes)
        assertEquals("closed as its operation ended, not kept", listOf(false), made[1].closes)
    }

    /** The pass is over: what it kept closes now, and one in use as its operation ends, without waiting for it. */
    @Test
    fun closingWhatIsIdleWaitsForNoOperation() {
        val films = use("nas/films")
        assertTrue(whileInUse({ kept.closeIdle() }, waitMs = 5_000))
        assertEquals(listOf(false), films.closes)
        assertEquals("closed as its operation ended, not kept", listOf(false), made[1].closes)
        assertNull(made.getOrNull(2))
    }

    @Test
    fun closingWhatIsIdleClosesEveryConnection() {
        val films = use("nas/films")
        val shows = use("nas/shows")
        kept.closeIdle()
        assertEquals(listOf(false), films.closes)
        assertEquals(listOf(false), shows.closes)
        use("nas/films")
        assertEquals(3, made.size)
    }
}
