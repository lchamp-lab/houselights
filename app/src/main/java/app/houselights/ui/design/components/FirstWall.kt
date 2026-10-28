package app.houselights.ui.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntSize
import app.houselights.core.ImageRef
import app.houselights.ui.design.FirstScanDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.groundFill
import app.houselights.ui.util.titleCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A title home's first scan found, as its wall shows it: its [poster], else a one-sheet of its [title] and [year]. */
@Immutable
data class FirstTitle(val id: Long, val title: String, val year: Int?, val poster: ImageRef?)

/**
 * What home's first scan's wall shows, frame by frame ([FirstWall]). The titles there as the wall comes show at once
 * ([come]); those found after fill its frames first found first, a batch a step ([step]); full, it renews in place, one
 * frame a step once every title on it has its picture: the latest title found comes into the oldest frame, round after
 * round, what the frame shows staying whole until the picture coming is decoded. Nothing ever slides. Kept where the
 * wall should be found again as it was.
 */
@Stable
class FirstWallState {
    internal val frames: List<WallFrame> = List(FirstScanDefaults.FRAMES) { WallFrame() }

    /** The frame the next renewal takes: the oldest. */
    private var oldest = 0

    /** The titles the frames show, in their order, null for a frame still empty. */
    val shown: List<FirstTitle?> get() = frames.map { it.shows }

    /** The title coming into each frame, its picture still loading unseen, null for none. */
    val coming: List<FirstTitle?> get() = frames.map { it.coming }

    /** Whether a title on the wall waits for its picture: one coming into its frame, or one filling it not arrived yet. */
    private val waits: Boolean get() = frames.any { it.waits }

    private fun onWall(title: FirstTitle) = frames.any { it.holds(title) }

    /**
     * Whether a step would change the wall for [titles]: a free frame to fill, or, every title on it arrived, a latest
     * title to renew it with.
     */
    internal fun due(titles: List<FirstTitle>): Boolean = if (frames.any { it.free }) {
        titles.any { !onWall(it) }
    } else {
        !waits && titles.lastOrNull()?.let { !onWall(it) } == true
    }

    /**
     * The wall comes, back from Réglages or with a new home, [titles] read: they take its free frames first found first
     * and show at once, as does what waits there for its picture; it renews none.
     */
    internal fun come(titles: List<FirstTitle>) {
        frames.forEach { it.comeBack() }
        fill(titles, atOnce = true)
    }

    /**
     * One step for [titles], first found first and the latest last: the free frames take the next titles not on the
     * wall, every one arriving, and renew none; full, once every title on it has its picture, the latest title, when not
     * on the wall, comes into the oldest frame, those found between two steps never passing on it.
     */
    internal fun step(titles: List<FirstTitle>) {
        if (frames.any { it.free }) {
            fill(titles, atOnce = false)
        } else if (!waits) {
            titles.lastOrNull()?.takeUnless(::onWall)?.let { latest ->
                frames[oldest].renew(latest)
                oldest = (oldest + 1) % frames.size
            }
        }
    }

    private fun fill(titles: List<FirstTitle>, atOnce: Boolean) {
        frames.filter { it.free }.zip(titles.filterNot(::onWall)).forEach { (frame, title) -> frame.fill(title, atOnce) }
    }
}

/** A wall's state ([FirstWallState]), kept while the wall is composed. */
@Composable
fun rememberFirstWallState(): FirstWallState = remember { FirstWallState() }

/**
 * One frame of the first scan's wall: the title it [shows] and the one [coming] into it, its picture loading unseen;
 * how far its opaque ground has lifted off what it shows ([lift]: 0 covered, as an empty frame, 1 whole), read while
 * drawing; once [renewed], its ground lifts plain, without the empty frame's rule.
 */
@Stable
internal class WallFrame {
    var shows: FirstTitle? by mutableStateOf(null)
        private set
    var coming: FirstTitle? by mutableStateOf(null)
        private set
    var renewed: Boolean by mutableStateOf(false)
        private set
    val lift = Animatable(0f)

    /** What it shows waits for its picture, its ground still over it. */
    private var awaited by mutableStateOf(false)

    /** The title waiting for its picture was there as the wall came: shown at once, without an arrival. */
    private var atOnce = false

    val free: Boolean get() = shows == null && coming == null

    /** Whether a title in it waits for its picture: one coming, or the one it shows not arrived yet. */
    val waits: Boolean get() = coming != null || awaited

    fun holds(title: FirstTitle): Boolean = shows?.id == title.id || coming?.id == title.id

    fun fill(title: FirstTitle, atOnce: Boolean) {
        shows = title
        awaited = true
        this.atOnce = atOnce
    }

    fun renew(title: FirstTitle) {
        coming = title
        atOnce = false
    }

    /** The wall comes: what waits here for its picture shows at once. */
    fun comeBack() {
        if (waits) atOnce = true
    }

