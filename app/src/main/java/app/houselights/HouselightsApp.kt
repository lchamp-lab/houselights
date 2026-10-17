package app.houselights

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.houselights.core.Http
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaProvider
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import app.houselights.core.suspendRunCatching
import app.houselights.player.PlaybackManager
import app.houselights.player.Redact
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
import app.houselights.ui.home.HomeLaunch
import app.houselights.ui.util.PlaybackOutcome
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.NullRequestDataException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

class HouselightsApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { imageHttpClient(Http.client) }
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
        .eventListener(ImageFailureLog())
        .build()
}

/**
 * Says in the log why a picture did not load (a card left with its title, a black backdrop): once
 * a minute for each place pictures come from (a server, TMDB, the copies on the device), as a NAS
 * asleep or a server away fails a whole screen of pictures at once. Addresses lose their secrets
 * ([Redact]); the screens have nothing to tell.
 */
internal class ImageFailureLog(private val clock: () -> Long = SystemClock::elapsedRealtime) : EventListener {
    /** When each place was last told of. */
    private val toldAt = ConcurrentHashMap<String, Long>()

    override fun onError(request: ImageRequest, result: ErrorResult) {
        // No picture asked for: nothing failed.
        if (result.throwable is NullRequestDataException) return
        val address = request.data.toString()
        val uri = Uri.parse(address)
        val now = clock()
        var tell = false
        toldAt.compute("${uri.scheme}://${uri.authority.orEmpty()}") { _, last ->
            if (last == null || now - last >= QUIET_MS) now.also { tell = true } else last
        }
        if (tell) Log.w(TAG, "Image ${Redact.uri(address)} failed", result.throwable)
    }

    private companion object {
        const val TAG = "Images"

        /** How long the failures of one place say nothing more once one was told. */
        const val QUIET_MS = 60_000L
    }
}

/** Manual dependency container, created once per process. */
class AppContainer(private val app: Application) : LibraryEvents {
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
    /** The player: launched for a title, and followed while it plays. */
    val player = PlaybackManager(app, scope) { resumeKey ->
        // After a restart, the source is known once the settings are read.
        settings.state.first { it.loaded }
        provider.reporter(resumeKey)
    }

    /** What came of a title asked to play, as screens see it: ended, or why it did not play. */
    val playback = PlaybackOutcome(player.playbackEnded)

    /** The launch screen, held at a cold start while home gets ready under it. */
    val launch = LaunchGate()

    /** Home's first rows and pictures, asked while the launch screen holds; home takes them. */
    val homeLaunch = HomeLaunch(launch, settings.state, { provider }, { libraryVersion.value })

    /** The NAS browsers, and the sessions and mounts they keep between files. */
    val remoteBrowsers = RemoteBrowsers()
    val scanner = LibraryScanner(
        dao = database.dao(),
        tmdb = ::tmdb,
        interactiveTmdb = ::tmdbInteractive,
        scope = scope,
        artwork = artwork,
        playing = player.playing,
        browsers = remoteBrowsers::of,
        closeIdle = remoteBrowsers::closeIdle,
        started = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) },
    )
    val library = LocalLibrary(app, database.dao(), scanner, artwork, remoteBrowsers::forget)

    private val changes = LibraryChanges(scope, listOf(player.playbackEnded, scanner.changed))

    override val libraryVersion: StateFlow<Long> = changes.version

    override fun libraryChanged() = changes.userChanged()

    init {
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

    init {
        scope.launch { settings.ensureDeviceId() }
        // Passwords a Keystore at fault left in plain text (migration 6 to 7): sealed once it seals again.
        scope.launch { LibraryDatabase.sealPlainPasswords(database.dao(), Secrets.device) }
    }
}

/** Decoded pictures kept in memory: a few backdrops and the posters of several rows. */
private const val IMAGE_MEMORY_BYTES = 64 * 1024 * 1024

/**
 * The pictures' client: [api]'s connections and threads, with a queue of its own. OkHttp lets five
 * requests at a time go to a host and queues the rest; a Jellyfin server sends its pictures and its
 * answers from one host, so a screen of posters loading would hold a title's details, or what
 * "Lire" asks, behind them.
 */
internal fun imageHttpClient(api: OkHttpClient): OkHttpClient =
    api.newBuilder().dispatcher(Dispatcher(api.dispatcher.executorService)).build()
