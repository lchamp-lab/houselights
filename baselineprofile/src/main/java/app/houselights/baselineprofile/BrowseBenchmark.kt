package app.houselights.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Frame timing on home and a film's page, uncompiled and with the baseline profile, as right after an install. */
@RunWith(Parameterized::class)
class BrowseBenchmark(private val compilation: CompilationMode) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun home() = measure { browseHome() }

    @Test
    fun detail() = measure { browseDetail() }

    private fun measure(journey: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = targetPackage,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = ITERATIONS,
        setupBlock = {
            startActivityAndWait()
            ensureLibrary()
            startHome()
        },
        measureBlock = journey,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun compilations() = listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
    }
}
