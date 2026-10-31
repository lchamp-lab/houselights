package app.houselights.update

import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.houselights.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext

/** The key the fixtures' releases are signed with, and another one. */
internal const val PINNED = "ca0adf07526458f6cb3e57e4c58466c455b686f5843ae64d45a376a293eb4da8"
internal const val OTHER_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
internal const val REPO = "lchamp-lab/houselights"
internal const val APK_SIZE = 6_444_633L

/** 2027-01-15, the fixtures' wall clock at the test's start. */
internal const val START = 1_800_000_000_000L
internal const val MINUTE = 60_000L
internal const val HOUR = 60 * MINUTE
internal const val DAY = 24 * HOUR

internal fun downloads(tag: String) = "https://github.com/$REPO/releases/download/$tag/"

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

internal fun sha256(text: String) = sha256(text.toByteArray())

/** The release [tag] as GitHub lists it: its APK and manifest under its downloads, unless left out or moved. */
internal fun release(
    tag: String,
    prerelease: Boolean = tag.contains('-'),
    draft: Boolean = false,
    withApk: Boolean = true,
    withManifest: Boolean = true,
    size: Long = APK_SIZE,
    digest: String? = null,
    under: String = downloads(tag),
): ListedRelease {
    val name = tag.removePrefix("v")
    val apk = ListedAsset("Houselights-$name.apk", size, under + "Houselights-$name.apk", digest)
    val manifest = ListedAsset("update.json", 900, under + "update.json")
    val others = listOf(ListedAsset("Houselights.apk", size, under + "Houselights.apk"))
    return ListedRelease(tag, draft, prerelease, others + listOfNotNull(apk.takeIf { withApk }, manifest.takeIf { withManifest }))
}

/** [releases] as GitHub's API answers them, with fields the app does not read. */
internal fun listJson(vararg releases: ListedRelease): String = releases.joinToString(",", "[", "]") { r ->
    val assets = r.assets.joinToString(",", "[", "]") { a ->
        """{"name":"${a.name}","size":${a.size},"download_count":12,"browser_download_url":"${a.url}"""" +
            a.digest?.let { ""","digest":"$it"""" }.orEmpty() + "}"
    }
    """{"tag_name":"${r.tag}","name":"Houselights","draft":${r.draft},"prerelease":${r.prerelease},""" +
        """"published_at":"2026-10-20T10:00:00Z","author":{"login":"lchamp-lab"},"assets":$assets}"""
}

/** The manifest the release workflow writes for [tag]. */
internal fun manifestJson(
    tag: String,
    versionCode: Long,
    size: Long = APK_SIZE,
    sha256: String = "e7c4d01f728d387a087c6df07a90c93f1e82fb6a939a99127cdca8136c1b930a",
    cert: String = PINNED,
    schema: Int = 1,
    notes: String = """{"en":"- Forced subtitles are chosen again.","fr":"- Les sous-titres forcés sont de nouveau choisis."}""",
    versionName: String = tag.removePrefix("v"),
    prerelease: Boolean = tag.contains('-'),
    minSdk: Int = 28,
): String = """{"schema":$schema,"tag":"$tag","versionName":"$versionName","versionCode":$versionCode,""" +
    """"prerelease":$prerelease,"minSdk":$minSdk,"apk":{"name":"Houselights-${tag.removePrefix("v")}.apk","size":$size,""" +
    """"sha256":"$sha256"},"certSha256":"$cert","notes":$notes}"""

/** An offer as a check finds it. */
internal fun offer(tag: String = "v0.4.1", versionCode: Long = 1010, size: Long = APK_SIZE, sha256: String = "a".repeat(64)) =
    UpdateOffer(
        tag = tag,
        versionName = tag.removePrefix("v"),
        versionCode = versionCode,
        prerelease = tag.contains('-'),
        size = size,
        sha256 = sha256,
        url = downloads(tag) + "Houselights-${tag.removePrefix("v")}.apk",
    )

/** GitHub as a test sets it: its releases and their manifests (by address), or a failure. */
internal class FakeSource : ReleaseSource {
    var releases: List<ListedRelease> = emptyList()
    var manifests: Map<String, String> = emptyMap()
    var failure: Throwable? = null
    var lists = 0
    val etags = mutableListOf<String?>()

    /** Offers [tag] at [versionCode] (with the releases already listed). */
    fun publish(tag: String, versionCode: Long) {
        releases = listOf(release(tag)) + releases
        manifests = manifests + (downloads(tag) + "update.json" to manifestJson(tag, versionCode))
    }

    override suspend fun list(etag: String?): Listing {
        lists++
        etags += etag
        failure?.let { throw it }
        return Listing.Fresh(releases, "\"e$lists\"")
    }

    override suspend fun manifest(url: String): String = manifests[url] ?: throw IOException("Not found: $url")
}

