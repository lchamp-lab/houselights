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

/** The requests a screen's components will make, so pictures preloaded under the launch screen hit memory. */
class PicturesAhead(private val context: Context, private val screenWidthPx: Int) {
    private val density = canvasDensityOf(screenWidthPx)

    private fun Dp.px(): Int = with(density) { roundToPx() }

    /** A hero showing [item]: the [AmbientBackdrop] picture and sample in [style], and the [HeroInfo] logo. */
    fun hero(item: MediaItem, style: BackdropStyle = BackdropStyle.Hero): List<ImageRequest> =
        item.backdrop?.let { backdropRequests(context, it, style, screenWidthPx) }.orEmpty() +
            listOfNotNull(item.logo?.let { titleArtRequest(context, it, HlLayout.heroTitleWidth.px(), HlLayout.heroTitleHeight.px()) })

    /** The posters of a wall's [items], at the size a [PosterCard] cell requests ([requestedPoster]). */
    fun wall(items: List<MediaItem>): List<ImageRequest> = items.mapNotNull { posterRequest(context, it, requestedPoster(null).px()) }

    /** The logo a detail page of [item] shows, at its [TitleArt] size; its backdrop is already on screen. */
    fun detail(item: MediaItem): List<ImageRequest> =
        listOfNotNull(item.logo?.let { titleArtRequest(context, it, HlLayout.titleArtWidth.px(), HlLayout.titleArtHeight.px()) })

    /** The posters, or stills when [landscape], that a [MediaRow] of [items] shows before it scrolls. */
    fun row(items: List<MediaItem>, landscape: Boolean, spacing: Dp): List<ImageRequest> {
        val card = if (landscape) HlSize.landscape else HlSize.poster
        val width = if (landscape) card.px() else requestedPoster(card).px()
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
