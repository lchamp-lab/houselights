package app.houselights.ui.catalog

import app.houselights.ui.design.TwoPaneDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.houselights.R
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.edge
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.components.AbsentCard
import app.houselights.ui.design.components.ArtworkImage
import app.houselights.ui.design.components.ArtworkSurface
import app.houselights.ui.design.components.Emphasis
import app.houselights.ui.design.components.HlActionRow
import app.houselights.ui.design.components.HlButton
import app.houselights.ui.design.components.HlIconButton
import app.houselights.ui.design.components.HlListItem
import app.houselights.ui.design.components.HlSegmented
import app.houselights.ui.design.components.HlSettingRow
import app.houselights.ui.design.components.HlTextField
import app.houselights.ui.design.components.MediaRow

/** Every shape at a size it takes, then focus in its two gestures and its ring, live with the remote. */
internal fun LazyListScope.catalogFocus() {
    item { Group("Shapes: radii by hierarchy, no pill, no disc", still = true) { Shapes() } }
    item { Group("Focus, frame: what carries its own light is framed 3 dp off; only a picture glows; nothing grows") { Frames() } }
    item { Group("Focus, fill: a plate turns white, ink black; the ring where nothing acts") { Fills() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shapes() {
    val c = Hl.colors
    val shapes = listOf(
        "thumb" to HlShapes.thumb, "badge" to HlShapes.badge, "mark" to HlShapes.mark, "control" to HlShapes.control,
        "field" to HlShapes.field, "row" to HlShapes.row, "artwork" to HlShapes.artwork, "wide" to HlShapes.wide,
        "panel" to HlShapes.panel, "sheet" to HlShapes.sheet,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalArrangement = Arrangement.spacedBy(HlSpace.m)) {
        shapes.forEach { (name, shape) ->
            Specimen("$name ${radii(shape)}", gap = HlSpace.xs) {
                Box(Modifier.size(CatalogSwatch).background(groundStepFill(), shape).border(HlSize.hairline, c.edge, shape))
            }
        }
    }
}

/** A shape's radii in dp, the free corners first: "10", or "24 / 0" for a sheet against the edge. */
@Composable
private fun radii(shape: RoundedCornerShape): String {
    val density = LocalDensity.current
    val unit = Size(density.density * CatalogSwatch.value, density.density * CatalogSwatch.value)
    return listOf(shape.topStart, shape.topEnd).map { (it.toPx(unit, density) / density.density).toInt() }
        .distinct().joinToString(" / ")
}

@Composable
private fun Frames() {
    val art = shippedImage(R.drawable.banner)
    val url = art.url(0)
    MediaRow(header = null) {
        item {
            Specimen("a poster", width = HlSize.poster) {
                ArtworkSurface({}, Modifier.width(HlSize.poster).aspectRatio(HlSize.POSTER_RATIO), light = url) {
                    ArtworkImage(url, null, Modifier.fillMaxSize())
                }
            }
        }
        item {
            Specimen("a wide image", width = HlSize.landscape) {
                ArtworkSurface({}, Modifier.width(HlSize.landscape).aspectRatio(16f / 9f), shape = HlShapes.wide, light = url) {
                    ArtworkImage(url, null, Modifier.fillMaxSize())
                }
            }
        }
        item {
            Specimen("a portrait", width = HlSize.personColumn) {
                ArtworkSurface({}, Modifier.width(HlSize.portrait).aspectRatio(HlSize.PORTRAIT_RATIO), light = url) {
                    ArtworkImage(url, null, Modifier.fillMaxSize())
                }
            }
        }
        item {
            Specimen("nothing to open: framed, no light of its own", width = HlSize.poster) { AbsentCard("Le Cercle rouge", 1970) }
        }
    }
    Specimen("a panel: framed, no light of its own", Modifier.widthIn(max = HlLayout.readable)) {
        HlListItem(stringResource(R.string.source_local), "Vos vidéos sur un NAS ou un partage réseau.", {}, icon = HlIcons.Folder)
    }
    Specimen("the main action: the ember light, framed", gap = HlSpace.xs) {
        HlButton(stringResource(R.string.play), {}, icon = HlIcons.Play, detail = "1 h 53", emphasis = Emphasis.Primary)
    }
}

@Composable
private fun ColumnScope.Fills() {
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("secondary", gap = HlSpace.xs) { HlButton(stringResource(R.string.filter), {}, icon = HlIcons.Filter) }
        // A tile's label takes no room of its own: the row keeps it.
        Specimen("tile", gap = HlSpace.xs) { HlActionRow { HlIconButton(HlIcons.Info, stringResource(R.string.tech_details), {}) } }
        Specimen("cannot act: ringed", gap = HlSpace.xs) { HlButton(stringResource(R.string.add), {}, enabled = false) }
    }
    var place by remember { mutableIntStateOf(1) }
    Specimen("tabs: the place lit in ember; focus is white, a dot marks the place", gap = HlSpace.xs) {
        HlSegmented(
            listOf(R.string.library_movies, R.string.library_shows, R.string.library_collections).map { stringResource(it) },
            selected = place,
            onSelect = { place = it },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.m), verticalAlignment = Alignment.Top) {
        Specimen("a row", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            HlSettingRow(stringResource(R.string.scan_now), description = stringResource(R.string.scan_now_desc), onClick = {})
        }
        var text by remember { mutableStateOf("") }
        Specimen("a field: the ring, ember once typing", Modifier.width(TwoPaneDefaults.sideWide), gap = HlSpace.xs) {
            HlTextField(
                stringResource(R.string.field_host),
                text,
                { text = it },
                placeholder = stringResource(R.string.placeholder_host),
                showLabel = false,
            )
        }
    }
}
