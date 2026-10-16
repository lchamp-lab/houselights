package app.houselights.providers.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.crypto.KeyGenerator

/**
 * A library on an in-memory database, scanned from a NAS ([nas], [showsNas]) and a TMDB ([tmdb]) held
 * in memory: what scenario tests of scans stand on. Robolectric tests only.
 */
class ScanBench(artworkDir: File, private val withTmdb: Boolean = true) {
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

    /** The scans' client, kept as the app keeps it: its pause lasts from one pass to the next. */
    val scanClient = tmdb.client(pauseWhenUnreachable = true)

    val artwork = ArtworkCache(artworkDir)

    val scanner = LibraryScanner(
        dao = dao,
        tmdb = { scanClient.takeIf { withTmdb } },
        scope = CoroutineScope(passes),
        artwork = artwork,
        browsers = { if (it.kind == SourceKind.SHOWS) showsNas else nas },
        clock = { now },
        interactiveTmdb = { tmdb.client().takeIf { withTmdb } },
    )

    fun close() = db.close()

    suspend fun folder(name: String, kind: SourceKind = SourceKind.MOVIES): SourceEntity {
        val id = dao.insertSource(
            SourceEntity(
                name = name, kind = kind, protocol = Protocol.NFS, host = "nas", share = "media", path = name.lowercase(),
                username = "", password = Secret(""),
            )
        )
        return checkNotNull(dao.source(id))
    }

    /** One whole pass, as the app asks for it, waited for. */
    suspend fun scan(refreshMetadata: Boolean = false) {
        scanner.scanAll(refreshMetadata)
        passes.children.toList().forEach { it.join() }
    }

    fun error(source: SourceEntity) = scanner.state.value.errors[source.id]?.kind

    /** The row of the file at [path], read whole (its NFO text included). */
    suspend fun item(source: SourceEntity, path: String): ItemEntity =
        checkNotNull(dao.item(dao.filesOf(source.id).single { it.filePath == path }.id)) { "No $path" }

    companion object {
        const val START = 1_800_000_000_000L
    }
}
