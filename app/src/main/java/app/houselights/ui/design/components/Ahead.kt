package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.ui.unit.Dp
import app.houselights.core.MediaItem
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.canvasDensityOf
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.houselights.ui.design.LocalLaunchScreen
import app.houselights.ui.util.screenWidthPx
import coil.imageLoader

/**
 * The pictures a screen shows first, asked ahead as its components will ask Coil for them once laid
 * out on a screen [screenWidthPx] wide: the same addresses at the same sizes, so that loaded while
 * the launch screen holds, each is found in memory and shows at once, without fading in.
 */
class PicturesAhead(private val context: Context, private val screenWidthPx: Int) {
    private val density = canvasDensityOf(screenWidthPx)

    private fun Dp.px(): Int = with(density) { roundToPx() }

    /**
     * A hero showing [item]: on its [AmbientBackdrop] in [style], the picture and its tiny copy; in
     * [HeroInfo], its logo.
     */
    fun hero(item: MediaItem, style: BackdropStyle = BackdropStyle.Hero): List<ImageRequest> =
        item.backdrop?.let { backdropRequests(context, it, style, screenWidthPx) }.orEmpty() +
            listOfNotNull(item.logo?.let { titleArtRequest(context, it, HlLayout.heroTitleWidth.px(), HlLayout.heroTitleHeight.px()) })

    /**
     * A wall's [items] as its [PosterCard]s fill their cells: their posters, asked as a cell asks
     * them ([CELL_POSTER]).
     */
    fun wall(items: List<MediaItem>): List<ImageRequest> = items.mapNotNull { posterRequest(context, it, CELL_POSTER.px()) }

    /**
     * The first picture a detail page of [item] shows that the screen opening it does not: its logo,
     * as the page sets it ([TitleArt] in [HlLayout.titleArtWidth]). Its backdrop is the one already shown.
     */
    fun detail(item: MediaItem): List<ImageRequest> =
        listOfNotNull(item.logo?.let { titleArtRequest(context, it, HlLayout.titleArtWidth.px(), HlLayout.titleArtHeight.px()) })

    /**
     * The cards of a [MediaRow] of [items], [spacing] apart, that show before it scrolls: posters
     * ([PosterCard]), or stills when [landscape] ([LandscapeCard]), each at its own width.
     */
    fun row(items: List<MediaItem>, landscape: Boolean, spacing: Dp): List<ImageRequest> {
        val card = if (landscape) HlSize.landscape else HlSize.poster
        val width = card.px()
        return items.take(cardsInView(card, spacing)).mapNotNull {
            if (landscape) stillRequest(context, it, width) else posterRequest(context, it, width)
        }
    }
}

/**
 * Once focus settles on [item] (a hero, a wall), what its detail page shows first and the screen
 * does not is asked ahead ([PicturesAhead.detail]): OK never waits for it. Not under the launch
 * screen, whose pictures come first.
 */
@Composable
internal fun DetailAhead(item: MediaItem?) {
    val context = LocalContext.current
    val screen = screenWidthPx()
    val covered = LocalLaunchScreen.current.covers
    val ahead = remember(context, screen) { PicturesAhead(context, screen) }
    LaunchedEffect(item?.id, item?.logo, covered) {
        if (item != null && !covered) LoadingAhead.load(context.imageLoader, ahead.detail(item))
    }
}

/**
 * The pictures being loaded ahead, by address, while they load: a component that asks Coil for one
 * of them meanwhile (home, composed under the launch screen while its pictures load) waits for it
 * ([AwaitAhead]), then finds it in memory, instead of loading it a second time.
 */
object LoadingAhead {
    private val loading = HashMap<Any, MutableList<Job>>()

    /** Loads [requests] into memory with [loader], all at once, each known by its address while it loads. */
    suspend fun load(loader: ImageLoader, requests: List<ImageRequest>) = coroutineScope {
        requests.forEach { request ->
            val ahead = request.newBuilder().setParameter(AHEAD, true, memoryCacheKey = null).build()
            val job = launch(start = CoroutineStart.LAZY) { loader.execute(ahead) }
            synchronized(loading) { loading.getOrPut(request.data) { mutableListOf() } += job }
            job.invokeOnCompletion {
                synchronized(loading) {
                    val jobs = loading[request.data] ?: return@synchronized
                    jobs -= job
                    if (jobs.isEmpty()) loading.remove(request.data)
                }
            }
            job.start()
        }
    }

    /** Waits while the picture at [data] is being loaded ahead; at once when it is not. */
    suspend fun await(data: Any) {
        synchronized(loading) { loading[data]?.toList() }?.joinAll()
    }
}

/**
 * Coil's side of [LoadingAhead]: a request for a picture being loaded ahead waits for it, then goes
 * on, and finds it in memory; one that is not, or that loads ahead itself, goes on at once.
 */
object AwaitAhead : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (chain.request.parameters.value<Boolean>(AHEAD) != true) LoadingAhead.await(chain.request.data)
        return chain.proceed(chain.request)
    }
}

/** Marks a request that loads ahead; left out of the memory cache key, so it is the picture home asks for. */
private const val AHEAD = "houselights.ahead"
