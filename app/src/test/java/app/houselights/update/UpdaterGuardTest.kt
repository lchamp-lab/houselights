package app.houselights.update

import app.houselights.testing.kotlinSources
import app.houselights.testing.linesWhere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** What the updater asks of Android, and the certificate it pins, read where they are written. */
class UpdaterGuardTest {
    private val permission = "android.permission.REQUEST_INSTALL_PACKAGES"

    /** The pinned certificate's only copy. */
    private val pinned = File("../gradle.properties").readLines()
        .single { it.startsWith("houselights.releaseCertSha256=") }.substringAfter('=').trim()

    @Test
    fun onlyTheUpdatersManifestAsksToInstallPackages() {
        val manifests = File("src").walkTopDown().filter { it.name == "AndroidManifest.xml" }.toList()
        assertTrue(manifests.any { it.path.endsWith("main/AndroidManifest.xml") })
        val asking = manifests.filter { it.readText().contains(permission) }.map { it.invariantSeparatorsPath }
        assertEquals(listOf("src/updater/AndroidManifest.xml"), asking)
        // The release's alone, and only when it has the updater (-Phouselights.updater=false builds it without).
        val build = File("build.gradle.kts").readText()
        val onlyInTheRelease = """if (updaterBuilt) sourceSets.getByName("release").manifest.srcFile("src/updater/AndroidManifest.xml")"""
        assertTrue(build.contains(onlyInTheRelease))
    }

    /** Android's confirmation is never skipped: no session asks to install without the user. */
    @Test
    fun theSessionNeverAsksToSkipTheConfirmation() {
        val sources = File("src/main/java")
        val found = kotlinSources(sources).linesWhere(sources) { "USER_ACTION_NOT_REQUIRED" in it || "setRequireUserAction" in it }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun theReadmesFingerprintIsThePinnedOne() {
        val colons = Regex("""^([0-9A-F]{2}:){31}[0-9A-F]{2}$""")
        val readme = File("../README.md").readLines().map(String::trim).filter(colons::matches)
        val expected = pinned.uppercase(Locale.ROOT).chunked(2).joinToString(":")
        assertEquals(listOf(expected), readme.distinct())
    }

    @Test
    fun theBuildPinsTheCertificateFromGradleProperties() {
        assertTrue(Regex("[0-9a-f]{64}").matches(pinned))
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("""providers.gradleProperty("houselights.releaseCertSha256")"""))
        assertFalse("No copy of it in the build", build.contains(pinned))
        val sources = File("src/main/java")
        assertTrue("Nor in the code", kotlinSources(sources).none { pinned in it.text })
    }
}
