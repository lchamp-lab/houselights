package app.houselights.ui.design.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.houselights.ui.design.Hl
import app.houselights.ui.design.HlAlpha
import app.houselights.ui.design.HlIcons
import app.houselights.ui.design.HlInset
import app.houselights.ui.design.HlLayout
import app.houselights.ui.design.HlMotion
import app.houselights.ui.design.HlShapes
import app.houselights.ui.design.HlSize
import app.houselights.ui.design.HlSpace
import app.houselights.ui.design.scrollEdges
import app.houselights.ui.util.toPx
import coil.compose.AsyncImage

/**
 * What the content of a sheet can do: close it, with its motion, then act once it has gone.
 * Choosing an option and Back both close this way.
 */
@Stable
class SheetScope internal constructor(private val close: (then: () -> Unit) -> Unit) {
    /** Takes the sheet away, then tells its owner and runs [then]. Ignored while it is leaving. */
    fun dismiss(then: () -> Unit = {}) = close(then)
}

/**
 * How a sheet or a menu comes and goes: it comes in as it is composed; [SheetScope.dismiss] takes
 * it away with the same motion, then tells its owner ([rememberSheetMotion]'s `onDismiss`) and runs
 * `then`. [progress] (0 gone, 1 shown) is read while drawing; while it is [leaving], keys are
 * ignored, so a second OK cannot choose twice.
 */
@Stable
internal class SheetMotion(
    private val shown: MutableTransitionState<Boolean>,
    private val animated: State<Float>,
    val sheet: SheetScope,
) {
    val progress: Float get() = animated.value
    val leaving: Boolean get() = !shown.targetState
}

/** The motion of a sheet or a menu: [STANDARD][HlMotion.STANDARD], emphasized, in and out. */
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
    val progress = transition.animateFloat({ tween(HlMotion.STANDARD, easing = HlMotion.emphasized) }, label = "sheetProgress") {
        if (it) 1f else 0f
    }
    if (shown.isIdle && !shown.currentState) {
        LaunchedEffect(Unit) {
            onDismiss()
            then()
        }
    }
    return remember { SheetMotion(shown, progress, sheet) }
}

/** Keys go nowhere while a sheet or a menu leaves. */
internal fun Modifier.stillWhileLeaving(motion: SheetMotion): Modifier = onPreviewKeyEvent { motion.leaving }

/**
 * The window under every sheet and modal: the room dimmed by our own scrim (not the platform's
 * grey one, nor its window animation), the motion of [rememberSheetMotion], and [onDismiss] only
 * once gone. [content] reads its progress (0 gone, 1 shown) while drawing.
 */
