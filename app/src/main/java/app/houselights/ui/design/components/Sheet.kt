package app.houselights.ui.design.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.houselights.R
import app.houselights.core.ImageRef
import app.houselights.ui.design.HlSideSheetDefaults
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.Ground
import app.houselights.ui.design.LocalGround
import app.houselights.ui.design.groundStepFill
import app.houselights.ui.design.scrollEdges
import app.houselights.ui.design.tryRequestFocus
import app.houselights.ui.util.toPx
import app.houselights.ui.design.capitalsAtTop
import app.houselights.ui.design.edge

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
 * Sheets). No Close button: Back or [SheetScope.dismiss] closes it; [scroll] lets a text-only panel scroll.
 */
@Composable
fun HlSideSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subject: String? = null,
    facts: List<String?> = emptyList(),
    description: String? = null,
    artwork: (@Composable () -> Unit)? = null,
    initialFocus: FocusRequester? = null,
    status: (@Composable () -> Unit)? = null,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.(sheet: SheetScope) -> Unit,
) {
    SheetWindow(onDismiss, HlAlpha.VEIL_SIDE, initialFocus) { motion ->
        val c = Hl.colors
        // Its rows and fields sit on glass.
        CompositionLocalProvider(LocalGround provides Ground.Glass) {
            Column(
                modifier
                    .align(Alignment.CenterEnd)
                    .width(HlSideSheetDefaults.width)
                    .fillMaxHeight()
                    .graphicsLayer {
                        // A short way in as it lights, each drawing faded on its own: no offscreen pass.
                        translationX = HlSpace.l.toPx() * (1f - motion.travel)
                        alpha = motion.opacity
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .sheetGlass(c.glass, c.edge)
                    .padding(start = HlInset.sheet, end = HlSpace.safeH, top = HlSpace.safeV, bottom = HlSpace.safeV),
            ) {
                SheetHeader(title, subject, facts, description, artwork, status)
                Spacer(Modifier.height(HlSpace.m))
                HlDivider()
                Spacer(Modifier.height(HlSpace.m))
                // Its rows fade into the sheet where there are more.
                Column(
                    Modifier.weight(1f).scrollEdges(scroll, c.glass).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(HlSpace.xxs),
                ) { content(motion.sheet) }
                Spacer(Modifier.height(HlSpace.m))
                BackHint()
            }
        }
    }
}

/**
 * A sheet's [glass] in [HlShapes.sheet], and its hairline [edge] on the side facing the screen only: its top
 * and bottom lie on the screen's own edges.
 */
private fun Modifier.sheetGlass(glass: Color, edge: Color): Modifier = drawWithCache {
    val outline = HlShapes.sheet.createOutline(size, layoutDirection, this)
    val corner = HlShapes.sheet.topStart.toPx(size, this)
    val hairline = Stroke(HlSize.hairline.toPx())
    onDrawBehind {
        drawOutline(outline, glass)
        clipRect(left = -hairline.width, right = corner) { drawOutline(outline, edge, style = hairline) }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    subject: String?,
    facts: List<String?>,
    description: String?,
    artwork: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?,
) {
    val c = Hl.colors
    // The text starts at the artwork's top.
    Row(verticalAlignment = Alignment.Top) {
        if (artwork != null) {
            artwork()
            Spacer(Modifier.width(HlSpace.m))
        }
        Column {
            Text(
                title,
                style = Hl.type.subject,
                color = c.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // Its capitals level with the artwork's top.
                modifier = if (artwork != null) Modifier.capitalsAtTop(Hl.type.subject.fontSize) else Modifier,
            )
            if (subject != null) {
                Spacer(Modifier.height(HlSpace.xxs))
                Text(subject, style = Hl.type.entry, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (facts.any { !it.isNullOrBlank() }) {
                Spacer(Modifier.height(HlSpace.xxs))
                FactRow(facts)
            }
            if (status != null) {
                Spacer(Modifier.height(HlSpace.s))
                status()
            }
        }
    }
    if (description != null) {
        Spacer(Modifier.height(HlSpace.s))
        Text(description, style = Hl.type.caption, color = c.contentSecondary)
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

/** The Back hint at a sheet's foot. */
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
 * A small image at the head of a row, in its use's shape (a logo on its dark plate), in a shared slot so
 * row names line up; without an image or on failure, [icon] or an empty frame.
 */
@Composable
fun RowPreview(image: ImageRef?, shape: PreviewShape, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Hl.colors
    val width: Dp = if (shape == PreviewShape.Poster) HlSize.rowPreviewPoster else HlSize.rowPreviewWide
    val logo = shape == PreviewShape.Logo
    Box(modifier.width(HlSize.rowPreviewWide).height(HlSize.rowPreview), contentAlignment = Alignment.Center) {
        val frame = Modifier.width(width).height(HlSize.rowPreview)
        ArtworkImage(
            image?.url(width.toPx()),
            description = null,
            contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
            picture = frame
                .clip(HlShapes.thumb)
                .background(if (logo) c.background else groundStepFill())
                .padding(if (logo) HlSpace.xxs else HlSpace.none),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.largeIcon))
            } else {
                EmptyFrame(frame, HlShapes.thumb)
            }
        }
    }
}
