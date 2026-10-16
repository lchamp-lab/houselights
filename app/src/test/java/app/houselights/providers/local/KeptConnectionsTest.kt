package app.houselights.providers.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/** How sessions and mounts are kept between files, closed, and made again. */
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

    private fun connect() = Connection(made.size).also { made += it }

    private fun use(key: String = "nas/films", block: (Connection) -> Unit = {}) = kept.use(key, ::connect) { block(it); it }

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
        use().hungUp = true
        use()
        assertEquals(2, made.size)
    }

    @Test
    fun aKeptConnectionThatBreaksIsMadeAgainOnce() {
        val first = use()
        var tries = 0
        val second = kept.use("nas/films", ::connect) {
            if (tries++ == 0) throw IOException("Connection reset")
            it
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
            kept.use("nas/films", { tries++; throw IOException("Connection refused") }) {}
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
    }

    @Test
    fun forgettingClosesTheConnectionsAsked() {
        val films = use("kodi@/nas/films")
        val guest = use("@/nas/films")
        val shows = use("kodi@/nas/shows")
        kept.forget { it.endsWith("/nas/films") }
        assertEquals(listOf(false), films.closes)
        assertEquals(listOf(false), guest.closes)
        assertEquals(emptyList<Boolean>(), shows.closes)
        assertSame(shows, use("kodi@/nas/shows"))
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
