package app.houselights.ui.design

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** 4 dp grid. [safeH]/[safeV] keep content inside the TV overscan-safe area (~5 %). */
object HlSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    val safeH = 48.dp
    val safeV = 28.dp
}

/**
 * Corner radii follow hierarchy: printed artwork is nearly square, interactive controls are
 * pills, people are circles.
 */
object HlShapes {
    val artwork = RoundedCornerShape(4.dp)
    val field = RoundedCornerShape(6.dp)
    val panel = RoundedCornerShape(10.dp)
    val badge = RoundedCornerShape(3.dp)
    val control = RoundedCornerShape(percent = 50)
    val avatar = CircleShape
}

object HlSize {
    val poster = 112.dp
    val landscape = 220.dp
    val episode = 264.dp
    val avatar = 76.dp
    val iconButton = 48.dp
    val icon = 20.dp
    val progress = 3.dp
    val focusBorder = 2.dp
    val hairline = 1.dp
}

/** How much a focused element grows: artwork the most, controls the least. */
object HlFocus {
    const val ARTWORK_SCALE = 1.1f
    const val CARD_SCALE = 1.06f
    const val CONTROL_SCALE = 1.04f
    /** Dim applied to the other cards of a row while one of them has focus. */
    const val SIBLING_DIM = 0.38f
}
