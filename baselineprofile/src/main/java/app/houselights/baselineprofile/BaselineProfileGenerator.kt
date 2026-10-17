package app.houselights.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Generates the baseline profile: the code the main journeys run, compiled ahead of time on the
 * player. `./gradlew :app:generateReleaseBaselineProfile` with the emulator running and rooted
 * (`adb root`); the profile lands in app/src/main/generated/baselineProfiles (README, Performance).
 *
 * The journeys run first: on a fresh install they add the library, which start-up then finds.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun journeys() = rule.collect(packageName = targetPackage) {
        pressHome()
        startActivityAndWait()
        ensureLibrary()
        startHome()
        browseHome()
        startHome()
        browseLibrary()
        startHome()
        browseDetail()
        startHome()
        browseShow()
        startHome()
        browseSearch()
        startHome()
        browseSettings()
    }

    /** What start-up runs, also laid out first in the dex file of minified builds. */
    @Test
    fun startup() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        awaitHome()
    }
}
