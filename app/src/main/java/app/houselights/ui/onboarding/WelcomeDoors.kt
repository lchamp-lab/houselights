package app.houselights.ui.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Text
import app.houselights.core.ImageRef
import app.houselights.core.MediaItem
import app.houselights.core.MediaKind
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.components.CutPosters
import app.houselights.ui.design.components.HomeDoor
import app.houselights.ui.design.components.ScaledTint
import app.houselights.ui.design.components.rememberCutPosters
import app.houselights.ui.design.components.tintPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Welcome's two choices as two doors of home (DESIGN.md, Onboarding): each source's glyph cut in a poster of the wall.

/** A source to choose on Welcome: its [name], [icon], [glyph] cut in its poster, what it [says] and its [tech] line. */
internal class SourceChoice(
    val name: String,
    val icon: ImageVector,
    val glyph: SourceGlyph,
    val says: String,
    val tech: String,
    val onClick: () -> Unit,
    val modifier: Modifier = Modifier,
)

/**
 * The [choices] side by side as doors, as tall as the column leaves them up to [HlLayout.welcomeDoor], their
 * glyphs cut in [posters] of the wall (one each, in order); under them, what the door with focus says and its
 * technical line, in the room the longer of them takes, so nothing moves as focus goes from one to the other.
 */
@Composable
internal fun ColumnScope.WelcomeDoors(choices: List<SourceChoice>, posters: List<String>) {
    var focused by remember { mutableIntStateOf(0) }
    Row(
        Modifier.weight(1f, fill = false).heightIn(max = HlLayout.welcomeDoor),
        horizontalArrangement = Arrangement.spacedBy(HlSpace.s),
    ) {
        choices.forEachIndexed { i, choice ->
            val cut = rememberCutPosters(listOfNotNull(posters.getOrNull(i)?.let(::posterItem)))
            HomeDoor(
                choice.name,
                choice.icon,
                choice.onClick,
                choice.modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { if (it.hasFocus) focused = i }
                    .semantics { contentDescription = choice.name + ", " + choice.says },
                glowTones = { cut.tones },
            ) { lit -> GlyphCut(choice.glyph, cut, lit) }
        }
    }
    Spacer(Modifier.height(HlSpace.m))
    // Both said in the same box, the one without focus unseen: its room is the longer's.
    Box(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
        choices.forEachIndexed { i, choice ->
            Column(Modifier.alpha(if (i == focused) 1f else 0f), verticalArrangement = Arrangement.spacedBy(HlSpace.xs)) {
                Text(choice.says, style = Hl.type.body, color = Hl.colors.contentSecondary)
                Text(choice.tech, style = Hl.type.jaquette, color = Hl.colors.contentSecondary)
            }
        }
    }
}

/** A poster of the wall as a title to cut: its address its only part. */
private fun posterItem(url: String) = MediaItem(url, MediaKind.MOVIE, "", poster = ImageRef(url) { url })

/** A source's glyph, on Lucide's 24 grid, filled. */
internal enum class SourceGlyph(private val make: () -> Path) {
    /** Lucide's `folder`. */
    FOLDER({ parse(FOLDER_PATH) }),

    /** One server unit of two drawers: a block parted by a gap, its two lights punched through. */
    SERVER({
        val block = parse(SERVER_PATH)
        val holes = Path().apply {
            addRect(Rect(SERVER_LEFT, GAP_TOP, SERVER_RIGHT, GAP_TOP + GAP))
            addOval(Rect(Offset(LIGHT_X, LIGHT_UPPER), LIGHT_RADIUS))
            addOval(Rect(Offset(LIGHT_X, LIGHT_LOWER), LIGHT_RADIUS))
        }
        Path.combine(PathOperation.Difference, block, holes)
    }),
    ;

    val path: Path by lazy { make() }
}

/**
 * [glyph] centred in its door's picture, cut in [posters]' poster (ink without one), edged by a fine line of ink, over
 * the poster's tint, dark at rest and lit whole once [lit], as a library door's count ([app.houselights.ui.design.components.DoorFigures]).
 */
@Composable
private fun GlyphCut(glyph: SourceGlyph, posters: CutPosters, lit: Boolean) {
    val c = Hl.colors
    val tints = remember { ScaledTint() }
    val tint = animateFloatAsState(
        if (lit) 1f else HlAlpha.DOOR_TINT,
        HlMotion.inOut(lit),
        label = "glyphTint",
    )
    val poster = posters.posters.firstOrNull()?.bitmap
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val path = glyph.path
            val bounds = path.getBounds()
            val scale = min(size.width * WelcomeDoorDefaults.GLYPH_ACROSS, size.height * WelcomeDoorDefaults.GLYPH_DOWN) /
                max(bounds.width, bounds.height)
            val drawn = Size(bounds.width * scale, bounds.height * scale)
            val left = (size.width - drawn.width) / 2 - bounds.left * scale
            val top = (size.height - drawn.height) / 2 - bounds.top * scale
            val edge = Stroke(HlSize.hairline.toPx())
            val line = c.content.copy(alpha = HlAlpha.FIGURE_EDGE)
            val tintPaint = posters.tint?.let { tintPaint(tints.at(it, size)) }
            val fill = poster?.let { posterPaint(it, drawn, Offset(left + bounds.left * scale, top + bounds.top * scale)) }
            // The glyph in screen units, as the poster's shader is.
            val onScreen = android.graphics.Path(path.asAndroidPath()).apply {
                transform(
                    Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(left, top)
                    },
                )
            }
            val glyphOnScreen = onScreen.asComposePath()
            onDrawBehind {
                tintPaint?.let { paint ->
                    // Its light read while drawing: focus redraws this door only.
                    paint.alpha = (tint.value * OPAQUE).roundToInt()
                    drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
                }
                if (fill != null) drawIntoCanvas { it.nativeCanvas.drawPath(onScreen, fill) } else drawPath(glyphOnScreen, c.content)
                drawPath(glyphOnScreen, line, style = edge)
            }
        },
    )
}

/** A poster covering a glyph [drawn] at [at], centred across it, from a tenth down, filtered as it is scaled. */
private fun posterPaint(poster: Bitmap, drawn: Size, at: Offset): Paint {
    val cover = max(drawn.width / poster.width, drawn.height / poster.height)
    val x = at.x + (drawn.width - poster.width * cover) * POSTER_ACROSS
    val y = at.y + (drawn.height - poster.height * cover) * POSTER_DOWN
    val shader = BitmapShader(poster, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(
            Matrix().apply {
                setScale(cover, cover)
                postTranslate(x, y)
            },
        )
    }
    return Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { this.shader = shader }
}

private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

internal object WelcomeDoorDefaults {
    /** The glyph's share of its picture, across and down, whichever is smaller. */
    const val GLYPH_ACROSS = 0.77f
    const val GLYPH_DOWN = 0.68f
}

private const val FOLDER_PATH =
    "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"
private const val SERVER_PATH = "M4 2h16a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Z"

/** The server on the 24 grid: its sides, the gap between its drawers, and their lights. */
private const val SERVER_LEFT = 2f
private const val SERVER_RIGHT = 22f
private const val GAP_TOP = 11.25f
private const val GAP = 1.5f
private const val LIGHT_X = 6f
private const val LIGHT_UPPER = 6f
private const val LIGHT_LOWER = 18f
private const val LIGHT_RADIUS = 1f

private const val POSTER_ACROSS = 0.5f
private const val POSTER_DOWN = 0.1f
private const val OPAQUE = 255
