package app.posterwall.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object Palette {
    val Background = Color(0xFF07080B)
    val Surface = Color(0xFF14161C)
    val SurfaceHigh = Color(0xFF1F222A)
    val Accent = Color(0xFFE9B949) // projector-light gold
    val OnAccent = Color(0xFF1A1405)
    val Text = Color(0xFFF2F2F4)
    val TextDim = Color(0xFFA9ADB8)
    val TextFaint = Color(0xFF6E7380)
    val Watched = Color(0xFF7FD18B)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    secondary = Palette.SurfaceHigh,
    onSecondary = Palette.Text,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    border = Palette.Text,
)

private val type = Typography(
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, lineHeight = 50.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
)

@Composable
fun PosterwallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
