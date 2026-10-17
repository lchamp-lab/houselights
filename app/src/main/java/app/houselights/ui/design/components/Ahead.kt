package app.houselights.ui.design.components

import android.content.Context
import androidx.compose.ui.unit.Dp
import app.houselights.core.MediaItem
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.canvasDensityOf
import coil.request.ImageRequest

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