    /**
     * [title]'s picture is decoded in the frame, or its one-sheet ready: the title coming takes the frame, what it
     * showed cut under the ground, which lifts off it over `TINT`; what it shows arrives so, or at once.
     */
    suspend fun ready(title: FirstTitle) {
        if (coming?.id == title.id) {
            lift.snapTo(0f)
            renewed = true
            shows = title
            coming = null
        } else if (shows?.id != title.id || lift.value == 1f || lift.isRunning) {
            return
        }
        awaited = false
        if (atOnce) lift.snapTo(1f) else lift.animateTo(1f, ARRIVAL)
    }
}

/** A poster arriving on the wall: its frame's ground lifting off it, linear, as a backdrop comes in. */
private val ARRIVAL = tween<Float>(HlMotion.TINT, easing = LinearEasing)

/**
 * Home's first scan's wall (DESIGN.md, Layout): the titles found so far, [titles] (first found first, the latest
 * last; null until read), in two whole lines of [FirstScanDefaults.COLUMNS] posters of [HlSize.wallPoster], each
 * decoded at its size, or a one-sheet where there is none. The titles there once read as the wall comes show at once;
 * a poster found after arrives once its picture is decoded, its frame's opaque ground lifting off it over `TINT`,
 * linear, read while drawing, without a layer nor the loader's fade; full, the wall renews in place ([FirstWallState]),
 * a step at most every [FirstScanDefaults.STEP_MS]. Not focusable; read as how many titles were [found], an arrival
 * never said.
 */
@Composable
fun FirstWall(titles: List<FirstTitle>?, found: Int, modifier: Modifier = Modifier, state: FirstWallState = rememberFirstWallState()) {
    val given by rememberUpdatedState(titles)
    LaunchedEffect(state) {
        state.come(snapshotFlow { given }.filterNotNull().first())
        while (true) {
            delay(FirstScanDefaults.STEP_MS)
            state.step(snapshotFlow { given?.takeIf(state::due) }.filterNotNull().first())
        }
    }
    val readAs = titleCount(found)
    Column(
        modifier.clearAndSetSemantics { contentDescription = readAs },
        verticalArrangement = Arrangement.spacedBy(FirstScanDefaults.gap),
    ) {
        state.frames.chunked(FirstScanDefaults.COLUMNS).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(FirstScanDefaults.gap)) {
                line.forEach { WallFrameView(it) }
            }
        }
    }
}

/**
 * One frame: what it shows, and what comes into it unseen under it until its picture is decoded; its ground over them,
 * with an empty frame's rule and edge until its first arrival lifts them with it, a renewal's ground plain.
 */
@Composable
private fun WallFrameView(frame: WallFrame, modifier: Modifier = Modifier) {
    val c = Hl.colors
    val shape = HlShapes.artwork
    val ground = groundFill(HlAlpha.FILL)
    val rule = c.contentDecorative
    val edge = c.content.copy(alpha = HlAlpha.EDGE_ARTWORK)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(HlSize.wallPoster, HlSize.wallPoster / HlSize.POSTER_RATIO)
            .clip(shape)
            .drawWithCache {
                // The picture's edge, under the ground, drawn over it too while the frame is empty, inside as a border is.
                val stroke = Stroke(HlSize.hairline.toPx())
                val inset = stroke.width / 2
                val outline = shape.createOutline(Size(size.width - stroke.width, size.height - stroke.width), layoutDirection, this)
                onDrawWithContent {
                    drawContent()
                    val over = 1f - frame.lift.value
                    if (over > 0f) {
                        drawRect(ground, alpha = over)
                        if (!frame.renewed) {
                            emptyFrameRule(rule, alpha = over)
                            translate(inset, inset) { drawOutline(outline, edge, alpha = over, style = stroke) }
                        }
                    }
                }
            }
            .artworkEdge(shape, c),
    ) {
        listOfNotNull(frame.coming, frame.shows).forEach { title ->
            key(title.id) {
                FramePicture(title, seen = frame.shows?.id == title.id, onShow = { scope.launch { frame.ready(title) } })
            }
        }
    }
}

/** [title]'s poster at the wall's size, else its one-sheet; composed but not drawn until [seen], so it loads unseen. */
@Composable
private fun FramePicture(title: FirstTitle, seen: Boolean, onShow: () -> Unit, modifier: Modifier = Modifier) {
    val px = posterPx(HlSize.wallPoster)
    val unseen = if (seen) Modifier else Modifier.drawWithContent {}
    ArtworkImage(
        title.poster?.url(px),
        title.title,
        modifier.fillMaxSize().then(unseen),
        size = IntSize(px, (px / HlSize.POSTER_RATIO).roundToInt()),
        onShow = onShow,
    ) { OneSheet(title) }
}

/** A title without its poster on the wall: the house's one-sheet, on the ground's step it needs on the black room. */
@Composable
private fun OneSheet(title: FirstTitle, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(groundFill(HlAlpha.FILL))) { FallbackArtwork(title.title, title.year) }
}
