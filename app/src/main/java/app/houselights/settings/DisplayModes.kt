package app.houselights.settings

import android.view.Display
import kotlin.math.abs

/** A mode the system lists for the screen: its size in pixels, its rate, its id for the window. */
data class OutputMode(val width: Int, val height: Int, val refreshRate: Float, val id: Int = 0)

/** The modes this display lists, as the system says them. */
fun Display.outputModes(): List<OutputMode> =
    supportedModes.map { OutputMode(it.physicalWidth, it.physicalHeight, it.refreshRate, it.modeId) }

/** Who sets the picture the TV receives, read from the modes the system lists. */
enum class OutputControl {
    /** The system lists 4K at 60 Hz: the interface can ask for it. */
    Ui4k,

    /** Real 4K modes, none at 60 Hz: the system's mode stays, there is nothing to choose. */
    System,

    /**
     * No mode at 4K: the player sets its output in its own settings and scales the interface to
     * it. The Zidoo lists only stand-in 1080p modes (the active one says 30 Hz) while its HDMI
     * signal is 2160p60: a mode asked for there does nothing, and the modes listed are not real.
     */
    Player,
}

object DisplayModes {
    /**
     * The mode "4K 60 Hz" asks for: at least 3840 × 2160, nearest to 60 Hz and within 1 Hz of it; at
     * the same rate, 3840 wide (a 16:9 TV's) before DCI 4K's 4096, whatever order the system lists them.
     */
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
