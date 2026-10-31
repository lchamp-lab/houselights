package app.houselights.ui.design

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.floor
import kotlin.math.sin

/** The sRGB level a screen writes for a grey of relative [luminance]. */
fun srgb(luminance: Float): Float = ColorSpaces.Srgb.oetf(luminance.toDouble()).toFloat()

/** The relative luminance of an sRGB grey [level]. */
fun linear(level: Float): Float = ColorSpaces.Srgb.eotf(level.toDouble()).toFloat()

/** A grain from 0 to 1 at each pixel, the same at every run: a busy picture without a file. */
fun noise(x: Int, y: Int): Float = (sin(x * NOISE_X + y * NOISE_Y) * NOISE_SCALE).let { it - floor(it) }

/** The usual hash of shaders, `fract(sin(dot(xy, (12.9898, 78.233))) * 43758.5453)`. */
private const val NOISE_X = 12.9898f
private const val NOISE_Y = 78.233f
private const val NOISE_SCALE = 43758.547f
