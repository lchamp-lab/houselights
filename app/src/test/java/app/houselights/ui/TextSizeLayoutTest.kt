package app.houselights.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.houselights.R
import app.houselights.core.AudioTrack
import app.houselights.core.Codecs
import app.houselights.core.HdrFormat
import app.houselights.core.MediaInfo
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.Person
import app.houselights.core.SourceConfig
import app.houselights.core.SubtitleTrack
import app.houselights.core.VideoInfo
import app.houselights.providers.local.ArtworkCache
import app.houselights.providers.local.FakeRemoteBrowser
import app.houselights.providers.local.FolderLevel
import app.houselights.providers.local.LibraryScanner
import app.houselights.providers.local.LocalLibrary
import app.houselights.providers.local.Protocol
import app.houselights.providers.local.Secret
import app.houselights.providers.local.SourceEntity
import app.houselights.providers.local.SourceKind
import app.houselights.providers.local.onCallingThread
import app.houselights.providers.local.testDatabase
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.TEXT_SCALE_MAX
import app.houselights.ui.design.TEXT_SCALE_MIN
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.HlTwoPane
import app.houselights.ui.design.components.LocalCutPosters
import app.houselights.ui.design.components.ScreenTitle
import app.houselights.ui.design.rememberFocusMemory
import app.houselights.ui.detail.DetailPage
import app.houselights.ui.detail.DetailViewModel
import app.houselights.ui.home.HomeGrid
import app.houselights.ui.home.fullHome
import app.houselights.ui.onboarding.AddFolderForm
import app.houselights.ui.onboarding.AddFolderViewModel
import app.houselights.ui.onboarding.FolderField
import app.houselights.ui.settings.LocalSettingsViewModel
import app.houselights.ui.settings.Pane
import app.houselights.ui.settings.PaneRows
import app.houselights.ui.settings.SettingsFixture
import app.houselights.ui.settings.SettingsPlaces
import app.houselights.ui.settings.displayPane
import app.houselights.ui.settings.panesFor
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.testing.TV
import app.houselights.ui.testing.cutTexts
import app.houselights.ui.util.PlaybackOutcome
import app.houselights.testing.folderNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DESIGN.md, One canvas: at the ends of the text sizes the app lays out at, home, a title's page, the settings and a
 * folder's form cut nothing at the smallest (0.75) that the largest (1.3) or the standard size does not already cut; the
 * form's help under its panel reads whole or is left out, never cut by the screen's foot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-$TV")
