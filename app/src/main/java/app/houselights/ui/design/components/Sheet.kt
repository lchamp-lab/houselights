package app.houselights.ui.design.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.ui.design.Ground
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlControl
import app.houselights.ui.design.HlFocus
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSideSheetDefaults
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.LocalAmbient
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.capitalsAtTop
import app.houselights.ui.design.edge
import app.houselights.ui.design.groundFill
import app.houselights.ui.design.groundStep
import app.houselights.ui.design.scrollEdges
import app.houselights.ui.design.tintedGlass
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.toPx

/** Lets a sheet's content close it with its motion, then act once it has gone. */
@Stable
class SheetScope internal constructor(private val close: (then: () -> Unit) -> Unit) {
    /** Animates the sheet out, then notifies its owner and runs [then]; ignored while leaving. */
    fun dismiss(then: () -> Unit = {}) = close(then)
}

/**
 * The enter and exit motion of a sheet or menu, read while drawing: [travel] (0 gone, 1 in place) in the
 * focus pull and the fall back, and [opacity] over a plate's quicker timing. Keys are ignored while
 * [leaving], so a second OK cannot choose twice.
 */
@Stable
internal class SheetMotion(
    private val shown: MutableTransitionState<Boolean>,
    private val travelled: State<Float>,
    private val lit: State<Float>,
    val sheet: SheetScope,
) {
    val travel: Float get() = travelled.value
    val opacity: Float get() = lit.value
    val leaving: Boolean get() = !shown.targetState
}

/** Sheet and menu motion: in over [HlMotion.FOCUS_IN], out over [HlMotion.FOCUS_OUT]; [onDismiss] runs once gone. */
@Composable
internal fun rememberSheetMotion(onDismiss: () -> Unit): SheetMotion {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    var then by remember { mutableStateOf({}) }
    val sheet = remember {
        SheetScope { after ->
            if (shown.targetState) {
                then = after
                shown.targetState = false
            }
        }
    }
    val transition = rememberTransition(shown, label = "sheet")
    val travel = transition.animateFloat({ arriving(targetState, HlMotion.FOCUS_IN, HlMotion.FOCUS_OUT) }, label = "sheetTravel") {
        if (it) 1f else 0f
    }
    val opacity = transition.animateFloat({ arriving(targetState, HlMotion.PLATE_IN, HlMotion.PLATE_OUT) }, label = "sheetOpacity") {
        if (it) 1f else 0f
    }
    if (shown.isIdle && !shown.currentState) {
        LaunchedEffect(Unit) {
            onDismiss()
            then()
        }
    }
    return remember { SheetMotion(shown, travel, opacity, sheet) }
}

/** In over [inMs] in the focus pull when [shown], else out over [outMs] in the fall back. */
private fun arriving(shown: Boolean, inMs: Int, outMs: Int): FiniteAnimationSpec<Float> =
    if (shown) tween(inMs, easing = HlMotion.focusPull) else tween(outMs, easing = HlMotion.fallBack)

/** Keys go nowhere while a sheet or a menu leaves. */
internal fun Modifier.stillWhileLeaving(motion: SheetMotion): Modifier = onPreviewKeyEvent { motion.leaving }

/**
 * The dialog window under every sheet, with our own scrim instead of the platform's grey dim and window
 * animation. [content] reads the motion's progress while drawing.
 */
