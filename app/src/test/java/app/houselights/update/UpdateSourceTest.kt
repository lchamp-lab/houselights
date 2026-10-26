package app.houselights.update

import app.houselights.testing.Answer
import app.houselights.testing.FakeServer
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which release GitHub's list offers, read as GitHub answers it, and how its answers are asked. */
@RunWith(RobolectricTestRunner::class)
class UpdateSourceTest {
    private var answer: (Request) -> Answer = { Answer(404) }
    private val server = FakeServer { answer(it) }
    private val github = GithubReleases(REPO, server.http, clock = { START })
    private val choice = ReleaseChoice(github::downloads, PINNED, sdk = 28)

    /** GitHub listing [releases], each manifest at its address in [manifests]. */
    private fun serve(vararg releases: ListedRelease, manifests: Map<String, String> = emptyMap()) {
        answer = { request ->
            when (request.url.host) {
                "api.github.com" -> Answer(200, listJson(*releases), mapOf("ETag" to "W/\"e1\""))
                else -> manifests[request.url.toString()]?.let { Answer(200, it) } ?: Answer(404)
            }
        }
    }

    private fun manifest(tag: String, versionCode: Long, text: String = manifestJson(tag, versionCode)) =
        downloads(tag) + "update.json" to text

    private fun found(beta: Boolean = false, installed: Long = 946): Found = runBlocking {
        val listing = github.list(null) as Listing.Fresh
        choice.pick(listing.releases, beta, installed) { github.manifest(it.manifest.url) }
    }

    private fun offered(beta: Boolean = false, installed: Long = 946): String? = (found(beta, installed) as? Found.Offer)?.offer?.tag

    private val three = arrayOf(release("v0.4.2-beta.1"), release("v0.4.1"), release("v0.4.0"))
    private val manifests = mapOf(manifest("v0.4.2-beta.1", 1020), manifest("v0.4.1", 1010), manifest("v0.4.0", 1000))

    @Test
    fun aStableUserIsOfferedTheNewestStableReleaseOnly() {
        serve(*three, manifests = manifests)
        assertEquals("v0.4.1", offered())
        val offer = (found() as Found.Offer).offer
        assertEquals(1010L, offer.versionCode)
        assertEquals(downloads("v0.4.1") + "Houselights-0.4.1.apk", offer.url)
    }

    @Test
    fun aBetaUserIsOfferedTheNewestPreReleaseOrStable() {
        serve(*three, manifests = manifests)
        assertEquals("v0.4.2-beta.1", offered(beta = true))
        serve(release("v0.4.2"), *three, manifests = manifests + manifest("v0.4.2", 1021))
        assertEquals("v0.4.2", offered(beta = true))
    }

    @Test
    fun theFinalReleaseIsOfferedOverItsOwnBeta() {
        // The beta listed first: GitHub orders by the commit's date, and the final may be tagged on an older one.
        serve(release("v0.4.1-beta.2"), release("v0.4.1"), manifests = mapOf(manifest("v0.4.1-beta.2", 1015), manifest("v0.4.1", 1016)))
        assertEquals("v0.4.1", offered(beta = true))
    }

    @Test
    fun aReleaseNoNewerThanTheInstalledCodeIsNotOffered() {
        serve(*three, manifests = manifests)
        assertEquals(Found.UpToDate, found(installed = 1010))
        assertEquals(Found.UpToDate, found(beta = true, installed = 1020))
    }

    @Test
    fun aDraftOrAReleaseWithoutItsManifestIsSkipped() {
        serve(
            release("v0.4.3", draft = true),
            release("v0.4.2", withManifest = false),
            release("v0.4.1", withApk = false),
            release("v0.4.0"),
            manifests = mapOf(manifest("v0.4.3", 1030), manifest("v0.4.2", 1020), manifest("v0.4.1", 1010), manifest("v0.4.0", 1000)),
        )
        assertEquals("v0.4.0", offered())
    }

    @Test
    fun aPreReleaseNotTaggedAsABetaIsSkipped() {
        serve(release("v0.4.2", prerelease = true), release("v0.4.1-beta.1", prerelease = false), release("v0.4.0"), manifests = manifests)
        assertEquals("v0.4.0", offered(beta = true))
    }

