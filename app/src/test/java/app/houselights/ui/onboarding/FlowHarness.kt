package app.houselights.ui.onboarding

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FoundNas
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.testing.secretsOf
import app.houselights.testing.testKey
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.DS920
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.PASSWORD
import app.houselights.ui.onboarding.FakeFlowNetwork.Companion.USER
import app.houselights.ui.testing.press
import app.houselights.ui.testing.setTvContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.robolectric.Shadows.shadowOf
import java.security.ProviderException

/**
 * « Ajouter un dossier » drawn over the board's network (FakeFlowNetwork) and driven from the remote: the flow over a
 * library, the remote's keys, and what each test waits for. [AddFolderFlowTest], [FlowAccountPathTest],
 * [DiskFolderEditTest], [FolderPickTest].
 */
open class FlowHarness {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The device's Keystore fails while set, as a restarting keystore daemon does: what a past launch sealed is not opened. */
    @Volatile
    protected var keystoreDown = false

    private val key = testKey()

    protected val db: LibraryDatabase by lazy {
        testDatabase(secretsOf { if (keystoreDown) throw ProviderException("Keystore operation failed") else key }) { onCallingThread() }
    }

    protected val context: Context get() = ApplicationProvider.getApplicationContext()

    protected val network = FakeFlowNetwork()

    /** How the flow ended, once it did. */
    protected var done: FlowDone? = null

    /** Back left the flow from its first step. */
    protected var left = false

    /** The folder edited Back left with, whose sheet opens again; null for an add, or a folder the flow could not open. */
    protected var leftWith: Long? = null

    /** The shared folder chosen at step 4 over the form of « Modifier le dossier » ([FolderFlow.picking]); null before. */
    protected var picked: String? = null

    protected lateinit var library: LocalLibrary

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    protected fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * The flow over a library holding [folders], drawn and settled; [saved] as a stopped process left it, [useFolders]
     * making the folders the source, [askDisk] asking Android to read the player's disks; with [editing], « Modifier le
     * dossier » on that folder; with [pick], step 4 over its form, the folder chosen in [picked].
     */
    protected fun flow(
        vararg folders: SourceEntity,
        saved: SavedStateHandle = SavedStateHandle(),
        useFolders: suspend () -> Boolean = { false },
        askDisk: DiskAsk = DiskAsk.NONE,
        editing: Long? = null,
        pick: SharePick? = null,
    ): FolderFlow {
        stock(*folders)
        val flow = FolderFlow(network, library, scope, useFolders = useFolders, saved = saved, editing = editing, pick = pick)
        val leave: (Long?) -> Unit = {
            left = true
            leftWith = it
        }
        compose.setTvContent {
            AddFolderFlow(flow, onLeave = leave, onDone = { done = it }, askDisk = askDisk, onPick = { picked = it })
        }
        until { flow.ready }
        compose.waitForIdle()
        return flow
    }

    /** The [library] holding [folders], whose scans never run. */
    protected fun stock(vararg folders: SourceEntity) {
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        // The scan asked after an add never runs: it would go to the network.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        runBlocking { folders.forEach { db.dao().insertSource(it) } }
        library = LocalLibrary(context, db.dao(), scanner, artwork) {}
    }

    /**
     * Waits for [done], running the main looper as it goes: the flow's work that went to another thread (a save, this
     * player's address) comes back there, which compose's own waitUntil does not run under Robolectric.
     */
    protected fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (done()) return
            check(System.currentTimeMillis() < end) { "Still waiting after $WAIT_MS ms" }
            Thread.sleep(POLL_MS)
        }
    }

    protected fun focusIsOn(words: String) = compose.onNode(isFocused() and hasText(words, substring = true)).assertExists()

    protected fun ok() = compose.press(Key.DirectionCenter)

    protected fun down(times: Int = 1) = compose.press(Key.DirectionDown, times)

    /** Back, once what the flow was told is drawn, as a remote's Back always comes after a frame. */
    protected fun back() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Films, NFS, on DS920: the library of a second folder. */
    protected val films = SourceEntity(
        name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = DS920, share = "volume1/video", path = "Films",
        username = "", password = Secret(""), uid = 1026, gid = 100,
    )

    /** Films over SMB with the account kept. */
    protected val smbFilms =
        films.copy(protocol = Protocol.SMB, share = "video", username = USER, password = Secret(PASSWORD), uid = null, gid = null)

    /**
     * [folder] with its password as a past launch sealed it, under the same key: read from the Keystore, never from what
     * this launch opened, so that it is not opened while [keystoreDown].
     */
    protected fun sealedBefore(folder: SourceEntity): SourceEntity =
        folder.copy(password = Secret("", unopened = secretsOf { key }.seal(folder.password.value)))

    /**
     * [folder] with its password as the next launch reads it: sealed under the key's next version, which this launch
     * never loaded, so that it is not opened while [keystoreDown] even once this launch opened what it sealed itself, as
     * the library's Secrets keeps a key it opened with until it fails, as one launch does.
     */
    protected fun sealedForTheNextLaunch(folder: SourceEntity): SourceEntity =
        folder.copy(password = Secret("", unopened = secretsOf(1, 2) { key }.seal(folder.password.value)))

    protected val ds920 = FoundNas("DS920", DS920, setOf(Protocol.SMB, Protocol.NFS))
}

/** How long a test waits for the flow, and how often it looks. */
private const val WAIT_MS = 5_000L
private const val POLL_MS = 10L
