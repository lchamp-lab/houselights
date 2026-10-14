package app.houselights

import android.app.Application
import app.houselights.core.LibraryKind
import app.houselights.core.MediaProvider
import app.houselights.core.SourceConfig
import app.houselights.player.PlaybackManager
import app.houselights.providers.jellyfin.JellyfinProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalEditor
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.TmdbClient
import app.houselights.settings.SettingsStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class HouselightsApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { OkHttpClient() }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(1_000_000_000L) // 1 GB, shown as such in Settings > Storage.
                .build()
        }
        .crossfade(300)
        .respectCacheHeaders(false)
        .build()
}

/** Manual dependency container, created once per process. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app, scope)
    val database = LibraryDatabase.create(app)
    val artwork = ArtworkCache(app.filesDir.resolve("artwork"))
    val scanner = LibraryScanner(
        dao = database.dao(),
        tmdbKey = { tmdbKey() },
        scope = scope,
        artwork = artwork,
    )
    val playback = PlaybackManager(app, scope)

    /** Emits whenever library data may have changed (playback ended, scan progress). */
    val libraryChanged: Flow<Unit> = merge(playback.playbackEnded, scanner.changed)

    @Volatile
    private var cached: Pair<SourceConfig, MediaProvider>? = null

    /** Provider of the active source; rebuilt when the source changes. */
    val provider: MediaProvider
        get() {
            val source = settings.state.value.source ?: error("No library source configured")
            cached?.takeIf { it.first == source }?.let { return it.second }
            val created = when (source) {
                SourceConfig.Local -> LocalProvider(app, database.dao(), LocalEditor(database.dao(), scanner, ::tmdbKey)) { settings.state.value.collectionMinFilms }
                is SourceConfig.Jellyfin -> JellyfinProvider(source, settings.state.value.deviceId)
            }
            cached = source to created
            return created
        }

    /** TMDB key in use: the user's own, else the one built into the APK. */
    fun tmdbKey(): String? = settings.state.value.tmdbApiKey ?: BuildConfig.TMDB_API_KEY.ifBlank { null }

    /** Poster URLs of trending films, or nothing when TMDB is unreachable. */
    suspend fun trendingPosters(widthPx: Int): List<String> = runCatching {
        val key = tmdbKey() ?: return emptyList()
        TmdbClient(key).trendingPosterPaths().map { TmdbClient.imageUrl(it, TmdbClient.Companion.ImageType.POSTER, widthPx) }
    }.getOrDefault(emptyList())

    fun libraryTitle(kind: LibraryKind): String = app.getString(
        when (kind) {
            LibraryKind.MOVIES -> R.string.library_movies
            LibraryKind.SHOWS -> R.string.library_shows
            LibraryKind.COLLECTIONS -> R.string.library_collections
            LibraryKind.MIXED -> R.string.library_mixed
        }
    )

    init {
        scope.launch { settings.ensureDeviceId() }
    }
}
