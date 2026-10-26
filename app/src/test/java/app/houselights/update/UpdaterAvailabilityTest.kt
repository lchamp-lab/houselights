package app.houselights.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater runs in the project's release alone: as built, as named and as signed. */
class UpdaterAvailabilityTest {
    private fun available(
        built: Boolean = true,
        packageName: String = UpdaterAvailability.RELEASE_PACKAGE,
        signers: List<String> = listOf(PINNED),
        history: List<String> = emptyList(),
    ) = UpdaterAvailability.available(built, packageName, signers, history, PINNED)

    @Test
    fun theProjectsReleaseUpdatesItself() {
        assertTrue(available())
        assertTrue("Its key rotated since", available(signers = listOf(OTHER_KEY), history = listOf(PINNED, OTHER_KEY)))
    }

    @Test
    fun aDebugOrBenchPackageNeverUpdates() {
        assertFalse(available(packageName = "app.houselights.debug"))
        assertFalse(available(packageName = "app.houselights.bench"))
    }

    /** CI's release build (the runner's debug key), :baselineprofile's, a fork's or F-Droid's. */
    @Test
    fun aReleaseSignedWithAnotherKeyNeverUpdates() {
        assertFalse(available(signers = listOf(OTHER_KEY)))
        assertFalse(available(signers = emptyList()))
        assertFalse(available(signers = listOf(PINNED, OTHER_KEY)))
    }

    @Test
    fun aBuildWithoutTheUpdaterHasNone() {
        assertFalse(available(built = false))
    }
}
