package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/**
 * A library on an in-memory database, scanned from a NAS ([nas], [showsNas]) and a TMDB ([tmdb]) held
 * in memory: what scenario tests of scans stand on. Robolectric tests only.
 */
class ScanBench(artworkDir: File, tmdbKey: Boolean = true) {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .addTypeConverter(SecretConverter(Secrets { key }))
        .build()
    val dao: LibraryDao = db.dao()
    val nas = FakeRemoteBrowser()
    val showsNas = FakeRemoteBrowser()
    val tmdb = FakeTmdb()
    private val passes = SupervisorJob()
    var now = START

    /** Whether the app has a TMDB key. */
    var withTmdb = tmdbKey

    /** The scans' client, kept as the app keeps it: its pause lasts from one pass to the next. */
    val scanClient = tmdb.client(pauseWhenUnreachable = true)

    /** The bytes the device can still write, where the artwork is copied. */
    var freeSpace = Long.MAX_VALUE

    val artwork = ArtworkCache(artworkDir) { freeSpace }

    /** A film playing, the app on screen: what a scan waits for. */
    val playing = MutableStateFlow(false)
    val started = MutableStateFlow(true)

    /** Times the scanner closed what it kept on the NAS, and what runs when it does. */
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
    )

    fun close() = db.close()

    /** Folders on a NAS of their own; the others on [nas] or [showsNas], by kind. */
    private val own = mutableMapOf<Long, FakeRemoteBrowser>()

    suspend fun folder(name: String, kind: SourceKind = SourceKind.MOVIES, on: FakeRemoteBrowser? = null): SourceEntity {
        val id = dao.insertSource(
            SourceEntity(
                name = name, kind = kind, protocol = Protocol.NFS, host = "nas", share = "media", path = name.lowercase(),
                username = "", password = Secret(""),
            )
        )
        on?.let { own[id] = it }
        return checkNotNull(dao.source(id))
    }

    /** One whole pass, as the app asks for it, waited for. */
    suspend fun scan(refreshMetadata: Boolean = false) {
        scanner.scanAll(refreshMetadata)
        awaitScans()
    }

    /** Waits for the scans under way, such as one the scanner started of itself. */
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
