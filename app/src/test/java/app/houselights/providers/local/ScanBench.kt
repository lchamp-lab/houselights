package app.houselights.providers.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.net.ConnectException
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory library, NAS ([nas], [showsNas]) and TMDB ([tmdb]) for scan scenario tests, on Robolectric.
 * [onQuery] sees each statement the library runs, with its bound values, on the thread that runs it.
 */
class ScanBench(
    artworkDir: File,
    tmdbKey: Boolean = true,
    onQuery: ((sql: String, args: List<Any?>) -> Unit)? = null,
) {
    val db: LibraryDatabase = testDatabase {
        onQuery?.let { callback -> setQueryCallback({ sql, args -> callback(sql, args) }, Runnable::run) }
    }
    val dao: LibraryDao = db.dao()
    val nas = FakeRemoteBrowser()
    val showsNas = FakeRemoteBrowser()
    val tmdb = FakeTmdb()
    private val passes = SupervisorJob()
    var now = START

    var withTmdb = tmdbKey

    /** The scans' client, kept as the app keeps it: its pause carries over from one pass to the next. */
    val scanClient = tmdb.client(pauseWhenUnreachable = true)

    /** Bytes the device can still write where the artwork is copied. */
    var freeSpace = Long.MAX_VALUE

    private val artwork = ArtworkCache(artworkDir) { freeSpace }

    /** Whether a film is playing and whether the app is on screen: what a scan waits for. */
    val playing = MutableStateFlow(false)
    val started = MutableStateFlow(true)

    /** Times the scanner closed its NAS connections, and what runs when it does. */
    val closed = AtomicInteger()
    var onClose: () -> Unit = {}

    /** On [now] and on Robolectric's time since boot, which [ShadowSystemClock.advanceBy] moves. */
    val scanner = LibraryScanner(
        dao = dao,
        tmdb = { scanClient.takeIf { withTmdb } },
        scope = CoroutineScope(passes),
        artwork = artwork,
        playing = playing,
        browsers = { own[it.id] ?: if (it.kind == SourceKind.SHOWS) showsNas else nas },
        closeIdle = {
            closed.incrementAndGet()
            onClose()
        },
        clock = { now },
        interactiveTmdb = { tmdb.client().takeIf { withTmdb } },
        started = started,
    )

    fun close() = db.close()

    /** Folders on a NAS of their own; the others on [nas] or [showsNas], by kind. */
    private val own = mutableMapOf<Long, FakeRemoteBrowser>()

    suspend fun folder(name: String, kind: SourceKind = SourceKind.MOVIES, on: FakeRemoteBrowser? = null): SourceEntity {
        val id = dao.insertSource(nfsFolder(name, kind))
        on?.let { own[id] = it }
        return checkNotNull(dao.source(id))
    }

    /** One whole pass, as the app requests it, awaited. */
    suspend fun scan(refreshMetadata: Boolean = false) {
        scanner.scanAll(refreshMetadata)
        awaitScans()
    }

    /** A pass the app starts on its own ([LibraryScanner.scanIfStale]) unless one ended within [maxAgeMs], awaited. */
    suspend fun autoScan(maxAgeMs: Long = 0) {
        scanner.scanIfStale(maxAgeMs)
        awaitScans()
    }

    /** Waits for the scans under way, such as one the scanner started on its own. */
    suspend fun awaitScans() = passes.children.toList().forEach { it.join() }

    /** Cuts the scans under way short, as when the app's process is stopped. */
    suspend fun cancelScans() = passes.children.toList().forEach { it.cancelAndJoin() }

    /** How many times screens are told the library changed while [block] runs, a pass's progress included. */
    suspend fun changesDuring(block: suspend () -> Unit): Int = toldDuring(block).let { it.progress + it.changes }

    /** What screens are told while [block] runs: a pass's progress, and changes to show at once. */
    suspend fun toldDuring(block: suspend () -> Unit): Told = coroutineScope {
        val progress = AtomicInteger()
        val changes = AtomicInteger()
        val watch = launch(Dispatchers.Unconfined) {
            launch { scanner.progress.collect { progress.incrementAndGet() } }
            scanner.changed.collect { changes.incrementAndGet() }
        }
        block()
        watch.cancel()
        Told(progress.get(), changes.get())
    }

    class Told(val progress: Int, val changes: Int)

    /** What one pass read on [nas] and [showsNas], asked of TMDB, and how often it told screens of a change. */
    class Pass(val reads: List<String>, val requests: List<String>, val changes: Int)

    /** One pass, requested ([scan]) or the app's own ([autoScan]), observed from its start. */
    suspend fun pass(refresh: Boolean = false, auto: Boolean = false): Pass {
        nas.reads.clear()
        showsNas.reads.clear()
        tmdb.requests.clear()
        val changes = changesDuring { if (auto) autoScan() else scan(refresh) }
        return Pass(nas.reads + showsNas.reads, tmdb.requests.toList(), changes)
    }

    /** A pass that reads nothing on the NAS, asks TMDB nothing and leaves screens as they are. */
    suspend fun assertQuiet(auto: Boolean = false) {
        val pass = pass(auto = auto)
        assertEquals("nothing read on the NAS", emptyList<String>(), pass.reads)
        assertEquals("nothing asked of TMDB", emptyList<String>(), pass.requests)
        assertEquals("screens left as they are", 0, pass.changes)
    }

    /** TMDB out of reach as the device meets it, the network down: the scans' client pauses after the first request. */
    fun tmdbOutOfReach() {
        tmdb.failure = ConnectException("Network is unreachable")
    }

    /** TMDB answering again, just past the scans' client's pause: a requested pass asks at once, the app's own wait the hour. */
    fun tmdbBack() {
        tmdb.failure = null
        tmdb.status = null
        tmdb.failingPath = null
        ShadowSystemClock.advanceBy(Duration.ofMillis(CLIENT_PAUSE_MS + 1))
    }

    fun error(source: SourceEntity) = scanner.state.value.errors[source.id]?.kind

    /** The row of the file at [path], read whole (its NFO text included). */
    suspend fun item(source: SourceEntity, path: String): ItemEntity =
        checkNotNull(dao.item(dao.filesOf(source.id).single { it.filePath == path }.id)) { "No $path" }

    /** Paths of the files [source] holds, sorted. */
    suspend fun paths(source: SourceEntity): List<String> = dao.filesOf(source.id).mapNotNull { it.filePath }.sorted()

    /** The show of the episode at [path]. */
    suspend fun showOf(source: SourceEntity, path: String): ItemEntity = checkNotNull(dao.item(checkNotNull(item(source, path).seriesId)))

    /** The show keyed as folders naming [title] (and [year]) are. */
    suspend fun series(title: String, year: Int? = null): ItemEntity =
        checkNotNull(dao.seriesByKey(FilenameParser.showKey(title, year))) { "No show $title" }

    companion object {
        const val START = 1_800_000_000_000L

        /** How long the scans' client stops asking TMDB after a network failure (TmdbClient's own). */
        private const val CLIENT_PAUSE_MS = 60_000L
    }
}

/** What the library runs, as a [ScanBench]'s query callback: values bound per statement, transactions, SQL while [recording]. */
class StatementLog : (String, List<Any?>) -> Unit {
    /** The most values one statement bound since [reset]. */
    private val widest = AtomicInteger()

    /** Transactions opened since [reset], nested ones not counted. */
    val transactions = AtomicInteger()
    private val depth = AtomicInteger()

    /** Statements run while [recording]. */
    val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var recording = false

    override fun invoke(sql: String, args: List<Any?>) {
        widest.accumulateAndGet(args.size, ::maxOf)
        if (recording) recorded += sql
        when {
            sql.startsWith("BEGIN") -> if (depth.getAndIncrement() == 0) transactions.incrementAndGet()
            sql.startsWith("END") -> depth.decrementAndGet()
        }
    }

    fun reset() {
        widest.set(0)
        transactions.set(0)
    }

    /** Android 9's SQLite binds at most 999 values a statement; Robolectric's binds more. */
    fun assertNoStatementTooWideForAndroid9() = assertTrue("${widest.get()} values in one statement", widest.get() <= ANDROID_9_LIMIT)

    private companion object {
        const val ANDROID_9_LIMIT = 999
    }
}
