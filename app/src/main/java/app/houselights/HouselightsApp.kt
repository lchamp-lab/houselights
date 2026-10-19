package app.houselights

import android.app.Application
import android.content.res.Configuration
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
import app.houselights.settings.AppSettings
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.home.HomeLaunch
import app.houselights.ui.library.LibraryAhead
import app.houselights.ui.design.components.AwaitAhead
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import app.houselights.ui.home.LibraryRows

class HouselightsApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        container.deviceLocaleChanged(newConfig.locales[0])
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { imageHttpClient(Http.client) }
        // A fixed budget: on a 2 GB player, a share of the large heap would hold 100 MB or more of pictures.
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(IMAGE_MEMORY_BYTES).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(1_000_000_000L) // 1 GB, shown as such in Settings > Storage.
                .build()
        }
        .crossfade(300)
        .respectCacheHeaders(false)
        // Waits for a picture the launch is already prefetching rather than loading it twice.
        .components { add(AwaitAhead) }
        .eventListener(ImageFailureLog())
        .build()
}

/** Logs failed pictures once a minute per origin, redacted ([Redact]): a sleeping NAS fails a whole screen. */
internal class ImageFailureLog(private val clock: () -> Long = SystemClock::elapsedRealtime) : EventListener {
    private val toldAt = ConcurrentHashMap<String, Long>()

    override fun onError(request: ImageRequest, result: ErrorResult) {
        // A request without data is not a failure.
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

        /** How long an origin stays silent after a logged failure. */
        const val QUIET_MS = 60_000L
    }
}

/** Manual dependency container, created once per process. */
class AppContainer(private val app: Application) : LibraryEvents {
    /** Work that outlives screens (scans, playback reports); uncaught failures are logged, never fatal. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("Houselights", "Background task failed", e) }
    )
    val settings = SettingsStore(app, scope, Secrets.device)

    /** The device's first language, kept current by [HouselightsApp.onConfigurationChanged]. */
    private val deviceLocale = MutableStateFlow(app.resources.configuration.locales[0])

    fun deviceLocaleChanged(locale: Locale) {
        deviceLocale.value = locale
    }

    /** TMDB's language: the one chosen in Settings, else the device's. */
    fun metadataLocale(): Locale = metadataLocale(settings.state.value, deviceLocale.value)

    /** The only way to change the source: the current one stays active until the new one is saved. */
    val sources = SourceSwitcher({ settings.state.value }, { settings.setSource(it) }, scope)
    val database = LibraryDatabase.create(app, Secrets.device)
    val artwork = ArtworkCache(app.filesDir.resolve("artwork"))
    /** Launches the external player for a title and follows it while it plays. */
    val player = PlaybackManager(app, scope) { resumeKey ->
        // After a process restart, the source is known only once the settings are loaded.
        settings.state.first { it.loaded }
        provider.reporter(resumeKey)
    }

    /** Playback outcomes as screens see them: ended, or why the title did not play. */
    val playback = PlaybackOutcome(player.playbackEnded)

    /** Holds the launch screen on a cold start while home loads underneath. */
    val launch = LaunchGate()

    /** Prefetches home's first rows and pictures while the launch screen holds. */
    val homeLaunch = HomeLaunch(launch, settings.state, { provider }, { libraryVersion.value })

    /** Prefetches a wall's first page and posters while home's pill moves to it. */
    val libraryAhead = LibraryAhead({ provider })

    /** Library-generated rows shown below home's own, chosen once a day. */
    val libraryRows = LibraryRows({ provider })

