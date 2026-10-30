package app.houselights.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Generates the baseline profile (CONTRIBUTING.md, Performance); journeys run first, adding the library if needed. */
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
        browseSagas()
        startHome()
        browseSaga()
        startHome()
        browseDetail()
        startHome()
        browseShow()
        startHome()
        browseSearch()
        startHome()
        browseSettings()
    }

    /** What start-up runs, also laid out first in the dex of minified builds. */
    @Test
    fun startup() = rule.collect(packageName = targetPackage, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        awaitHome()
    }
}
