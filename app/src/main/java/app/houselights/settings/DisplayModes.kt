package app.houselights.settings

import android.view.Display
import kotlin.math.abs

/** A display mode the system lists: size in pixels, refresh rate, and mode id for the window. */
data class OutputMode(val width: Int, val height: Int, val refreshRate: Float, val id: Int = 0)

fun Display.outputModes(): List<OutputMode> =
    supportedModes.map { OutputMode(it.physicalWidth, it.physicalHeight, it.refreshRate, it.modeId) }

/** Who sets the picture the TV receives, read from the modes the system lists. */
enum class OutputControl {
    /** The system lists 4K at 60 Hz: the UI can request it. */
    Ui4k,

    /** Real 4K modes but none at 60 Hz: the system mode stays, there is nothing to choose. */
    System,

    /**
     * No 4K mode: the player sets its own output. The Zidoo lists only placeholder 1080p modes (the active one
     * says 30 Hz) while its HDMI output is 2160p60, so a requested mode does nothing.
     */
    Player,
}

object DisplayModes {
    /** The "4K 60 Hz" mode: at least 3840 × 2160 within 1 Hz of 60, and 3840 wide before DCI's 4096 at equal rate. */
    fun ui4k(modes: List<OutputMode>): OutputMode? = modes
        .filter { it.isUltraHd }
        .minWithOrNull(compareBy<OutputMode>({ abs(it.refreshRate - UI_RATE) }, { it.width != UHD_WIDTH }))
        ?.takeIf { abs(it.refreshRate - UI_RATE) < RATE_TOLERANCE }

    fun control(modes: List<OutputMode>): OutputControl = when {
        ui4k(modes) != null -> OutputControl.Ui4k
        modes.any { it.isUltraHd } -> OutputControl.System
        else -> OutputControl.Player
    }

    private val OutputMode.isUltraHd get() = width >= UHD_WIDTH && height >= UHD_HEIGHT

    private const val UHD_WIDTH = 3840
    private const val UHD_HEIGHT = 2160
    private const val UI_RATE = 60f

    /** 59.94 Hz counts as 60. */
    private const val RATE_TOLERANCE = 1f
}
