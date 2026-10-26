package app.houselights.update

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The release workflow publishes what the app's updater reads, from one certificate, and only once it is whole. */
class ReleaseWorkflowTest {
    private val workflow = File("../.github/workflows/release.yml").readText()

    /** The steps of [job], each from its "- " to the next, as written. */
    private fun steps(job: String): List<String> {
        val start = workflow.indexOf("\n  $job:\n")
        assertTrue("No job $job", start >= 0)
        val end = Regex("""\n  [a-z]+:\n""").find(workflow, start + 1)?.range?.first ?: workflow.length
        return workflow.substring(start, end).split("\n      - ").drop(1)
    }

    private fun step(job: String, name: String) = steps(job).single { it.contains("name: $name\n") }

    @OptIn(ExperimentalSerializationApi::class)
    private val SerialDescriptor.names get() = (0 until elementsCount).map { getElementName(it) }

    @Test
    fun theCertificateHasOneSource() {
        assertTrue("A copy of a certificate", Regex("""\b[0-9a-f]{64}\b""").findAll(workflow).none())
        val read = step("release", "The project's certificate")
        assertTrue(read.contains("houselights\\.releaseCertSha256=") && read.contains("gradle.properties"))
        assertTrue(read.contains("CERT_SHA256=\$cert\" >> \"\$GITHUB_ENV\""))
    }

    @Test
    fun theWorkflowWritesEveryFieldTheAppReads() {
        val written = step("release", "update.json")
        val template = written.substringAfter("jq -n").substringBefore("> update.json")
        val manifest = UpdateManifest.serializer().descriptor
        val apk = ManifestApk.serializer().descriptor
        (manifest.names + apk.names).forEach { field ->
            assertTrue("update.json lacks $field", Regex("""\b$field: """).containsMatchIn(template))
        }
        assertTrue(template.contains("schema: ${UpdateManifest.SCHEMA},"))
        // Read from the APK, as Android will install it.
        assertTrue(written.contains("aapt2\" dump badging \"\$APK\""))
        assertTrue("Checked before it is published", written.contains("jq -e"))
        val published = step("release", "Release")
        assertTrue(published.contains(UpdateManifest.FILE))
        assertTrue(published.contains("\"\$APK\""))
    }

    @Test
    fun theReleaseIsBuiltUnderItsTagsName() {
        assertTrue(step("release", "The signed release").contains("-PversionName=\"\$VERSION\""))
    }

    @Test
    fun aReleaseIsSeenOnlyOnceAllItsFilesAre() {
        val published = step("release", "Release")
        val draft = published.indexOf("gh release create \"\$GITHUB_REF_NAME\" --verify-tag --draft")
        val publish = published.indexOf("gh release edit \"\$GITHUB_REF_NAME\" --draft=false")
        assertTrue(draft >= 0 && publish > draft)
    }

    /** The check job runs untrusted build code: its read-only token is in the guard's environment alone, before any build. */
    @Test
    fun theVersionCodeGuardAloneHasTheChecksToken() {
        val check = steps("check")
        val guard = check.indexOfFirst { it.contains("name: The versionCode grows\n") }
        assertTrue(guard >= 0)
        assertEquals(listOf(guard), check.indices.filter { check[it].contains("GH_TOKEN") })
        assertTrue("Before Gradle", check.indexOfFirst { it.contains("./gradlew") } > guard)
        assertTrue(check[guard].contains("git rev-list --count HEAD"))
        assertTrue("A final after its last beta", check[guard].contains("A final needs at least one commit after its last beta"))
        val header = workflow.substringBefore("\n  release:").substringBefore("    steps:")
        assertTrue("No token for the whole job", !header.substringAfter("\n  check:").contains("GH_TOKEN"))
        assertTrue(workflow.contains("permissions:\n  contents: read\n"))
    }
}