@Composable
internal fun SheetWindow(
    onDismiss: () -> Unit,
    scrim: Float,
    initialFocus: FocusRequester?,
    content: @Composable BoxScope.(motion: SheetMotion) -> Unit,
) {
    val motion = rememberSheetMotion(onDismiss)
    Dialog(onDismissRequest = { motion.sheet.dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        LaunchedEffect(Unit) { initialFocus?.tryRequestFocus() }
        val room = Hl.colors.background
        Box(
            Modifier
                .fillMaxSize()
                .stillWhileLeaving(motion)
                .drawBehind { drawRect(room.copy(alpha = scrim * motion.opacity)) },
        ) {
            content(motion)
        }
    }
}

/**
 * A full-height panel at the right edge for acting on or reading about what the screen shows (DESIGN.md,
 * Sheets), its glass in the film's light: its task's [title], what it is about in [facts] after its printed
 * [badges], ended by a [stateText] with its [state]'s dot, beside its [artwork], as tall as they are, and the
 * [places] it shows (an NFO's views) at the end of the title's line; then its plates, [HlSpace.m] apart, in the
 * film's light ([HlPlate]). [wide], a reading sheet holding the reading measure. No Close button nor hint: Back or
 * [SheetScope.dismiss] closes it; [scroll] lets a text-only panel scroll, and without [scrolls] the content takes
 * the sheet's height and scrolls itself.
 */
@Composable
fun HlSideSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    facts: List<String?> = emptyList(),
    badges: List<String> = emptyList(),
    stateText: String? = null,
    state: StatusState = StatusState.Done,
    description: String? = null,
    artwork: (@Composable () -> Unit)? = null,
    places: (@Composable () -> Unit)? = null,
    initialFocus: FocusRequester? = null,
    status: (@Composable () -> Unit)? = null,
    scroll: ScrollState = rememberScrollState(),
    scrolls: Boolean = true,
    wide: Boolean = false,
    content: @Composable ColumnScope.(sheet: SheetScope) -> Unit,
) {
    SheetWindow(onDismiss, HlAlpha.VEIL_SIDE, initialFocus) { motion ->
        val c = Hl.colors
        val light = LocalAmbient.current
        val glass = remember(c, light) { { c.tintedGlass(light.glow()) } }
        // Its rows and fields sit on glass.
        CompositionLocalProvider(LocalGround provides Ground.Glass) {
            Column(
                modifier
                    .align(Alignment.CenterEnd)
                    .width(if (wide) HlSideSheetDefaults.wideWidth else HlSideSheetDefaults.width)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // A short way in as it lights, each drawing faded on its own: no offscreen pass.
                        translationX = HlSpace.l.toPx() * (1f - motion.travel)
                        alpha = motion.opacity
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .sheetGlass(glass, c.edge)
                    .padding(
                        start = if (wide) HlSpace.safeH else HlInset.sheet,
                        end = HlSpace.safeH,
                        top = HlSpace.safeV,
                        bottom = HlSpace.safeV,
                    ),
            ) {
                SheetHeader(SheetHead(title, facts, badges, stateText, state, description), artwork, places, status)
                // Its plates fade into the sheet where there are more; the room above and below them keeps a framed
                // picture's light inside the scroll.
                Column(
                    Modifier
                        .weight(1f)
                        .then(if (scrolls) Modifier.scrollEdges(scroll, glass).verticalScroll(scroll) else Modifier)
                        .padding(top = HlSpace.l, bottom = HlFocus.room),
                    verticalArrangement = Arrangement.spacedBy(HlSpace.m),
                ) { content(motion.sheet) }
            }
        }
    }
}

/**
 * A sheet's [glass] in [HlShapes.sheet], read while drawing, and its hairline [edge] on the side facing the screen
 * only: its top and bottom lie on the screen's own edges.
 */
private fun Modifier.sheetGlass(glass: () -> Color, edge: Color): Modifier = drawWithCache {
    val outline = HlShapes.sheet.createOutline(size, layoutDirection, this)
    val corner = HlShapes.sheet.topStart.toPx(size, this)
    val hairline = Stroke(HlSize.hairline.toPx())
    onDrawBehind {
        drawOutline(outline, glass())
        clipRect(left = -hairline.width, right = corner) { drawOutline(outline, edge, style = hairline) }
    }
}

/** The words of a sheet's head: what it does, what about, and how that stands. */
@Immutable
private data class SheetHead(
    val title: String,
    val facts: List<String?>,
    val badges: List<String>,
    val stateText: String?,
    val state: StatusState,
    val description: String?,
)

/**
 * What a sheet does, in `subject`, and what it is about on one line under it, its name first and in white, its
 * printed marks before and its state after; its artwork beside them, as tall as both; its [places] at the end of
 * the title's line, their words on its baseline and their plates hanging past the edge as they do past the start.
 */
@Composable
private fun SheetHeader(
    head: SheetHead,
    artwork: (@Composable () -> Unit)?,
    places: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?,
) {
    val c = Hl.colors
    Layout(
        contents = listOf(
            { artwork?.invoke() },
            { HeadWords(head, capitalsAtTop = artwork != null) },
            { places?.invoke() },
        ),
    ) { (art, text, track), constraints ->
        val picture = art.firstOrNull()
        val block = text.first()
        val hang = HlControl.tabPaddingH.roundToPx()
        val placed = track.firstOrNull()?.measure(Constraints(maxWidth = constraints.maxWidth))
        val width = constraints.maxWidth - (placed?.let { it.width - hang + HlSpace.l.roundToPx() } ?: 0)
        val gap = if (picture != null) HlSpace.m.roundToPx() else 0
        // The artwork as tall as the title and its facts as they set beside it, one line or two, and as wide as
        // that height makes it: the title gives up its width, never the artwork.
        val beside = picture?.let { width - it.maxIntrinsicWidth(block.minIntrinsicHeight(width)) - gap } ?: width
        val tall = block.minIntrinsicHeight(beside.coerceAtLeast(0))
        val wide = picture?.maxIntrinsicWidth(tall)?.coerceAtMost(width - gap) ?: 0
        val words = block.measure(Constraints(maxWidth = (width - wide - gap).coerceAtLeast(0)))
        val shown = picture?.measure(
            Constraints(
                maxWidth = picture.maxIntrinsicWidth(words.height).coerceAtMost(width - gap),
                minHeight = words.height,
                maxHeight = words.height,
            ),
        )
        val start = shown?.let { it.width + gap } ?: 0
        layout(constraints.maxWidth, words.height) {
            shown?.placeRelative(0, 0)
            words.placeRelative(start, 0)
            placed?.let {
                val line = words[FirstBaseline].takeIf { b -> b != AlignmentLine.Unspecified } ?: 0
                val own = it[FirstBaseline].takeIf { b -> b != AlignmentLine.Unspecified } ?: line
                it.placeRelative(constraints.maxWidth - it.width + hang, line - own)
            }
        }
    }
    if (status != null) {
        Spacer(Modifier.height(HlSpace.s))
        status()
    }
    if (head.description != null) {
        Spacer(Modifier.height(HlSpace.l))
        Text(head.description, style = Hl.type.meta, color = c.contentSecondary)
    }
}

