package app.houselights

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.houselights.core.Http
import app.houselights.core.LibraryEvents
import app.houselights.core.MediaItem
import app.houselights.core.MediaProvider
import app.houselights.core.PlaybackOptions
import app.houselights.core.Secrets
import app.houselights.core.SourceConfig
import app.houselights.core.TrackPlan
import app.houselights.core.Typeset
import app.houselights.core.suspendRunCatching
import app.houselights.journal.HomeAddress
import app.houselights.player.PlaybackManager
import app.houselights.player.Redact
import app.houselights.player.TrackChooser
import app.houselights.player.ZidooPlayer
import app.houselights.player.ZidooSettings
import app.houselights.providers.jellyfin.DiscoveryAhead
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.JellyfinProvider
import app.houselights.providers.local.AndroidVolumes
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.DetailsLanguage
import app.houselights.providers.local.DiskAccess
import app.houselights.providers.local.DiskPermission
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalEditor
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.LocalProvider
import app.houselights.providers.local.NasDiscovery
import app.houselights.providers.local.RemoteBrowsers
import app.houselights.providers.local.TmdbClient
import app.houselights.providers.local.TrackChoiceEntity
import app.houselights.providers.local.Volumes
import app.houselights.providers.local.followDetailsLanguage
import app.houselights.providers.trakt.TraktAccount
import app.houselights.providers.trakt.TraktKeys
import app.houselights.providers.trakt.TraktSync
import app.houselights.settings.AppSettings
import app.houselights.settings.InterfaceLanguage
import app.houselights.settings.SettingsStore
import app.houselights.settings.SourceSwitcher
import app.houselights.settings.TraktFollow
import app.houselights.ui.design.components.AwaitAhead
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.FIELD_POSTERS
import app.houselights.ui.home.HomeLaunch
import app.houselights.ui.home.LibraryRows
import app.houselights.ui.home.UnseenPick
import app.houselights.ui.library.LibraryAhead
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.ui.util.RoomPosters
import app.houselights.update.ApkDownload
import app.houselights.update.GithubReleases
import app.houselights.update.PackageArchive
import app.houselights.update.ReleaseChoice
import app.houselights.update.SessionInstaller
import app.houselights.update.ThisApp
import app.houselights.update.UpdateMoments
import app.houselights.update.UpdateParts
import app.houselights.update.UpdateStore
import app.houselights.update.UpdateVerifier
import app.houselights.update.Updater
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class HouselightsApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    /** The language the app speaks, in its resources ([getResources]) and each activity's. */
    lateinit var language: InterfaceLanguage
        private set

    override fun onCreate() {
        super.onCreate()
        language = InterfaceLanguage(baseContext).also { it.applyDefault() }
        container = AppContainer(this, language)
    }

    override fun getResources(): Resources = if (::language.isInitialized) language.resources else super.getResources()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        language.applyDefault()
        container.deviceLocaleChanged(newConfig.locales[0])
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Coil trims its own memory cache, which the doors' cut posters outlive.
        container.cutPosters.trim(level)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory { ImageCalls(Http.client) }
        // A fixed budget: on a 2 GB player, a share of the large heap would hold 100 MB or more of pictures.
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes(IMAGE_MEMORY_BYTES).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(IMAGE_DISK_BYTES)
                .build()
        }
        .crossfade(IMAGE_CROSSFADE_MS)
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
        val uri = address.toUri()
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
class AppContainer(private val app: Application, val language: InterfaceLanguage) : LibraryEvents {
    /** Work that outlives screens (scans, playback reports); uncaught failures are logged, never fatal. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("Houselights", "Background task failed", e) },
    )
    val settings = SettingsStore(app, scope, Secrets.device(app))

    private val _deviceLocale = MutableStateFlow(InterfaceLanguage.deviceLocales()[0])

    /** The device's first language, whatever the app speaks; kept current by [HouselightsApp.onConfigurationChanged]. */
    val deviceLocale: StateFlow<Locale> = _deviceLocale