    @Test
    fun filesServedElsewhereThanTheReleasesDownloadsAreSkipped() {
        serve(
            release("v0.4.2", under = "https://example.com/lchamp-lab/houselights/releases/download/v0.4.2/"),
            release("v0.4.1", under = "https://github.com/someone/houselights/releases/download/v0.4.1/"),
            release("v0.4.0"),
            manifests = manifests,
        )
        assertEquals("v0.4.0", offered())
    }

    /** The manifest says which key signed the APK for whoever reads it: the APK's own signer decides ([UpdateVerifier]). */
    @Test
    fun aManifestNamingAnotherCertificateIsStillReadForItsApkToDecide() {
        serve(release("v0.4.1"), manifests = mapOf(manifest("v0.4.1", 1010, manifestJson("v0.4.1", 1010, cert = OTHER_KEY))))
        assertEquals("v0.4.1", offered())
    }

    @Test
    fun aManifestDisagreeingWithItsAssetIsSkipped() {
        val sha = "b".repeat(64)
        serve(
            release("v0.4.4"),
            release("v0.4.3", digest = "sha256:${"c".repeat(64)}"),
            release("v0.4.2"),
            release("v0.4.1", digest = "sha256:$sha"),
            manifests = mapOf(
                manifest("v0.4.4", 1040, manifestJson("v0.4.4", 1040, size = APK_SIZE + 1)),
                manifest("v0.4.3", 1030, manifestJson("v0.4.3", 1030, sha256 = sha)),
                manifest("v0.4.2", 1020, manifestJson("v0.4.1", 1020)),
                manifest("v0.4.1", 1010, manifestJson("v0.4.1", 1010, sha256 = sha)),
            ),
        )
        // Three manifests are read at most: the fourth is found at the next check, once the first is fixed.
        assertEquals(Found.UpToDate, found())
        serve(
            release("v0.4.3", digest = "sha256:${"c".repeat(64)}"),
            release("v0.4.2"),
            release("v0.4.1", digest = "sha256:$sha"),
            manifests = mapOf(
                manifest("v0.4.3", 1030, manifestJson("v0.4.3", 1030, sha256 = sha)),
                manifest("v0.4.2", 1020, manifestJson("v0.4.2", 1020, versionName = "0.4.2-beta.1")),
                manifest("v0.4.1", 1010, manifestJson("v0.4.1", 1010, sha256 = sha)),
            ),
        )
        assertEquals("v0.4.1", offered())
    }

    @Test
    fun aManifestOfANewerSchemaIsUnreadable() {
        val newer = manifest("v0.5.0", 1100, manifestJson("v0.5.0", 1100, schema = 2))
        serve(release("v0.5.0"), release("v0.4.0"), manifests = mapOf(newer, manifest("v0.4.0", 1000)))
        assertEquals("A newer release this version cannot read is said", Found.Unreadable, found(installed = 1000))
        assertEquals("v0.4.0", offered(installed = 946))
        serve(release("v0.5.0"), manifests = mapOf(manifest("v0.5.0", 1100, "<html>Not a manifest</html>")))
        assertEquals(Found.Unreadable, found())
    }

    @Test
    fun aReleaseForANewerAndroidIsSkipped() {
        val newer = manifest("v0.5.0", 1100, manifestJson("v0.5.0", 1100, minSdk = 30))
        serve(release("v0.5.0"), release("v0.4.0"), manifests = mapOf(newer, manifest("v0.4.0", 1000)))
        assertEquals("v0.4.0", offered())
    }

    @Test
    fun theNotesAreInTheAppsLanguageElseEnglish() {
        serve(release("v0.4.1"), manifests = manifests)
        val offer = (found() as Found.Offer).offer
        assertEquals(listOf("Les sous-titres forcés sont de nouveau choisis."), offer.notesIn("fr"))
        assertEquals(listOf("Forced subtitles are chosen again."), offer.notesIn("de"))
        val long = offer.copy(notes = mapOf("en" to "# Houselights 0.4.1\n\n- One...\n* Two"))
        assertEquals(listOf("One…", "Two"), long.notesIn("en"))
    }

