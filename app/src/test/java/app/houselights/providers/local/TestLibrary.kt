package app.houselights.providers.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.houselights.core.ArtworkChoices
import app.houselights.core.ArtworkKind
import app.houselights.core.ArtworkOption
import app.houselights.core.CollectionThreshold
import app.houselights.core.Identity
import app.houselights.core.MatchCandidate
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MetadataEditor
import app.houselights.core.Secrets
import app.houselights.testing.testSecrets
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

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

/** Inserts [item] ([LibraryDao.insert]) with [nfo] as its NFO text ([NfoDao.setNfo]); its id. */
suspend fun LibraryDao.insertWithNfo(item: ItemEntity, nfo: String?): Long = insert(item).also { setNfo(it, nfo) }

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

/** A folder on an SMB share, signed in to as [username]. */
fun smbFolder(
    name: String = "Films",
    kind: SourceKind = SourceKind.MOVIES,
    host: String = "192.168.1.10",
    share: String = "media",
    path: String = name.lowercase(),
    username: String = "kodi",
    password: String = "secret",
    domain: String = "",
) = SourceEntity(
    name = name, kind = kind, protocol = Protocol.SMB, host = host, share = share, path = path, username = username,
    password = Secret(password), domain = domain,
)

/**
 * An in-memory library holding a folder of films ([source]), Room answering on the test's thread, and the local
 * provider over it, a saga shown from [collectionMin] films; closed after each test.
 */
class TestLibrary(private val collectionMin: () -> Int = { CollectionThreshold.TWO_FILMS }) : ExternalResource() {
    lateinit var db: LibraryDatabase
        private set
    lateinit var dao: LibraryDao
        private set
    lateinit var provider: LocalProvider
        private set
    var source = 0L
        private set

    override fun before() = runBlocking {
        db = testDatabase { allowMainThreadQueries() }
        dao = db.dao()
        provider = LocalProvider(ApplicationProvider.getApplicationContext(), dao, NoEditor, collectionMinFilms = collectionMin)
        source = dao.insertSource(nfsFolder())
    }

    override fun after() = db.close()

    /** A film of [source], identified and sorted as a scan leaves it: one argument per column a test sets. */
    @Suppress("LongParameterList") // One per column a test sets.
    suspend fun film(
        title: String,
        year: Int? = 2020,
        tmdbId: Int? = null,
        path: String = "$title.mkv",
        badges: List<String> = emptyList(),
        rating: Double? = null,
        votes: Int? = null,
        collectionId: Long? = null,
        played: Boolean = false,
        lastPlayedAt: Long? = null,
        positionMs: Long = 0,
        fileSize: Long? = null,
        runtimeMin: Int? = null,
        originalTitle: String? = null,
        genres: List<String> = emptyList(),
        media: MediaInfo? = null,
    ): Long = dao.insert(
        ItemEntity(
            kind = ItemKind.MOVIE, sourceId = source, title = title, sortTitle = MetadataMerge.sortKey(title), year = year,
            tmdbId = tmdbId, filePath = path, badges = badges, rating = rating, votes = votes, collectionId = collectionId,
            played = played, lastPlayedAt = lastPlayedAt, positionMs = positionMs, fileSize = fileSize, runtimeMin = runtimeMin,
            originalTitle = originalTitle, addedAt = 0, matchedAt = 1, genres = genres, mediaInfo = media?.let(MediaProbe::encode),
        ),
    )
}

/** An editor for tests that never edit: every call but [canEdit] fails. */
internal object NoEditor : MetadataEditor {
    override fun canEdit(item: MediaItem) = false
    override suspend fun artwork(item: MediaItem, kind: ArtworkKind): ArtworkChoices = error("unused")
    override suspend fun setArtwork(item: MediaItem, kind: ArtworkKind, option: ArtworkOption) = error("unused")
    override suspend fun identity(item: MediaItem): Identity = error("unused")
    override suspend fun search(item: MediaItem, query: String, year: Int?): List<MatchCandidate> = error("unused")
    override suspend fun identify(item: MediaItem, candidate: MatchCandidate?) = error("unused")
}