    /** NAS browsers, with the sessions and mounts they keep open between files. */
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
        refreshedIn = { libraryReadIn(it.toLanguageTag()) },
    )
    val library = LocalLibrary(app, database.dao(), scanner, artwork, remoteBrowsers::forget)

    private val changes = LibraryChanges(scope, listOf(player.playbackEnded, scanner.changed))

    override val libraryVersion: StateFlow<Long> = changes.version

    override fun libraryChanged() = changes.userChanged()

    init {
        // A new TMDB key (or back to the built-in one) forgets the old key's results and retries unmatched titles.
        scope.launch {
            settings.state.filter { it.loaded }.map { it.tmdbApiKey }.distinctUntilChanged().drop(1).collect {
                if (settings.state.value.source == SourceConfig.Local) scanner.tmdbKeyChanged()
            }
        }
        // Details read in another language than TMDB's are all read again; a refresh cut short resumes at launch.
        scope.launch {
            combine(settings.state.filter { it.loaded }, deviceLocale) { s, device ->
                val refreshable = s.source == SourceConfig.Local && tmdbKey(s) != null
                Triple(metadataLocale(s, device).toLanguageTag(), s.libraryLanguage, refreshable)
            }.distinctUntilChanged().collect { (wanted, read, refreshable) ->
                when {
                    // Until a language is recorded, the folders were read in the one in use.
                    read == null -> libraryReadIn(wanted)
                    wanted != read && refreshable -> scanner.scanAll(refreshMetadata = true)
                }
            }
        }
    }

    /** Records the language all details are now in. */
    private suspend fun libraryReadIn(tag: String) {
        suspendRunCatching { settings.setLibraryLanguage(tag) }
            .onFailure { Log.w("Houselights", "The library's language could not be saved", it) }
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

    /** TMDB key in use: the one saved in Settings, else the one built into the APK. */
    fun tmdbKey(s: AppSettings = settings.state.value): String? = s.tmdbApiKey ?: BuildConfig.TMDB_API_KEY.ifBlank { null }

    @Volatile
    private var tmdbClient: TmdbClient? = null

    @Volatile
    private var tmdbInteractiveClient: TmdbClient? = null

    /** TMDB client for scans, kept while key and language hold; fails fast for a while when TMDB is unreachable. */
    fun tmdb(): TmdbClient? {
        val key = tmdbKey()?.takeIf { it.isNotBlank() } ?: return null
        val locale = metadataLocale()
        tmdbClient?.takeIf { it.fits(key, locale) }?.let { return it }
        return TmdbClient(key, pauseWhenUnreachable = true, locale = locale).also { tmdbClient = it }
    }

    /** TMDB client for interactive requests (search, identification, artwork): it never pauses. */
    fun tmdbInteractive(): TmdbClient? {
        val key = tmdbKey()?.takeIf { it.isNotBlank() } ?: return null
        val locale = metadataLocale()
        tmdbInteractiveClient?.takeIf { it.fits(key, locale) }?.let { return it }
        return TmdbClient(key, locale = locale).also { tmdbInteractiveClient = it }
    }

    /** Poster URLs of trending films, or nothing when TMDB is unreachable. */
    suspend fun trendingPosters(widthPx: Int): List<String> = suspendRunCatching {
        val tmdb = tmdbInteractive() ?: return emptyList()
        tmdb.trendingPosterPaths().map { TmdbClient.imageUrl(it, TmdbClient.Companion.ImageType.POSTER, widthPx) }
    }.getOrDefault(emptyList())

    init {
        scope.launch { settings.ensureDeviceId() }
        // Seals passwords left in plain text by a failing Keystore (migration 6 to 7) once it works again.
        scope.launch { LibraryDatabase.sealPlainPasswords(database.dao(), Secrets.device) }
    }
}

private fun metadataLocale(s: AppSettings, device: Locale): Locale = s.metadataLanguage?.let(Locale::forLanguageTag) ?: device

/** Decoded pictures kept in memory: a few backdrops and the posters of several rows. */
private const val IMAGE_MEMORY_BYTES = 64 * 1024 * 1024

/**
 * Image client sharing [api]'s pool with its own queue: OkHttp runs five requests per host, and Jellyfin serves
 * images and API from one host, so posters would otherwise delay a title's details or playback.
 */
internal fun imageHttpClient(api: OkHttpClient): OkHttpClient =
    api.newBuilder().dispatcher(Dispatcher(api.dispatcher.executorService)).build()
