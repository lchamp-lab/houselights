package app.posterwall

import android.app.Application
import app.posterwall.core.LibraryKind
import app.posterwall.core.MediaProvider
import app.posterwall.core.SourceConfig
import app.posterwall.player.PlaybackManager
import app.posterwall.providers.jellyfin.JellyfinProvider
import app.posterwall.providers.local.LibraryDatabase
import app.posterwall.providers.local.LibraryScanner
import app.posterwall.providers.local.LocalProvider
import app.posterwall.settings.SettingsStore
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

class PosterwallApp : Application(), ImageLoaderFactory {
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
                .maxSizeBytes(1024L * 1024 * 1024)
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
    val scanner = LibraryScanner(
        dao = database.dao(),
        tmdbKey = { settings.state.value.tmdbApiKey ?: BuildConfig.TMDB_API_KEY.ifBlank { null } },
        scope = scope,
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
                SourceConfig.Local -> LocalProvider(app, database.dao())
                is SourceConfig.Jellyfin -> JellyfinProvider(source, settings.state.value.deviceId)
            }
            cached = source to created
            return created
        }

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
