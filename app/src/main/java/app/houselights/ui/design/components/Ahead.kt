package app.houselights.ui.design.components

import app.houselights.ui.design.HeroDefaults
import android.content.Context
import androidx.compose.ui.unit.Dp
import app.houselights.core.MediaItem
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HomeGridDefaults
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

/** The requests a screen's components will make, so the pictures loaded ahead of it hit memory. */
class PicturesAhead(private val context: Context, private val screenWidthPx: Int) {
    private val density = canvasDensityOf(screenWidthPx)

    private fun Dp.px(): Int = with(density) { roundToPx() }

    /** A hero showing [item]: the [AmbientBackdrop] picture and sample in [style], and the [HeroInfo] logo. */
    fun hero(item: MediaItem, style: BackdropStyle = BackdropStyle.Hero): List<ImageRequest> =
        item.backdrop?.let { backdropRequests(context, it, style, screenWidthPx) }.orEmpty() +
            listOfNotNull(item.logo?.let { titleArtRequest(context, it, HeroDefaults.titleWidth, HeroDefaults.titleHeight, density) })

    /** Home's case showing [item] ([ResumeCase]): its picture, cropped upright, and its logo. */
    fun case(item: MediaItem): List<ImageRequest> = listOfNotNull(
        caseRequest(context, item) { it.px() },
        item.logo?.let { titleArtRequest(context, it, HomeGridDefaults.caseLogoWidth, HomeGridDefaults.caseLogoHeight, density) },
    )

    /** A door's still showing [item] ([DoorStill]): its backdrop at the door's width, and its logo. */
    fun still(item: MediaItem): List<ImageRequest> = listOfNotNull(
        stillDoorRequest(context, item) { it.px() },
        item.logo?.let { titleArtRequest(context, it, HomeGridDefaults.stillLogoWidth, HomeGridDefaults.stillLogoHeight, density) },
    )

    /** The posters of a door's band ([DoorPosters]). */
    fun strip(items: List<MediaItem>): List<ImageRequest> = posters(items, HomeGridDefaults.stripPoster)

    /** The posters a door's count is cut in ([DoorFigures]), read for their pixels, each once. */
    fun cut(items: List<MediaItem>): List<ImageRequest> =
        items.distinctBy { it.id }.mapNotNull { cutPosterRequest(context, it) { d -> d.px() } }

    /** Cuts a door's count in [items]' posters ahead into [cuts] ([CutPosterCache.make]), so the door draws them on its first frame. */
    suspend fun cutAhead(items: List<MediaItem>, cuts: CutPosterCache) {
        cuts.make(context, items) { it.px() }
    }

    /** Two sagas' posters ([DoorSagas]). */
    fun sagas(items: List<MediaItem>): List<ImageRequest> = posters(items.take(2), HomeGridDefaults.sagaPoster)

    /** The search door's small posters ([DoorWall]). */
    fun thumbs(items: List<MediaItem>): List<ImageRequest> = posters(items, HomeGridDefaults.wallThumb)

    private fun posters(items: List<MediaItem>, width: Dp): List<ImageRequest> = items.mapNotNull { posterRequest(context, it, width.px()) }

    /** The posters of a wall's [items], at the size a [PosterCard] cell requests ([requestedPoster]). */
    fun wall(items: List<MediaItem>): List<ImageRequest> = items.mapNotNull { posterRequest(context, it, requestedPoster(null).px()) }

    /** The logo a detail page of [item] shows, at its [TitleArt] size; its backdrop is decoded as the page opens. */
    fun detail(item: MediaItem): List<ImageRequest> =
        listOfNotNull(item.logo?.let { titleArtRequest(context, it, HlLayout.titleArtWidth, HlLayout.titleArtHeight, density) })

    /**
     * The posters, or stills when [landscape], that a [MediaRow] of [items] shows before it scrolls, at the size its
     * cards request: a still at its width, a poster at [requestedPoster].
     */
    fun row(items: List<MediaItem>, landscape: Boolean, spacing: Dp): List<ImageRequest> {
        val card = if (landscape) HlSize.landscape else HlSize.poster
        val width = (if (landscape) card else requestedPoster(card)).px()
        return items.take(cardsInView(card, spacing)).mapNotNull {
            if (landscape) stillRequest(context, it, width) else posterRequest(context, it, width)
        }
    }
}

/** Preloads the focused [item]'s detail page pictures so OK never waits; not under the launch screen. */
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

/** Preloads in flight by request data; [AwaitAhead] makes a concurrent request wait, then hit memory. */
object LoadingAhead {
    private val loading = HashMap<Any, MutableList<Job>>()

    /** Loads [requests] into the memory cache with [loader], concurrently, each registered while in flight. */
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

    /** Suspends until preloads of [data] complete; returns at once when none is in flight. */
    suspend fun await(data: Any) {
        synchronized(loading) { loading[data]?.toList() }?.joinAll()
    }
}

/** Coil interceptor: a request waits for an in-flight preload of the same data ([LoadingAhead]). */
object AwaitAhead : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (chain.request.parameters.value<Boolean>(AHEAD) != true) LoadingAhead.await(chain.request.data)
        return chain.proceed(chain.request)
    }
}

/** Marks a preload request; excluded from the memory cache key so the entry matches the regular request. */
private const val AHEAD = "houselights.ahead"