// Real fonts, so texts measure as on the device.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextSizeLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The system's text size, which the theme multiplies by the app's step. */
    private var system by mutableFloatStateOf(1f)

    @After
    fun stop() = work.cancel()

    /** [content] in the app's theme at the system's text size, changed in place as the setting changes it. */
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, system)) {
            CompositionLocalProvider(LocalCutPosters provides remember { CutPosterCache() }) { HouselightsTheme(content = content) }
        }
    }

    /** What is cut at [scale]. */
    private fun cutAt(scale: Float): Set<String> {
        system = scale
        compose.waitForIdle()
        return compose.cutTexts()
    }

    private fun assertTheSmallestCutsNothingNew() {
        val largest = cutAt(TEXT_SCALE_MAX)
        val standard = cutAt(1f)
        val smallest = cutAt(TEXT_SCALE_MIN)
        assertTrue("cut at 0.75, not at 1.3: ${smallest - largest}", (smallest - largest).isEmpty())
        assertTrue("cut at 0.75, not at 1: ${smallest - standard}", (smallest - standard).isEmpty())
    }

    /** « Modifier le dossier » for an NFS folder at rest: its help whole at every size, or left out where it does not fit. */
    @Test
    fun aFoldersForm() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = testDatabase { onCallingThread() }
        try {
            val artwork = ArtworkCache(context.cacheDir.resolve("artwork"))
            val idle = CoroutineScope(Job().apply { cancel() })
            val scanner = LibraryScanner(db.dao(), { null }, idle, artwork, browsers = { FakeRemoteBrowser() }, closeIdle = {})
            val library = LocalLibrary(context, db.dao(), scanner, artwork) {}
            val id = runBlocking {
                db.dao().insertSource(
                    SourceEntity(
                        name = "Films", kind = SourceKind.MOVIES, protocol = Protocol.NFS, host = "192.168.1.20",
                        share = "volume1/video", path = "Films", username = "", password = Secret(""),
                    )
                )
            }
            val vm = AddFolderViewModel(
                library, id, work, probe = { FolderLevel(folderNames(412), videos = 3) },
                playerAddress = { "192.168.1.42" },
            )
            show { AddFolderForm(vm, FolderField.FIRST) {} }
            compose.waitUntil { vm.ready && vm.player != null }
            assertTheSmallestCutsNothingNew()
            val help = context.getString(R.string.nfs_hint_player, "192.168.1.42")
            listOf(TEXT_SCALE_MAX, 1f, TEXT_SCALE_MIN).forEach { scale ->
                system = scale
                compose.waitForIdle()
                // Left out where it does not fit whole: measured, never placed.
                val shown = compose.onAllNodesWithText(help).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
                if (shown.isNotEmpty()) {
                    assertFalse("the help cut at $scale", help in compose.cutTexts())
                    val box = compose.onNodeWithText(help).getUnclippedBoundsInRoot()
                    assertTrue("the help past the safe area at $scale: $box", box.bottom <= SCREEN_H - HlSpace.safeV)
                }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun home() {
        show { HomeGrid(fullHome()) }
        assertTheSmallestCutsNothingNew()
    }

    @Test
    fun aTitlesPage() {
        val provider = FakeProvider().apply { items[FILM.id] = FILM }
        val vm = DetailViewModel(FILM.id, { provider }, FakeEvents(), work, tracksOf = { null })
        show {
            DetailPage(
                vm,
                PlaybackOutcome(MutableSharedFlow()),
                screen = "page",
                onPlay = { _, _, _ -> },
                onOpenItem = {},
                onEditArtwork = { _, _ -> },
                onIdentify = {},
                onOpenPerson = {},
            )
        }
        assertTheSmallestCutsNothingNew()
    }

    /** The settings on their display pane, « Taille du texte » among its rows. */
    @Test
    fun theSettings() {
        val settings = SettingsFixture(folder.root)
        try {
            val panes = panesFor(SourceConfig.Local, pathMapping = false, counts = true, tracks = true, trakt = false)
            show {
                val s by settings.vm.settings.collectAsState()
                val memory = rememberFocusMemory()
                HlTwoPane(
                    side = {
                        ScreenTitle(stringResource(R.string.settings_title))
                        Spacer(Modifier.height(HlSpace.l))
                        SettingsPlaces(panes, Pane.Display, memory, ready = true) {}
                    },
                ) {
                    val rows = remember { PaneRows(memory, Pane.Display.name) }
                    CompositionLocalProvider(LocalSettingsViewModel provides settings.vm) {
                        LazyColumn(Modifier.fillMaxSize()) { displayPane(s, rows) }
                    }
                }
            }
            assertTheSmallestCutsNothingNew()
        } finally {
            settings.close()
        }
    }

    private companion object {
        val SCREEN_H = 540.dp

        /** A film with everything its page shows: a tagline, an overview, a cast, formats. */
        val FILM = MediaItem(
            id = "top-gun",
            kind = MediaKind.MOVIE,
            title = "Top Gun : Maverick",
            tagline = "Ressentez le besoin… le besoin de vitesse.",
            overview = "Après plus de trente ans de service en tant que l’un des meilleurs aviateurs de la Marine, Pete « Maverick » " +
                "Mitchell continue de repousser ses limites en tant que pilote d’essai courageux, et esquive l’avancement " +
                "dans le grade qui le mettrait à terre. Chargé de former un détachement de diplômés de Top Gun pour une " +
                "mission spéciale, Maverick y retrouve le fils de son défunt ami.",
            year = 2022,
            runtimeMs = 7_860_000,
            officialRating = "12",
            communityRating = 8.2,
            genres = listOf("Action", "Drame", "Aventure"),
            cast = listOf(
                Person("Tom Cruise", "Capt. Pete « Maverick » Mitchell", null),
                Person("Miles Teller", "Lt. Bradley « Rooster » Bradshaw", null),
                Person("Jennifer Connelly", "Penny Benjamin", null),
                Person("Jon Hamm", "Vice-amiral Beau « Cyclone » Simpson", null),
            ),
            directors = listOf("Joseph Kosinski"),
            media = MediaInfo(
                container = "MKV",
                sizeBytes = 62_400_000_000,
                durationMs = 7_800_000,
                video = VideoInfo(Codecs.HEVC, 3840, 1608, 10, hdr = listOf(HdrFormat.DOLBY_VISION, HdrFormat.HDR10), frameRate = 23.976f),
                audio = listOf(AudioTrack(Codecs.TRUEHD, 8, "eng", atmos = true, default = true), AudioTrack(Codecs.EAC3, 6, "fre")),
                subtitles = listOf(SubtitleTrack("fre", format = Codecs.PGS, forced = true), SubtitleTrack("eng", format = Codecs.PGS)),
            ),
            fileName = "Top.Gun.Maverick.2022.2160p.UHD.BluRay.REMUX.mkv",
        )
    }
}