    fun deviceLocaleChanged(locale: Locale) {
        _deviceLocale.value = locale
    }

    /** TMDB's language: the one chosen in Settings, else the device's. */
    fun metadataLocale(): Locale = metadataLocale(settings.state.value, deviceLocale.value)

    /** Whether the app is on screen, whichever activity shows it. */
    private val onScreen = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }

    /** The Trakt account the folders' watch history goes to, its tokens in the settings. */
    val trakt = TraktAccount(
        TraktKeys.builtIn,
        session = { settings.state.value.trakt },
        save = settings::setTrakt,
        retries = scope,
        started = onScreen,
    )

    /** The only way to change the source: the current one stays active until the new one is saved. */
    val sources = SourceSwitcher({ settings.state.value }, settings::setSource, scope, keepUnopened = settings::setSourceKeepingUnopened)
    val database = LibraryDatabase.create(app, Secrets.device(app))

    /** Trakt follows the account and the source as the settings have them. */
    private val traktFollow = TraktFollow(settings.state)

    /** Keeps the folders' history and the Trakt account's together, in the background. */
    val traktSync = TraktSync(
        trakt,
        database.trakt(),
        scope,
        changed = ::libraryChanged,
        // Read at a playback's end too, which a process restarted meanwhile may report before the settings are followed.
        following = traktFollow::queues,
    )
    val artwork = ArtworkCache(app.filesDir.resolve("artwork"))

    /** The audio and subtitles asked of the player: Settings', or each title's own choice. */
    val tracks = TrackChooser(
        preferences = { settings.state.value.tracks },
        // The app's own language, which may not be the device's, in the device's region.
        locale = {
            Locale.Builder().setLanguage(app.resources.configuration.locales[0].language)
                .setRegion(deviceLocale.value.country).build()
        },
        load = { database.trackChoices().choice(it)?.choice() },
        save = { key, choice ->
            database.trackChoices().save(TrackChoiceEntity(key, choice.audio, choice.subtitles?.stored, System.currentTimeMillis()))
        },
        forget = { database.trackChoices().forget(it) },
        playerAudio = { ZidooSettings.audio(app.contentResolver) },
        // The player speaks the device's language, whatever the app's.
        deviceLanguage = { deviceLocale.value.language },
        playersOwn = { player.playersOwnTracks(it) },
    )

    /** The Zidoo player takes the tracks asked of it ([ZidooPlayer.takesTracks]); another ignores them. Debug builds show them anywhere. */
    val asksTracks: Boolean by lazy { BuildConfig.DEBUG || ZidooPlayer.takesTracks(ZidooPlayer(app).choose().player?.component) }

    /** The tracks Lire would ask of the player for [item], from the file it plays (its chosen copy); null when unknown. */
    suspend fun tracksOf(item: MediaItem): TrackPlan? {
        if (!asksTracks) return null
        val s = settings.state.value
        return provider.playedTracks(item, PlaybackOptions(s.pathMappings, s.forceHttp))?.let { tracks.table(it) }
    }

    /** Launches the external player for a title and follows it while it plays. */
    val player: PlaybackManager = PlaybackManager(app, scope, remember = tracks::remember) { resumeKey ->
        // After a process restart, the source is known only once the settings are loaded.
        settings.state.first { it.loaded }
        provider.reporter(resumeKey)
    }

    /** Playback outcomes as screens see them: ended, or why the title did not play. */
    val playback = PlaybackOutcome(player.playbackEnded)

    /** Holds the launch screen on a cold start while home loads underneath. */
    val launch = LaunchGate()

    /** The film home's case proposes when nothing is in progress, the same all session. */
    val unseenPick = UnseenPick()

    /** The posters home's doors cut their counts in, let go when the player or the system needs the memory. */
    val cutPosters = CutPosterCache()

    /** Prefetches a wall's first page and posters as its door on home is chosen. */
    val libraryAhead = LibraryAhead({ provider })

    /** Library-generated rows shown below home's own, chosen once a day. */
    val libraryRows = LibraryRows({ provider }, { settledVersion.value })

    /** The player's own disks a folder can be on: its storage, USB disks and SD cards, where Android mounts each now. */
    val volumes: Volumes = AndroidVolumes(app)

    /** Whether the app may read those disks, and where Android grants it. */
    val diskPermission = DiskPermission(app)

    /** NAS browsers, with the sessions and mounts they keep open between files, and the player's disks'. */
    val remoteBrowsers = RemoteBrowsers(volumes) { diskPermission.access() == DiskAccess.GRANTED }

    /** The NAS that announce themselves on the local network, for « Ajouter un dossier »'s first step. */
    val nasDiscovery = NasDiscovery(app)
    val scanner = LibraryScanner(
        dao = database.dao(),
        tmdb = ::tmdb,
        interactiveTmdb = ::tmdbInteractive,
        scope = scope,
        artwork = artwork,
        playing = player.playing,
        browsers = remoteBrowsers::of,
        closeIdle = remoteBrowsers::closeIdle,
        started = onScreen,
    )
    val library = LocalLibrary(app, database.dao(), scanner, artwork, remoteBrowsers::forget)

    /** This player's address on the home network ([HomeAddress]), the one a NAS's NFS rules allow; null without one. */
    suspend fun playerAddress(): String? = withContext(Dispatchers.IO) { HomeAddress.find(app)?.hostAddress }

    /** Jellyfin's sign-in on the network, its discovery started ahead as « Serveur Jellyfin » takes focus on Welcome. */
    val jellyfin = DiscoveryAhead(JellyfinNetwork.Lan, scope)

    /** Whether the folders hold one at least ([LocalLibrary.anyFolder]); null until read. */
    val hasFolders: StateFlow<Boolean?> = library.anyFolder.stateIn(scope, SharingStarted.Eagerly, null)

    /** Prefetches home's first rows and pictures while the launch screen holds. */
    val homeLaunch = HomeLaunch(
        launch,
        settings.state,
        hasFolders = { hasFolders.filterNotNull().first() },
        provider = { provider },
        version = { libraryVersion.value },
        pick = unseenPick,
    )

    private val changes = LibraryChanges(scope, listOf(player.playbackEnded, scanner.changed), scanner.progress)

    /** The app's own updates from its releases on GitHub, in the project's release alone (README, Install). */
    val updates: Updater = GithubReleases().let { releases ->
        Updater(
            scope,
            settings.state,
            UpdateStore(app),
            ThisApp(app),
            UpdateParts(
                releases,
                ReleaseChoice(releases::downloads, BuildConfig.RELEASE_CERT_SHA256, Build.VERSION.SDK_INT),
                ApkDownload(app.cacheDir.resolve("updates"), releases.http),
                UpdateVerifier(PackageArchive(app.packageManager), BuildConfig.RELEASE_CERT_SHA256),
                SessionInstaller(app, InstallResultReceiver::class.java),
            ),
            UpdateMoments(
                onScreen = onScreen.stateIn(scope, SharingStarted.Eagerly, false),
                playing = player.playing,
                scanning = scanner.state.map { it.running },
                launched = { launch.awaitOpen() },
            ),
        )
    }

    override val libraryVersion: StateFlow<Long> = changes.version

    override val settledVersion: StateFlow<Long> = changes.settled

    override fun libraryChanged() = changes.userChanged()

    init {
        scope.launch { retryOnTmdbKeyChange(settings.state, scanner::tmdbKeyChanged) }
        // Text from elsewhere is typeset for the language it comes in, whatever the app speaks.
        scope.launch {
            combine(settings.state, deviceLocale) { s, device ->
                (if (s.source == SourceConfig.Local) metadataLocale(s, device) else device).language
            }.collect { Typeset.language = it }
        }
        scope.launch { rewordOnLanguageChange(language.chosen, ::libraryChanged) }
        // Details read in another language than TMDB's are read again, folder by folder.
        scope.launch {
            val languages = combine(settings.state.filter { it.loaded }, deviceLocale) { s, device ->
                val refreshable = s.source == SourceConfig.Local && tmdbKey(s) != null
                DetailsLanguage(metadataLocale(s, device).toLanguageTag(), s.libraryLanguage, refreshable)
            }
            followDetailsLanguage(languages, scanner::refreshAllIn, ::libraryReadIn)
        }
    }

    /** Records the language the details are now in, or being read again in. */
    private suspend fun libraryReadIn(tag: String) {
        suspendRunCatching { settings.setLibraryLanguage(tag) }
            .onFailure { Log.w("Houselights", "The library's language could not be saved", it) }
    }

    @Volatile
    private var cached: Pair<SourceConfig, MediaProvider>? = null

    /** Provider of the active source; rebuilt when the source changes. */
    val provider: MediaProvider
        get() = providerOrNull() ?: error("No library source configured")

    /** Provider of the active source, or null while there is none (its connection could not be read back). */
    fun providerOrNull(): MediaProvider? {
        val source = settings.state.value.source ?: return null
        cached?.takeIf { it.first == source }?.let { return it.second }
        val created = when (source) {
            SourceConfig.Local -> {
                val editor = LocalEditor(database.dao(), scanner, ::tmdbInteractive)
                LocalProvider(app, database.dao(), editor, traktSync, volumes) { settings.state.value.collectionMinFilms }
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

    /** The posters the settings and their forms lie on, read once a visit. */
    val roomPosters = RoomPosters(
        source = { settings.state.first { it.loaded || it.readFailed }.source },
        latest = { library.latestPosters(FIELD_POSTERS).first() },
        hasFolders = { library.hasFolders() },
        trending = { widthPx -> trendingPosters(widthPx).map { LocalProvider.image(it, TmdbClient.Companion.ImageType.POSTER) } },
    )

    /** Poster URLs of trending films, or nothing when TMDB is unreachable. */
    suspend fun trendingPosters(widthPx: Int): List<String> = suspendRunCatching {
        val tmdb = tmdbInteractive() ?: return emptyList()
        tmdb.trendingPosterPaths().map { TmdbClient.imageUrl(it, TmdbClient.Companion.ImageType.POSTER, widthPx) }
    }.getOrDefault(emptyList())

    init {
        scope.launch { settings.ensureDeviceId() }
        // Seals passwords left in plain text by a failing Keystore (migration 6 to 7) once it works again.
        scope.launch { LibraryDatabase.sealPlainPasswords(database.dao(), Secrets.device(app)) }
        // Trakt follows the account and the source; the end of a scan reads it again.
        scope.launch { traktFollow.drive(traktSync) }
        scope.launch { scanner.passEnded.collect(traktSync::scanned) }
        // In the project's release alone: says what the last update did, then checks once a day while the app is on screen.
        scope.launch { updates.run() }
    }
}

private fun metadataLocale(s: AppSettings, device: Locale): Locale = s.metadataLanguage?.let(Locale::forLanguageTag) ?: device

/** Decoded pictures kept in memory: a few backdrops and the posters of several rows. */
internal const val IMAGE_MEMORY_BYTES = 64 * 1024 * 1024

/** Pictures kept on disk: 1 GB, shown as such in Settings > Storage. */
private const val IMAGE_DISK_BYTES = 1_000_000_000L

/** A picture arriving fades in over this long. */
private const val IMAGE_CROSSFADE_MS = 300

/**
 * Image client sharing [api]'s pool with its own queue: OkHttp runs five requests per host, and Jellyfin serves
 * images and API from one host, so posters would otherwise delay a title's details or playback. A redirect never
 * changes scheme: an NFO's HTTPS picture cannot send the player to an address in plain HTTP.
 */
internal fun imageHttpClient(api: OkHttpClient): OkHttpClient =
    api.newBuilder().dispatcher(Dispatcher(api.dispatcher.executorService)).followSslRedirects(false).build()
