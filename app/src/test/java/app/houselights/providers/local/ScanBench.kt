package app.houselights.providers.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory library, NAS ([nas], [showsNas]) and TMDB ([tmdb]) for scan scenario tests, on Robolectric. */
class ScanBench(artworkDir: File, tmdbKey: Boolean = true) {
    val db: LibraryDatabase = testDatabase()
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

    val artwork = ArtworkCache(artworkDir) { freeSpace }

    /** Whether a film is playing and whether the app is on screen: what a scan waits for. */
    val playing = MutableStateFlow(false)
    val started = MutableStateFlow(true)

    /** Languages the scanner said a refresh read every source in. */
    val refreshedIn: MutableList<Locale> = Collections.synchronizedList(mutableListOf())

    /** Times the scanner closed its NAS connections, and what runs when it does. */
    val closed = AtomicInteger()
    var onClose: () -> Unit = {}

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
        refreshedIn = { refreshedIn += it },
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

    /** A pass the app starts on its own every few minutes ([LibraryScanner.scanIfStale]), awaited. */
    suspend fun autoScan() {
        scanner.scanIfStale(0)
        awaitScans()
    }

    /** Waits for the scans under way, such as one the scanner started on its own. */
    suspend fun awaitScans() = passes.children.toList().forEach { it.join() }

    /** How many times screens are told the library changed while [block] runs. */
    suspend fun changesDuring(block: suspend () -> Unit): Int = coroutineScope {
        val changes = AtomicInteger()
        val watch = launch(Dispatchers.Unconfined) { scanner.changed.collect { changes.incrementAndGet() } }
        block()
        watch.cancel()
        changes.get()
    }

    fun error(source: SourceEntity) = scanner.state.value.errors[source.id]?.kind

    /** The row of the file at [path], read whole (its NFO text included). */
    suspend fun item(source: SourceEntity, path: String): ItemEntity =
        checkNotNull(dao.item(dao.filesOf(source.id).single { it.filePath == path }.id)) { "No $path" }

    companion object {
        const val START = 1_800_000_000_000L
    }
}
