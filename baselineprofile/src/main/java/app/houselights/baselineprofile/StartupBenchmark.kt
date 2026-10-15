package app.houselights.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cold start-up to home's rows, without any compilation (a fresh install, before the player has
 * compiled anything) and with the baseline profile, on a rooted device:
 * `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` (README, Performance).
 */
@RunWith(Parameterized::class)
class StartupBenchmark(private val compilation: CompilationMode) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStart() {
        // A cold start empties the page cache first, which needs root: a player's user build has
        // none, so there the measure is skipped, not failed (an emulator with `adb root` has it).
        assumeTrue("Cold start-up needs a rooted device (adb root)", rooted())
        measureColdStart()
    }

    private fun measureColdStart() = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            // A fresh install has no library: added once, before the first measure.
            startActivityAndWait()
            ensureLibrary()
            pressHome()
        },
    ) {
        startActivityAndWait()
        awaitHome()
    }

    private fun rooted(): Boolean =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("id").contains("uid=0")

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun compilations() = listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
    }
}

/** Repeats of each measure: enough for a median the player's noise does not move. */
internal const val ITERATIONS = 5
