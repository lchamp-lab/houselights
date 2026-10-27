package app.houselights.ui.onboarding

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.providers.jellyfin.AuthRequest
import app.houselights.providers.jellyfin.AuthResult
import app.houselights.providers.jellyfin.DiscoveredServer
import app.houselights.providers.jellyfin.JellyfinNetwork
import app.houselights.providers.jellyfin.PublicSystemInfo
import app.houselights.providers.jellyfin.ServerCheck
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.LibraryDatabase
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.settings.SourceSwitcher
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.TwoPaneDefaults
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.setTvContent
import app.houselights.ui.testing.testSettings
import app.houselights.ui.util.wholePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

/**
 * The forms on the TV canvas, in French and German (DESIGN.md, Secondary screens): a folder's and Jellyfin's, two
 * servers found, hold whole in the safe area with their actions' focus, their fields [HlSize.field] tall, the form
 * from the top of the safe area beside its column.
 */
@RunWith(RobolectricTestRunner::class)
// Real fonts, so lines break as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val db: LibraryDatabase by lazy { testDatabase { onCallingThread() } }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    @Config(qualifiers = "fr-$TV")
    fun aFolderInFrench() = aFolderHolds()

    @Test
    @Config(qualifiers = "de-$TV")
    fun aFolderInGerman() = aFolderHolds()

    @Test
    @Config(qualifiers = "fr-$TV")
    fun jellyfinInFrench() = jellyfinHolds()

    @Test
    @Config(qualifiers = "de-$TV")
    fun jellyfinInGerman() = jellyfinHolds()

    private fun library(): LocalLibrary {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        return LocalLibrary(context, db.dao(), scanner, artwork) {}
    }

    /** « Modifier le dossier » over an NFS folder: its first control, a field and Save where the form's frame puts them. */
    private fun aFolderHolds() {
        val id = runBlocking {
            db.dao().insertSource(
                SourceEntity(
                    name = "Films",
                    kind = SourceKind.MOVIES,
                    protocol = Protocol.NFS,
                    host = "192.168.1.20",
                    share = "volume1/video",
                    path = "Films",
                    username = "",
                    password = Secret(""),
                ),
            )
        }
        val vm = AddFolderViewModel(library(), id, scope, probe = { FolderLevel(emptyList(), videos = 0) })
        compose.setTvContent { AddFolderForm(vm, FolderField.FIRST) {} }
        compose.waitUntil { vm.ready }
        compose.waitForIdle()
        holds(first = R.string.field_protocol, field = R.string.field_host, action = R.string.save)
    }

    /** The boards' « Séries », over SMB with its password kept, opened to edit; its test answers [probe]. */
    private fun series(probe: suspend (SourceEntity) -> FolderLevel): AddFolderViewModel {
        val id = runBlocking {
            db.dao().insertSource(
                SourceEntity(
                    name = "Séries",
                    kind = SourceKind.SHOWS,
                    protocol = Protocol.SMB,
                    host = "192.168.1.20",
                    share = "video",
                    path = "Séries",
                    username = "houselights",
                    password = Secret("stored"),
                ),
            )
        }
        return AddFolderViewModel(library(), id, scope, probe = probe)
    }

    /**
     * « Modifier le dossier » at the largest text, the NAS refusing its password: the refusal under « Mot de passe »,
     * in view, and the column beside it whole, its help left out where it does not fit, never cut by the screen's foot.
     */
    @Test
    @Config(qualifiers = "fr-$TV")
    fun anEditedFolderAtTheLargestTextCutsNothing() {
        val vm = series { throw IOException("STATUS_LOGON_FAILURE (0xc000006d): Authentication failed") }
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGEST_TEXT)) {
                AddFolderForm(vm, FolderField.FIRST) {}
            }
        }
        compose.waitUntil { vm.ready }
        vm.test()
        compose.waitUntil { vm.check != null }
        compose.waitForIdle()
        fun text(id: Int) = compose.activity.getString(id)
        val refused = text(R.string.error_login_failed)
        listOf(text(R.string.edit_folder_desc), text(R.string.connection_failed), "/video/Séries", refused).forEach {
            assertFalse("$it cut", cut(it))
        }
        val message = compose.onNodeWithText(refused).getUnclippedBoundsInRoot()
        assertTrue("the refusal in view: $message", message.bottom <= SCREEN_H - HlSpace.safeV)
        helpWholeOrAbsent(text(R.string.smb_hint))
    }

    /** At rest, at the standard size, the help reads whole under the panel. */
    @Test
    @Config(qualifiers = "fr-$TV")
    fun anEditedFolderAtRestShowsItsHelpWhole() {
        val vm = series { FolderLevel(emptyList(), videos = 12) }
        compose.setTvContent { AddFolderForm(vm, FolderField.FIRST) {} }
        compose.waitUntil { vm.ready }
        compose.waitForIdle()
        val help = compose.activity.getString(R.string.smb_hint)
        assertFalse("$help cut", cut(help))
        assertTrue(compose.onNodeWithText(help).getUnclippedBoundsInRoot().bottom <= SCREEN_H - HlSpace.safeV)
    }

    /** [help], when shown, ends inside the safe area, whole; left out, it is not placed at all. */
    private fun helpWholeOrAbsent(help: String) {
        if (compose.onAllNodesWithText(help).fetchSemanticsNodes().none { it.layoutInfo.isPlaced }) return
        assertFalse("$help cut", cut(help))
        val box = compose.onNodeWithText(help).getUnclippedBoundsInRoot()
        assertTrue("$box over the safe area's foot", box.bottom <= SCREEN_H - HlSpace.safeV)
    }

    private fun jellyfinHolds() {
        val vm = runBlocking {
            val settings = testSettings(folder.root, scope)
            val sources = SourceSwitcher({ settings.state.value }, { _, _ -> }, scope, signOut = { _, _ -> })
            JellyfinLoginViewModel(settings, sources, TwoServers)
        }
        compose.setTvContent { JellyfinLoginForm(vm) {} }
        compose.waitForIdle()
        compose.onNodeWithText("Bureau").assertExists()
        holds(first = R.string.servers_found, field = R.string.field_server, action = R.string.sign_in)
    }

    /**
     * The form from the top of the safe area, its [first] words there; its [field] where the grid puts it, [HlSize.field]
     * tall, and its [action] framed inside the safe area.
     */
    private fun holds(first: Int, field: Int, action: Int) {
        fun text(id: Int) = compose.activity.getString(id)
        val top = compose.onNodeWithText(text(first)).getUnclippedBoundsInRoot().top
        assertEquals("the form from the safe area's top: $top", HlSpace.safeV.value, top.value, TOP)
        val box = compose.onNodeWithContentDescription(text(field)).getUnclippedBoundsInRoot()
        assertEquals("$box", HlSize.field.value, box.height.value, PX)
        assertEquals("the form beside its column: $box", (HlSpace.safeH + TwoPaneDefaults.sideWide + HlSpace.xxl).value, box.left.value, PX)
        assertEquals("the form to the safe area: $box", (SCREEN_W - HlSpace.safeH).value, box.right.value, PX)
        val button = compose.onNodeWithText(text(action)).getUnclippedBoundsInRoot()
        val reach = HlFocus.reach
        val framed = DpRect(button.left - reach, button.top - reach, button.right + reach, button.bottom + reach)
        assertTrue("$framed inside the safe area", framed.bottom <= SCREEN_H - HlSpace.safeV && framed.right <= SCREEN_W - HlSpace.safeH)
    }

    @Test
    @Config(qualifiers = "fr-$TV")
    fun foundServersInFrenchAtTheLargestText() = foundServersHoldAtTheLargestText()

    @Test
    @Config(qualifiers = "de-$TV")
    fun foundServersInGermanAtTheLargestText() = foundServersHoldAtTheLargestText()

    // The languages whose words run longest or widest.
    @Test
    @Config(qualifiers = "pt-$TV")
    fun foundServersInPortugueseAtTheLargestText() = foundServersHoldAtTheLargestText()

    @Test
    @Config(qualifiers = "nl-$TV")
    fun foundServersInDutchAtTheLargestText() = foundServersHoldAtTheLargestText()

    @Test
    @Config(qualifiers = "pl-$TV")
    fun foundServersInPolishAtTheLargestText() = foundServersHoldAtTheLargestText()

    @Test
    @Config(qualifiers = "ja-$TV")
    fun foundServersInJapaneseAtTheLargestText() = foundServersHoldAtTheLargestText()

    /**
     * At the largest text, the states take the widest of their words; names and addresses hold whole beside them, and
     * the connection a server picked makes holds whole beside its protocol in the panel.
     */
    private fun foundServersHoldAtTheLargestText() {
        val vm = runBlocking {
            val settings = testSettings(folder.root, scope)
            val sources = SourceSwitcher({ settings.state.value }, { _, _ -> }, scope, signOut = { _, _ -> })
            JellyfinLoginViewModel(settings, sources, FourServers)
        }
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGEST_TEXT)) { JellyfinLoginForm(vm) {} }
        }
        compose.waitUntil { FourServers.servers.all { vm.checkOf(it) != ServerCheck.Checking || it.name == "Chambre" } }
        compose.waitForIdle()
        val states = listOf(R.string.server_encrypted, R.string.server_plain, R.string.checking, R.string.server_silent)
            .map { compose.activity.getString(it) }
        val starts = states.map { compose.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot().left }
        assertEquals("one column: $starts", 1, starts.toSet().size)
        val names = FourServers.servers.mapNotNull { it.name }
        val addresses = listOf("jellyfin.mondomaine.fr", "192.168.1.31:8096", "192.168.1.42:8096", "192.168.1.57:8096")
        (states + names + addresses).forEach { assertFalse("$it cut", cut(it)) }
        mapOf(0 to R.string.connection_is_encrypted, 1 to R.string.connection_is_plain).forEach { (i, connection) ->
            vm.choose(FourServers.servers[i])
            compose.waitForIdle()
            val words = compose.activity.getString(connection)
            assertFalse("$words cut", cut(words))
        }
    }

    @Test
    @Config(qualifiers = "fr-$TV")
    fun theFlowsColumnInFrenchAtTheLargestText() = theFlowsColumnHoldsAtTheLargestText()

    @Test
    @Config(qualifiers = "de-$TV")
    fun theFlowsColumnInGermanAtTheLargestText() = theFlowsColumnHoldsAtTheLargestText()

    /**
     * « Ajouter un dossier » at the largest text, on its longest message (an export refusing this player): the step's
     * sentence and the panel hold whole in the left column, the panel ending inside the safe area, the path unbroken.
     */
    private fun theFlowsColumnHoldsAtTheLargestText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
        val idle = CoroutineScope(Job().apply { cancel() })
        val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
        runBlocking {
            db.dao().insertSource(
                SourceEntity(
                    name = "Films",
                    kind = SourceKind.MOVIES,
                    protocol = Protocol.NFS,
                    host = FakeFlowNetwork.DS920,
                    share = "volume1/video",
                    path = "Films",
                    username = "",
                    password = Secret(""),
                ),
            )
        }
        val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
        val flow = FolderFlow(FakeFlowNetwork(), library, scope, useFolders = { false })
        compose.setTvContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, LARGEST_TEXT)) { AddFolderFlow(flow) }
        }
        // The flow's work on other threads comes back on the main looper, which compose's waitUntil does not run here.
        fun until(done: () -> Boolean) {
            val end = System.currentTimeMillis() + FLOW_WAIT_MS
            while (!done() && System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
            }
            assertTrue("the flow went on", done())
        }
        until { flow.ready }
        flow.chooseUsed(flow.used.single())
        until { flow.step == FlowStep.SHARES }
        flow.openShare(FlowShare("/volume1/photo"))
        until { flow.message != null }
        compose.waitForIdle()
        val said = compose.activity.getString(R.string.error_nfs_refused, FakeFlowNetwork.PLAYER, wholePath("/volume1/photo"))
        val box = compose.onNodeWithText(said, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the panel ends inside the safe area: $box", box.bottom + HlInset.plate <= SCREEN_H - HlSpace.safeV)
        listOf(said, compose.activity.getString(R.string.flow_share_desc)).forEach { assertFalse("$it cut", cut(it)) }
    }

    /** True when [text]'s own layout runs past its box or ends in an ellipsis, wherever it is (a row and the panel). */
    private fun cut(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().any { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.first()
            layout.hasVisualOverflow || (0 until layout.lineCount).any(layout::isLineEllipsized)
        }

    /** Two servers answer discovery, checked for ever; none signs in. */
    private object TwoServers : JellyfinNetwork {
        override fun discover() = flowOf(
            DiscoveredServer("http://192.168.1.20:8096", "Jellyfin du salon"),
            DiscoveredServer("http://192.168.1.31:8096", "Bureau"),
        )

        override suspend fun check(found: DiscoveredServer): ServerCheck = awaitCancellation()

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> = throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    /** The board's four servers: one encrypted on its own name, one plain, one checked for ever, one silent. */
    private object FourServers : JellyfinNetwork {
        val servers = listOf(
            DiscoveredServer("https://jellyfin.mondomaine.fr", "Salon", "a"),
            DiscoveredServer("http://192.168.1.31:8096", "NAS du bureau", "b"),
            DiscoveredServer("http://192.168.1.42:8096", "Chambre", "c"),
            DiscoveredServer("http://192.168.1.57:8096", "Ancien NAS", "d"),
        )

        override fun discover() = servers.asFlow()

        override suspend fun check(found: DiscoveredServer): ServerCheck = when (found.name) {
            "Salon" -> ServerCheck.Encrypted(found.address)
            "NAS du bureau" -> ServerCheck.Plain(found.address)
            "Ancien NAS" -> ServerCheck.Silent
            else -> awaitCancellation()
        }

        override suspend fun reach(input: String, deviceId: String): Pair<String, PublicSystemInfo> = throw IOException("offline")

        override suspend fun authenticate(url: String, deviceId: String, request: AuthRequest): AuthResult = throw IOException("offline")
    }

    private companion object {
        val SCREEN_W = 960.dp
        val SCREEN_H = 540.dp

        /** Bounds are whole test pixels. */
        const val PX = 1f

        /** Words whose box starts at their letters; a group's title, set from its baseline, starts up to 4 lower. */
        const val TOP = 4f

        /** The largest text size Android TV offers. */
        const val LARGEST_TEXT = 1.3f

        /** How long « Ajouter un dossier » may take to answer its fake network. */
        const val FLOW_WAIT_MS = 5_000L
    }
}
