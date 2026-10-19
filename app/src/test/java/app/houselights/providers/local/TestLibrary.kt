package app.houselights.providers.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaItem
import app.houselights.core.MetadataEditor
import app.houselights.core.Secrets
import app.houselights.testing.testSecrets

/**
 * An in-memory library sealing passwords with [secrets]. [configure] adds what a test needs to Room's builder:
 * main-thread queries (Robolectric runs tests on the main thread, where Room refuses them), executors, a query callback.
 */
fun testDatabase(
    secrets: Secrets = testSecrets(),
    configure: RoomDatabase.Builder<LibraryDatabase>.() -> Unit = {},
): LibraryDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
    .addTypeConverter(SecretConverter(secrets))
    .apply(configure)
    .build()

/** Queries and transactions run on the calling thread, so that a test's scheduler sees every step. */
fun RoomDatabase.Builder<LibraryDatabase>.onCallingThread() {
    allowMainThreadQueries()
    setQueryExecutor(Runnable::run)
    setTransactionExecutor(Runnable::run)
}

/** A folder on an NFS share, open to guests. */
fun nfsFolder(
    name: String = "Films",
    kind: SourceKind = SourceKind.MOVIES,
    host: String = "nas",
    share: String = "media",
    path: String = name.lowercase(),
    id: Long = 0L,
    lastScanAt: Long = 0L,
) = SourceEntity(
    id = id, name = name, kind = kind, protocol = Protocol.NFS, host = host, share = share, path = path, username = "",
    password = Secret(""), lastScanAt = lastScanAt,
)

/** An editor for tests that never edit: every call but [canEdit] fails. */
internal object NoEditor : MetadataEditor {
    override fun canEdit(item: MediaItem) = false
    override suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices = error("unused")
    override suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption) = error("unused")
    override suspend fun identity(item: MediaItem): Identity = error("unused")
    override suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate> = error("unused")
    override suspend fun identify(item: MediaItem, candidate: MatchCandidate?) = error("unused")
}