@Composable
internal fun SheetWindow(
    onDismiss: () -> Unit,
    scrim: Float,
    initialFocus: FocusRequester?,
    content: @Composable BoxScope.(progress: () -> Float, sheet: SheetScope) -> Unit,
) {
    val motion = rememberSheetMotion(onDismiss)
    Dialog(onDismissRequest = { motion.sheet.dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        LaunchedEffect(Unit) { initialFocus?.let { runCatching { it.requestFocus() } } }
        val room = Hl.colors.background
        Box(
            Modifier
                .fillMaxSize()
                .stillWhileLeaving(motion)
                .drawBehind { drawRect(room.copy(alpha = scrim * motion.progress)) },
        ) {
            content({ motion.progress }, motion.sheet)
        }
    }
}

/**
 * A panel the full height of the screen, against its right edge, for acting on what the screen
 * shows (editing a title, the actions of a folder, the choices of a setting) while it stays in
 * view on the left. From the top: what the panel does ([title], in the voice of the poster), what
 * it is about ([artwork], [subject], [facts], where it stands as an [HlStatus] in [status],
 * [description]), a hairline, the rows, and the Back hint. There is no Close button: Back closes,
 * as choosing does through [SheetScope.dismiss].
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
    content: @Composable ColumnScope.(sheet: SheetScope) -> Unit,
) {
    SheetWindow(onDismiss, HlAlpha.SCRIM_SIDE, initialFocus) { progress, sheet ->
        val body = rememberScrollState()
        Row(
            modifier
                .align(Alignment.CenterEnd)
                .width(HlLayout.sideSheet)
                .fillMaxHeight()
                .graphicsLayer { translationX = size.width * (1f - progress()) }
                .background(Hl.colors.surfaceRaised),
        ) {
            HlDivider(vertical = true)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = HlInset.sheet, end = HlSpace.safeH, top = HlSpace.safeV, bottom = HlSpace.safeV),
            ) {
                SheetHeader(title, subject, facts, description, artwork, status)
                Spacer(Modifier.height(HlSpace.m))
                HlDivider()
                Spacer(Modifier.height(HlSpace.m))
                // Its rows fade into the sheet where there are more.
                Column(
                    Modifier.weight(1f).scrollEdges(body, Hl.colors.surfaceRaised).verticalScroll(body),
                    verticalArrangement = Arrangement.spacedBy(HlSpace.xxs),
                ) { content(sheet) }
                Spacer(Modifier.height(HlSpace.m))
                BackHint()
            }
        }
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (artwork != null) {
            artwork()
            Spacer(Modifier.width(HlSpace.m))
        }
        Column {
            Text(title, style = Hl.type.subject, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subject != null) {
                Spacer(Modifier.height(HlSpace.xxs))
                Text(subject, style = Hl.type.entry, color = c.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (facts.any { !it.isNullOrBlank() }) {
                Spacer(Modifier.height(HlSpace.xxs))
                FactRow(facts)
            }
        }
    }
    if (status != null) {
        Spacer(Modifier.height(HlSpace.s))
        status()
    }
    if (description != null) {
        Spacer(Modifier.height(HlSpace.s))
        Text(description, style = Hl.type.detail, color = c.contentSecondary)
    }
}

/**
 * An action that cannot be undone asks first, in its side sheet. [rows] show until then: they
 * receive `ask`, for the row that asks, and `origin`, the modifier that names that row. Asking
 * puts a card in their place: a short tungsten rule (attention), the [question] in the voice of
 * the poster, its [consequence], then Cancel, focused, and [confirm], its label tungsten at rest,
 * never red. Cancel, or Back, brings the rows back with focus on the row that asked, then runs
 * [onCancel]; [confirm] runs [onConfirm].
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
    // Cancelled: focus goes back to the row that asked, once it is there again.
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
                runCatching { origin.requestFocus() }
            }
        }
    }
}

/** The intertitle a [SheetConfirm] asks with, in place of the rows of its sheet. */
@Composable
private fun ConfirmCard(question: String, consequence: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
    Intertitle(question, message = consequence, attention = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(HlSpace.s)) {
            HlButton(stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.focusRequester(cancel))
            PillButton(confirm, onClick = onConfirm, caution = true)
        }
    }
}

/** How to leave a sheet: the Back key, said once at its foot. */
@Composable
internal fun BackHint(modifier: Modifier = Modifier) {
    val c = Hl.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(HlIcons.Back, contentDescription = null, tint = c.contentTertiary, modifier = Modifier.size(HlSize.inlineIcon))
        Spacer(Modifier.width(HlSpace.xs))
        Text(stringResource(R.string.sheet_back_hint), style = Hl.type.caption, color = c.contentTertiary)
    }
}

/** How a preview sits at the head of a row: at the shape the image is used in. */
enum class PreviewShape { Poster, Backdrop, Logo }

/**
 * An image as it is used, small, at the head of a row: a poster, a backdrop, a logo on its dark
 * plate. Every preview takes the same slot, so the names of the rows line up; with no image, or
 * one that fails to load, the slot shows [icon], or an empty frame.
 */
@Composable
fun RowPreview(image: ImageRef?, shape: PreviewShape, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Hl.colors
    val width: Dp = if (shape == PreviewShape.Poster) HlSize.rowPreviewPoster else HlSize.rowPreviewWide
    val url = image?.url(width.toPx())
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.width(HlSize.rowPreviewWide).height(HlSize.rowPreview), contentAlignment = Alignment.Center) {
        val frame = Modifier.width(width).height(HlSize.rowPreview)
        when {
            url != null && !failed -> Box(
                frame.clip(HlShapes.artwork).background(if (shape == PreviewShape.Logo) c.background else c.surface),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = if (shape == PreviewShape.Logo) ContentScale.Fit else ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize().padding(if (shape == PreviewShape.Logo) HlSpace.xxs else HlSpace.none),
                )
            }
            icon != null -> Icon(icon, contentDescription = null, tint = c.contentSecondary, modifier = Modifier.size(HlSize.discIcon))
            else -> EmptyFrame(frame)
        }
    }
}