/** Downloads that write a small file at once, or wait for [gate]; [failure] fails them. */
internal class FakeDownloads(private val dir: File) : Downloads {
    var gate: CompletableDeferred<Unit>? = null
    var failure: UpdateFailure? = null
    var fetched = 0
    var cancelled = 0

    override suspend fun fetch(offer: UpdateOffer, progress: (read: Long, total: Long) -> Unit): File {
        fetched++
        progress(offer.size / 3, offer.size)
        try {
            gate?.await()
        } catch (e: CancellationException) {
            cancelled++
            throw e
        }
        failure?.let { fail(it, "test") }
        dir.mkdirs()
        return File(dir, ReleaseChoice.apkName(offer.versionName)).apply { writeText("apk") }
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Android's installer as a test sets it: allowed or not, its settings screens, the sessions it holds, a session it refuses. */
internal class FakeInstaller : Installer {
    var allowed = true
    var screen = PermissionScreen.APP
    var opens = true

    /** Thrown once the session is written, before Android takes it, as a session Android refuses. */
    var failure: Throwable? = null
    val opened = mutableListOf<PermissionScreen>()
    val installed = mutableListOf<File>()
    val held = mutableSetOf<Int>()
    val confirmed = mutableListOf<Intent>()
    var abandons = 0
    private var next = 1

    /** What the store held as pending when the session was committed. */
    var pendingAtCommit: Pending? = null
    var store: UpdateStore? = null

    override fun allowed() = allowed

    override fun permissionScreen() = screen

    override fun open(screen: PermissionScreen): Boolean {
        opened += screen
        return opens
    }

    override suspend fun install(apk: File, beforeCommit: suspend (session: Int) -> Unit): Int {
        val id = next++
        beforeCommit(id)
        failure?.let { throw it }
        pendingAtCommit = store?.read()?.pending
        held += id
        installed += apk
        return id
    }

    override fun holds(session: Int) = session in held

    override fun confirm(intent: Intent): Boolean = confirmed.add(intent)

    override fun abandonAll() {
        abandons++
        held.clear()
    }
}

internal class FakeArchive(var facts: ArchiveFacts?) : ArchiveReader {
    override fun read(file: File) = facts
}

internal class FakeApp(
    var code: Long = 946,
    var name: String = "0.4.0",
    override val available: Boolean = true,
    override val packageName: String = UpdaterAvailability.RELEASE_PACKAGE,
) : InstalledApp {
    override fun versionCode() = code

    override fun versionName() = name
}

/**
 * An updater on the test's scheduler and clock ([START] plus its virtual time), its store in [folder]: GitHub, the
 * downloads and Android's installer faked, the app on screen with nothing playing nor scanning, the launch done.
 */
internal class UpdaterFixture(private val test: TestScope, folder: File) {
    val source = FakeSource()
    val downloads = FakeDownloads(folder.resolve("updates"))
    val installer = FakeInstaller()
    val app = FakeApp()
    val settings = MutableStateFlow(AppSettings(loaded = true))
    val onScreen = MutableStateFlow(true)
    val playing = MutableStateFlow(false)
    val scanning = MutableStateFlow(false)
    val store = UpdateStore(PreferenceDataStoreFactory.create(scope = test.backgroundScope) { folder.resolve("updates.preferences_pb") })
    val clock = { START + test.testScheduler.currentTime }

    init {
        installer.store = store
    }

    /** The archive the downloads hold: the offer's own, signed with the project's key, unless a test changes it. */
    val archive = FakeArchive(null)

    private var process: Job? = null
    lateinit var updater: Updater
        private set

    /** Starts the app's process: a new updater over what the store kept, run as the app runs it. */
    fun start(): Updater {
        process?.cancel()
        val scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
        updater = Updater(
            scope,
            settings,
            store,
            app,
            UpdateParts(source, ReleaseChoice(::downloads, PINNED, 28), downloads, UpdateVerifier(archive, PINNED), installer),
            UpdateMoments(onScreen, playing, scanning, launched = {}),
            clock = clock,
            io = EmptyCoroutineContext,
        )
        process = scope.coroutineContext[Job]
        scope.launch { updater.run() }
        test.runCurrent()
        return updater
    }

    /** 0.4.1 offered, the automatic check off, the sheet open: what « Mettre à jour » starts from. */
    fun offered(): UpdateOffer {
        source.publish("v0.4.1", 1010)
        settings.value = AppSettings(loaded = true, updateCheck = false)
        start()
        updater.checkNow()
        test.runCurrent()
        val offer = checkNotNull(updater.state.value.offer)
        genuine(offer)
        updater.opened()
        return offer
    }

    /** The archive [offer]'s download holds when it is the project's release. */
    fun genuine(offer: UpdateOffer) {
        archive.facts = ArchiveFacts(app.packageName, offer.versionCode, offer.versionName, listOf(PINNED))
    }
}
