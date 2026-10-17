package app.houselights.ui.home

import app.houselights.core.HomeSection
import app.houselights.ui.design.components.PicturesAhead
import coil.request.ImageRequest

/**
 * What home's first view shows, as it asks Coil for it: the hero's pictures (the first title of the
 * first row, where focus lands), then the cards of the first row on the screen.
 */
internal fun firstPictures(ahead: PicturesAhead, sections: List<HomeSection>): List<ImageRequest> {
    val first = sections.firstOrNull() ?: return emptyList()
    val hero = selectedTitle(sections, null)?.let(ahead::hero).orEmpty()
    return hero + ahead.row(first.items, first.landscape, first.spacing)
}
