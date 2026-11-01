package app.houselights.ui.detail

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp
import app.houselights.core.ArtworkKind
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.core.MediaProvider
import app.houselights.core.Saga
import app.houselights.core.SagaPart
import app.houselights.core.UserState
import app.houselights.ui.design.HlIconButtonDefaults
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HouselightsTheme
import app.houselights.ui.design.components.CutPosterCache
import app.houselights.ui.design.components.LocalCutPosters
import app.houselights.ui.design.components.VoletPlan
import app.houselights.ui.testing.FakeCatalog
import app.houselights.ui.testing.FakeEvents
import app.houselights.ui.testing.FakeProvider
import app.houselights.ui.util.PlaybackOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.IOException
import java.time.LocalDate
import java.util.Locale

// Dune as the boards show it: the first film watched, the second half seen, the third announced, the library lacking it.

/** A picture of [name], asked at the width its place requests. */
internal fun picture(name: String) = ImageRef(name) { "test://$name?w=$it" }

internal const val MIN = 60_000L

internal val duneSaga = MediaItem(
    "dune",
    MediaKind.COLLECTION,
    "Dune",
    overview = "La saga de Paul Atréides, de Caladan au désert d’Arrakis.",
    backdrop = picture("dune-saga"),
    totalCount = 3,
)

internal val dune = MediaItem(
    "dune1",
    MediaKind.MOVIE,
    "Dune",
    year = 2021,
    runtimeMs = 155 * MIN,
    poster = picture("dune1-poster"),
    backdrop = picture("dune1-backdrop"),
    user = UserState(played = true),
)

/** Seen to 1 h 32 of its 2 h 46: 55 %, 1 h 14 left. */
internal val duneTwo = MediaItem(
    "dune2",
    MediaKind.MOVIE,
    "Dune : Deuxième partie",
    year = 2024,
    runtimeMs = 166 * MIN,
    poster = picture("dune2-poster"),
    backdrop = picture("dune2-backdrop"),
    user = UserState(positionMs = 92 * MIN),
)

internal val duneThreeOut: LocalDate = LocalDate.of(2026, 12, 15)

internal val duneThree = SagaPart(
    null,
    "Dune : Troisième partie",
    2026,
    released = false,
    overview = "Paul Atréides, empereur, face à la guerre sainte qu’il a vue venir.",
    backdrop = picture("dune3-backdrop"),
    poster = picture("dune3-poster"),
    releaseDate = duneThreeOut,
)

/** The saga whole, in release order, as a source that knows it says it. */
internal fun duneWhole(first: MediaItem = dune, second: MediaItem = duneTwo, third: SagaPart = duneThree) = Saga(
    duneSaga,
    listOf(
        SagaPart(first, first.title, first.year, overview = "Paul part pour Arrakis.", backdrop = first.backdrop),
        SagaPart(second, second.title, second.year, overview = "Paul s’allie aux Fremen.", backdrop = second.backdrop),
        third,
    ),
)

/** A source of [films], all of [saga]: Lire's film follows the shared rule, the films' saga is [saga]; [away], it fails. */
internal class SagaSource(source: FakeProvider, private val saga: MediaItem, private val films: List<MediaItem>) : MediaProvider by source {
    var away = false

    override suspend fun collectionItems(collection: MediaItem): List<MediaItem> {
        if (away) throw IOException("Server away")
        return films.takeIf { collection.id == saga.id }.orEmpty()
    }

    override suspend fun collectionOf(item: MediaItem): MediaItem? = saga.takeIf { films.any { film -> film.id == item.id } }

    override suspend fun nextUp(series: MediaItem): MediaItem? = super.nextUp(series)
}

/** A source that knows Dune whole, held films [first] and [second], then [third]. */
internal fun duneSource(first: MediaItem = dune, second: MediaItem = duneTwo, third: SagaPart = duneThree): SagaSource {
    val provider = FakeProvider().apply {
        items[duneSaga.id] = duneSaga
        items[first.id] = first
        items[second.id] = second
        catalog = FakeCatalog().apply { sagas = mapOf(duneSaga.id to duneWhole(first, second, third)) }
        capabilities = capabilities.copy(sagaParts = true)
    }
    return SagaSource(provider, duneSaga, listOf(first, second))
}

/**
 * A page of [source] on the TV canvas, kept by a saveable state holder as the app's navigation keeps it: [shown] false
 * leaves it for another screen, true comes back to it; its text at [step], in [language] once set.
 */
internal class SagaPage(private val compose: ComposeContentTestRule, private val source: MediaProvider, private val work: CoroutineScope) {
    var shown by mutableStateOf(true)
    var step by mutableFloatStateOf(1f)

    /** The language the page speaks, as the app wraps its screens in the one chosen (a BCP 47 tag); null, the device's. */
    var language by mutableStateOf<String?>(null)
    val opened = mutableListOf<String>()
    val played = mutableListOf<String>()

    /** The pictures whose choice the page opened, by the title's id, as the app's navigation would. */
    val edited = mutableListOf<Pair<String, ArtworkKind>>()

    /** The room a row of tiles keeps under it for a label, at the text size shown. */
    var labelRoom = 0.dp
        private set

    /** The height of a hint's line, at the text size shown. */
    var hintRoom = 0.dp
        private set

    /** The page's view model, as a library change reaches it. */
    lateinit var vm: DetailViewModel
        private set

    fun open(id: String) {
        vm = DetailViewModel(id, { source }, FakeEvents(), work)
        compose.setContent {
            Speaking(language) {
                CompositionLocalProvider(LocalCutPosters provides remember { CutPosterCache() }) {
                    HouselightsTheme(step) {
                        labelRoom = HlIconButtonDefaults.labelRoom
                        hintRoom = HlIconButtonDefaults.hintRoom
                        val pages = rememberSaveableStateHolder()
                        if (shown) {
                            pages.SaveableStateProvider(id) {
                                DetailPage(
                                    vm,
                                    PlaybackOutcome(MutableSharedFlow()),
                                    screen = "page",
                                    onPlay = { title, resume, _ -> played += "${title.id} $resume" },
                                    onOpenItem = { opened += it },
                                    onEditArtwork = { id, kind -> edited += id to kind },
                                    onIdentify = {},
                                    onOpenPerson = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

/** [content] speaking [tag] as the app's screens do once it is chosen (`InterfaceLanguage.wrap`): its words, plurals and dates. */
@Composable
internal fun Speaking(tag: String?, content: @Composable () -> Unit) {
    if (tag == null) return content()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val local = remember(tag, context, configuration) {
        context.createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
    }
    CompositionLocalProvider(
        LocalContext provides local,
        LocalConfiguration provides local.resources.configuration,
        LocalResources provides local.resources,
        content = content,
    )
}

/** Dune's polyptych on the canvas, the saga's panel and three films'. */
internal val dunePlan = VoletPlan.of(4, HlLayout.CANVAS_WIDTH.dp - HlLayout.voletSide * 2, 473.dp)
