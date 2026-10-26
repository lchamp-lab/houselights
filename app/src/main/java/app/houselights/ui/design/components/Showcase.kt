package app.houselights.ui.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.ui.design.Ground
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlScrim
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.ShowcaseDefaults
import app.houselights.ui.design.VeilStop
import app.houselights.ui.design.colorStops
import app.houselights.ui.design.plateEdge
import app.houselights.ui.design.tintedGlass

/**
 * A library folder as a showcase (DESIGN.md, Secondary screens), [HlSize.showcase] wide: its latest [posters], four
 * across and full height, melting into glass tinted by their own light; over their foot its [name] in `subject`
 * beside its printed [mark] (a protocol), then its [facts], the first in white, and the [state] it was left in by
 * its light ([stateLight]: the white dot, "Analysé à l'instant"; the ring, "12 introuvables"); [status] takes their line
 * (a scan running, a failure). Focus frames it as a picture, its words keeping their ink. One layer, its clip; the melt
 * and the glass are read while drawing.
 */
@Composable
fun Showcase(
    name: String,
    mark: String?,
    posters: List<ImageRef>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    facts: List<String?> = emptyList(),
    state: String? = null,
    status: (@Composable () -> Unit)? = null,
    onFocused: () -> Unit = {},
    stateLight: StatusState = StatusState.Done,
) {
    val c = Hl.colors
    // The glass takes the light of its first poster, sampled once, at once: plain glass until then.
    val tone = rememberGlowTone(posters.firstOrNull()?.url(SAMPLE_PX), active = true)
    val glass = remember(tone, c) { ColorProducer { c.tintedGlass(tone.value ?: Color.Transparent) } }
    // Posters in every slot cover the glass: none is drawn under them.
    val full = posters.size >= SHOWCASE_SLOTS
    val band = remember { mutableIntStateOf(0) }
    val covered = remember(band) { { band.intValue.toFloat() } }
    HlFocusSurface(
        onClick = onClick,
        look = FocusStyle.frame(rest = glass, restInk = c.content, edge = BorderStroke(HlSize.hairline, c.plateEdge)),
        shape = HlShapes.panel,
        modifier = modifier.width(HlSize.showcase).onFocusChanged { if (it.isFocused) onFocused() },
        covered = covered.takeIf { full },
    ) {
        val plate = LocalPlate.current
        CompositionLocalProvider(LocalGround provides Ground.Glass) {
            Box {
                val melt = remember(plate) { if (plate == null) Modifier else Modifier.meltsInto(plate, HlScrim.SHOWCASE_FOOT) }
                PosterStrip(posters, SHOWCASE_SLOTS, Modifier.fillMaxWidth().then(melt).onSizeChanged { band.intValue = it.height })
                Column(
                    Modifier.padding(
                        start = ShowcaseDefaults.inset,
                        end = ShowcaseDefaults.inset,
                        top = ShowcaseDefaults.nameTop,
                        bottom = ShowcaseDefaults.inset,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
                        Text(
                            name,
                            style = Hl.type.subject,
                            color = c.content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (mark != null) Badge(mark)
                    }
                    Spacer(Modifier.height(HlSpace.s))
                    if (status != null) {
                        status()
                    } else {
                        ShowcaseFacts(facts, state, stateLight)
                    }
                }
            }
        }
    }
}

/** Its facts, the first in white, dropping whole from the end, then its state by its [light]. */
@Composable
private fun ShowcaseFacts(facts: List<String?>, state: String?, light: StatusState) {
    val c = Hl.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FactRow(facts, Modifier.weight(1f), lead = c.content)
        if (state != null) {
            Spacer(Modifier.width(HlSpace.fact))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLight(light)
                Spacer(Modifier.width(HlSpace.xxs))
                Text(state, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Posters across a showcase. */
const val SHOWCASE_SLOTS = 4

/**
 * Melts what it draws into its [plate] ([LocalPlate]) at its foot, along a curve of the system ([foot]); the plate
 * is read while drawing.
 */
internal fun Modifier.meltsInto(plate: ColorProducer, foot: List<VeilStop>): Modifier =
    drawWithCache {
        // One mask, cached, drawn where the melt is: above it, it is clear.
        val mask = Brush.verticalGradient(*foot.colorStops(Color.White), startY = 0f, endY = size.height)
        val from = foot.first().at * size.height
        val band = Size(size.width, size.height - from)
        onDrawWithContent {
            drawContent()
            drawRect(mask, Offset(0f, from), band, colorFilter = PlateTints.of(plate()))
        }
    }

/** The mask's tint per plate colour, the last few kept: a showcase's glass takes one colour, once. */
private object PlateTints {
    private val made = object : LinkedHashMap<Int, ColorFilter>(TINTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ColorFilter>) = size > TINTS
    }

    @Synchronized
    fun of(color: Color): ColorFilter {
        val argb = color.toArgb()
        return made.getOrPut(argb) { ColorFilter.tint(Color(argb), BlendMode.SrcIn) }
    }
}

/** Tints kept: a pane's showcases, and a few more. */
private const val TINTS = 16