    @Test
    fun theNotesAreClampedToWhatTheSheetShows() {
        val notes = "x".repeat(5_000)
        serve(release("v0.4.1"), manifests = mapOf(manifest("v0.4.1", 1010, manifestJson("v0.4.1", 1010, notes = """{"en":"$notes"}"""))))
        val offer = (found() as Found.Offer).offer
        assertEquals(UpdateOffer.NOTES_MAX, offer.notes.getValue("en").length)
    }

    @Test
    fun anUnchangedListAnswers304AndAsksNothingMore() = runBlocking {
        answer = { request ->
            if (request.header("If-None-Match") == "W/\"e1\"") Answer(304) else Answer(200, listJson(*three), mapOf("ETag" to "W/\"e1\""))
        }
        val first = github.list(null) as Listing.Fresh
        assertEquals("W/\"e1\"", first.etag)
        assertNull(server.requests.single().header("If-None-Match"))
        assertEquals(Listing.Unchanged, github.list(first.etag))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun githubsRateLimitWaitsUntilItsReset() {
        fun limited(resetS: Long): Long? {
            val headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$resetS")
            answer = { Answer(403, """{"message":"API rate limit exceeded"}""", headers) }
            return assertThrows(GithubStatusException::class.java) { runBlocking { github.list(null) } }.waitUntil
        }
        val now = START / 1000
        assertEquals(START + 20 * MINUTE, limited(now + 20 * 60))
        assertEquals("An hour at most", START + HOUR, limited(now + 3 * 60 * 60))
        assertEquals("A minute at least", START + MINUTE, limited(now - 60))
        answer = { Answer(429, "") }
        assertEquals(START + MINUTE, assertThrows(GithubStatusException::class.java) { runBlocking { github.list(null) } }.waitUntil)
    }

    @Test
    fun aSecondaryLimitWaitsItsRetryAfter() {
        answer = { Answer(403, """{"message":"You have exceeded a secondary rate limit"}""", mapOf("Retry-After" to "120")) }
        assertEquals(START + 2 * MINUTE, assertThrows(GithubStatusException::class.java) { runBlocking { github.list(null) } }.waitUntil)
        answer = { Answer(403, """{"message":"Forbidden"}""") }
        val refused = assertThrows(GithubStatusException::class.java) { runBlocking { github.list(null) } }
        assertNull("Not a limit: GitHub failed", refused.waitUntil)
        assertEquals(UpdateFailure.GITHUB_FAILED, failureOf(refused, UpdateFailure.OFFLINE))
    }

    @Test
    fun anAnswerOverItsCapIsRefused() {
        answer = { Answer(200, "[" + " ".repeat(GithubReleases.LIST_MAX_BYTES.toInt()) + "]") }
        val refused = assertThrows(TooLargeException::class.java) { runBlocking { github.list(null) } }
        assertEquals(UpdateFailure.GITHUB_FAILED, failureOf(refused, UpdateFailure.OFFLINE))
        answer = { Answer(200, " ".repeat(GithubReleases.MANIFEST_MAX_BYTES.toInt() + 1)) }
        assertThrows(TooLargeException::class.java) { runBlocking { github.manifest(downloads("v0.4.1") + "update.json") } }
    }

    @Test
    fun everyRequestNamesTheAppAndCarriesNoCredentials() {
        serve(*three, manifests = manifests)
        found()
        val requests = server.requests.toList()
        assertTrue(requests.size >= 2)
        requests.forEach { request ->
            assertTrue(request.url.toString(), request.header("User-Agent")!!.startsWith("Houselights/"))
            assertNull(request.url.toString(), request.header("Authorization"))
            assertTrue(request.url.isHttps)
        }
        val api = requests.first()
        assertEquals("/repos/$REPO/releases", api.url.encodedPath)
        assertEquals("10", api.url.queryParameter("per_page"))
        assertEquals("application/vnd.github+json", api.header("Accept"))
        assertEquals("2022-11-28", api.header("X-GitHub-Api-Version"))
    }
}