/** The title, then its facts after their printed marks and before their state, on one line. */
@Composable
private fun HeadWords(head: SheetHead, capitalsAtTop: Boolean) {
    val c = Hl.colors
    Column {
        // Its capitals level with the artwork's top.
        Text(
            head.title,
            style = Hl.type.subject,
            color = c.content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (capitalsAtTop) Modifier.capitalsAtTop(Hl.type.subject.fontSize) else Modifier,
        )
        val facts = head.badges.isNotEmpty() || head.facts.any { !it.isNullOrBlank() }
        if (facts || head.stateText != null) {
            Spacer(Modifier.height(HlSpace.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (facts) BadgedFactRow(head.badges, head.facts, Modifier.weight(1f, fill = false), lead = c.content)
                head.stateText?.let {
                    if (facts) Spacer(Modifier.width(HlSpace.fact))
                    StatusLight(head.state)
                    Spacer(Modifier.width(HlSpace.xxs))
                    Text(it, style = Hl.type.meta, color = c.contentSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Confirms an irreversible action inside its side sheet: [rows] get `ask` and `origin` (the asking row's
 * modifier); asking replaces them with an attention intertitle ([question], [consequence], Cancel focused,
 * [confirm]). Cancel or Back restores the rows, refocuses the asking row and runs [onCancel].
 */
@Composable
fun SheetConfirm(
    question: String,
    consequence: String,
    confirm: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit = {},
    rows: @Composable (ask: () -> Unit, origin: Modifier) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    // After a cancel, refocus the asking row once it is composed again.
    var answered by remember { mutableStateOf(false) }
    val origin = remember { FocusRequester() }
    if (asking) {
        val cancel = {
            asking = false
            answered = true
            onCancel()
        }
        BackHandler(onBack = cancel)
        ConfirmCard(question, consequence, confirm, onConfirm, cancel)
    } else {
        rows({ asking = true }, Modifier.focusRequester(origin))
        if (answered) {
            LaunchedEffect(Unit) {
                answered = false
                origin.tryRequestFocus()
            }
        }
    }
}

@Composable
private fun ConfirmCard(question: String, consequence: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.tryRequestFocus() }
    Intertitle(question, message = consequence, attention = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.focusRequester(cancel))
            ButtonPlate(confirm, onClick = onConfirm, caution = true)
        }
    }
}

/** The Back hint of an intertitle nothing else fixes. */
@Composable
internal fun BackHint(modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(HlIcons.Back, contentDescription = null, tint = c.contentTertiary, modifier = Modifier.size(HlSize.inlineIcon))
        Spacer(Modifier.width(HlSpace.xs))
        Text(stringResource(R.string.sheet_back_hint), style = Hl.type.caption, color = c.contentTertiary)
    }
}

/** The shape of a [RowPreview]: that of the image's use. */
enum class PreviewShape { Poster, Backdrop, Logo }

/**
 * A small image at the head of a row, in one box whatever it previews ([HlSize.rowPreview], 16:9) on an opaque step
 * of its ground, so it holds on focus's white: a backdrop filling it, a logo fitted in it, a poster framed inside it
 * a step off its top and foot, its corners parallel to the box's. Without an image or on failure, a poster keeps its
 * own empty frame; a backdrop or a logo shows [icon], or the box alone.
 */
@Composable
fun RowPreview(image: ImageRef?, shape: PreviewShape, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Hl.colors
    val url = image?.url(HlSize.rowPreview.toPx())
    // The box's radius less the gap: square at this size.
    val inner = RectangleShape
    Box(
        modifier
            .width(HlSize.rowPreview)
            .aspectRatio(HlSize.PREVIEW_RATIO)
            .clip(HlShapes.thumb)
            .background(groundFill(groundStep()))
            .artworkEdge(HlShapes.thumb, c),
        contentAlignment = Alignment.Center,
    ) {
        if (shape == PreviewShape.Poster) {
            ArtworkImage(
                url,
                description = null,
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = HlSpace.xxs)
                    .aspectRatio(HlSize.POSTER_RATIO, matchHeightConstraintsFirst = true),
                picture = Modifier.clip(inner).artworkEdge(inner, c),
            ) { EmptyFrame(Modifier.fillMaxSize(), inner) }
        } else {
            val logo = shape == PreviewShape.Logo
            ArtworkImage(
                url,
                description = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
                picture = if (logo) Modifier.padding(HlSpace.xxs) else Modifier,
            ) {
                icon?.let { Icon(it, null, Modifier.align(Alignment.Center).size(HlSize.largeIcon), tint = c.contentSecondary) }
            }
        }
    }
}
