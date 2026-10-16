package app.houselights

import android.app.Application
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.houselights.core.Http
import app.houselights.core.LibraryKind
import app.houselights.core.MediaProvider
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.core.throttleLatest
import app.houselights.player.PlaybackManager
import app.houselights.providers.jellyfin.JellyfinProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalEditor
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.RemoteBrowsers
import app.houselights.providers.local.TmdbClient
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HouselightsApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.client }
        // A fixed budget: on a 2 GB player, a share of the large heap would hold 100 MB of pictures or more.
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(IMAGE_MEMORY_BYTES).build() }
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
    /**
     * Work that outlives screens (scans, playback reports). A failure nothing else caught is logged:
     * a background task never takes the app down.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("Houselights", "Background task failed", e) }
    )
    val settings = SettingsStore(app, scope, Secrets.device)

    /** The one way to change the source: the current one stays until the new one is saved. */
    val sources = SourceSwitcher({ settings.state.value }, { settings.setSource(it) }, scope)
    val database = LibraryDatabase.create(app, Secrets.device)
    val artwork = ArtworkCache(app.filesDir.resolve("artwork"))
    val playback = PlaybackManager(app, scope) { resumeKey ->
        // After a restart, the source is known once the settings are read.
        settings.state.first { it.loaded }
        provider.reporter(resumeKey)
    }

    /** The NAS browsers, and the sessions and mounts they keep between files. */
    val remoteBrowsers = RemoteBrowsers()
    val scanner = LibraryScanner(
        dao = database.dao(),
        tmdb = ::tmdb,
        interactiveTmdb = ::tmdbInteractive,
        scope = scope,
        artwork = artwork,
        playing = playback.playing,
        browsers = remoteBrowsers::of,
        closeIdle = remoteBrowsers::closeIdle,
        started = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) },
    )
    val library = LocalLibrary(app, database.dao(), scanner, artwork, remoteBrowsers::forget)

    private val _libraryVersion = MutableStateFlow(0L)

    /**
     * Moves on when library data may have changed (playback ended, scan progress), at most every few
     * seconds. Screens on display reload when it moves; screens behind others when they come back.
     */
    val libraryVersion: StateFlow<Long> = _libraryVersion

    init {
        scope.launch {
            merge(playback.playbackEnded, scanner.changed)
                .throttleLatest(LIBRARY_CHANGE_MS)
                .collect { _libraryVersion.update { it + 1 } }
        }
        // A TMDB key saved, or removed for the built-in one: what TMDB said of the old one is forgotten,
        // and the titles it left unmatched are asked for at once.
        scope.launch {
            settings.state.filter { it.loaded }.map { it.tmdbApiKey }.distinctUntilChanged().drop(1).collect {
                if (settings.state.value.source == SourceConfig.Local) scanner.tmdbKeyChanged()
            }
        }
    }

    @Volatile
    private var cached: Pair<SourceConfig, MediaProvider>? = null

    /** Provider of the active source; rebuilt when the source changes. */
    val provider: MediaProvider
        get() {
            val source = settings.state.value.source ?: error("No library source configured")
            cached?.takeIf { it.first == source }?.let { return it.second }
            val created = when (source) {
                SourceConfig.Local -> LocalProvider(app, database.dao(), LocalEditor(database.dao(), scanner, ::tmdbInteractive)) {
                    settings.state.value.collectionMinFilms
                }
                is SourceConfig.Jellyfin -> JellyfinProvider(source, settings.state.value.deviceId)
            }
            cached = source to created
            return created
        }

    /** TMDB key in use: the user's own, else the one built into the APK. */
    fun tmdbKey(): String? = settings.state.value.tmdbApiKey ?: BuildConfig.TMDB_API_KEY.ifBlank { null }

    @Volatile
    private var tmdbClient: TmdbClient? = null

    @Volatile
    private var tmdbInteractiveClient: TmdbClient? = null

    /**
     * Scans' TMDB client for the key in use, made once and kept while the key and language stay. Once
     * TMDB could not be reached, it fails at once for a while (see [TmdbClient]).
     */
    fun tmdb(): TmdbClient? {
        val key = tmdbKey()?.takeIf { it.isNotBlank() } ?: return null
        tmdbClient?.takeIf { it.fits(key) }?.let { return it }
        return TmdbClient(key, pauseWhenUnreachable = true).also { tmdbClient = it }
    }

    /** The TMDB client of what people ask (a search, an identification, artwork): TMDB is asked every time. */
    fun tmdbInteractive(): TmdbClient? {
        val key = tmdbKey()?.takeIf { it.isNotBlank() } ?: return null
        tmdbInteractiveClient?.takeIf { it.fits(key) }?.let { return it }
        return TmdbClient(key).also { tmdbInteractiveClient = it }
    }

    /** Poster URLs of trending films, or nothing when TMDB is unreachable. */
    suspend fun trendingPosters(widthPx: Int): List<String> = suspendRunCatching {
        val tmdb = tmdbInteractive() ?: return emptyList()
        tmdb.trendingPosterPaths().map { TmdbClient.imageUrl(it, TmdbClient.Companion.ImageType.POSTER, widthPx) }
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

/** Scan progress refreshes screens at most this often. */
private const val LIBRARY_CHANGE_MS = 3_000L

/** Decoded pictures kept in memory: a few backdrops and the posters of several rows. */
private const val IMAGE_MEMORY_BYTES = 64 * 1024 * 1024
