package app.houselights.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** The APK downloaded from GitHub's addresses to its CDN, checked as it comes, and nothing left of a download that fails. */
class ApkDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    /** Every name leads to the test server, which answers by the host asked for, as GitHub and its CDN would. */
    private val base = OkHttpClient.Builder()
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName(server.hostName))
            },
        )
        .build()

    /** GitHub's client, its hops kept to GitHub's hosts, in plain HTTP as the test server speaks it. */
    private val github = GithubReleases(REPO, base, plainHttp = true)

    private val bytes = ByteArray(200_000) { (it % 251).toByte() }

    private val dir get() = folder.root.resolve("updates")

    private fun at(host: String, path: String) = "http://$host:${server.port}$path"

    private val apkPath = "/$REPO/releases/download/v0.4.1/Houselights-0.4.1.apk"

    /** 0.4.1 as its manifest lists it, served at github.com, the bytes' SHA-256 unless [sha]. */
    private fun apk(sha: String = sha256(bytes)) = offer(size = bytes.size.toLong(), sha256 = sha).copy(url = at("github.com", apkPath))

    private val RecordedRequest.host get() = getHeader("Host").orEmpty().substringBefore(':')

    /** [github]'s answer at github.com, [cdn]'s at objects.githubusercontent.com, nothing elsewhere. */
    private fun serve(
        github: (RecordedRequest) -> MockResponse,
        cdn: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) },
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.host) {
                "github.com" -> github(request)
                "objects.githubusercontent.com" -> cdn(request)
                else -> MockResponse().setResponseCode(500)
            }
        }
    }

    private fun redirect(to: String) = MockResponse().setResponseCode(302).setHeader("Location", to)

    private fun asked() = List(server.requestCount) { server.takeRequest().host }

    private fun download(space: Long = Long.MAX_VALUE) = ApkDownload(dir, github::http, space = { space })

    private fun fetch(offer: UpdateOffer = apk(), space: Long = Long.MAX_VALUE, progress: (Long, Long) -> Unit = { _, _ -> }): File =
        runBlocking { download(space).fetch(offer, progress) }

    private fun failure(offer: UpdateOffer = apk(), space: Long = Long.MAX_VALUE): UpdateFailure =
        assertThrows(UpdateException::class.java) { fetch(offer, space) }.failure

    /** What the download left in its folder. */
    private fun left() = dir.listFiles().orEmpty().map { it.name }

    @After
    fun stop() = server.shutdown()

    @Test
    fun theApkFollowsGithubsRedirectToItsCdn() {
        serve(
            github = { redirect(at("objects.githubusercontent.com", "/github-production-release-asset/1?sig=x")) },
            cdn = { MockResponse().setBody(Buffer().write(bytes)) },
        )
        var told = 0
        var last = 0L
        val apk = fetch { read, total ->
            told++
            last = read
            assertEquals(bytes.size.toLong(), total)
        }
        assertArrayEquals(bytes, apk.readBytes())
        assertEquals("Houselights-0.4.1.apk", apk.name)
        assertEquals(listOf("github.com", "objects.githubusercontent.com"), asked())
        assertEquals(listOf("Houselights-0.4.1.apk"), left())
        assertTrue("Once a hundredth at most: $told", told in 1..101)
        assertEquals(bytes.size.toLong(), last)
    }

    @Test
    fun aRedirectToPlainHttpIsRefused() {
        assertFalse(github.http.followSslRedirects)
        assertTrue(GithubHops.allowed("https://objects.githubusercontent.com/a".toHttpUrl()))
        assertTrue(GithubHops.allowed("https://release-assets.githubusercontent.com/a".toHttpUrl()))
        assertFalse(GithubHops.allowed("http://objects.githubusercontent.com/a".toHttpUrl()))
        assertFalse(GithubHops.allowed("http://github.com/a".toHttpUrl()))
        // The project's own client, which a test cannot serve over HTTPS: a plain HTTP hop never leaves the device.
        serve({ MockResponse().setBody(Buffer().write(bytes)) })
        val strict = ApkDownload(dir, GithubReleases(REPO, base)::http)
        assertThrows(RefusedHopException::class.java) { runBlocking { strict.fetch(apk()) { _, _ -> } } }
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun aHopOutsideGithubIsRefused() {
        assertFalse(GithubHops.allowed("https://githubusercontent.com.example.net/a".toHttpUrl()))
        assertFalse(GithubHops.allowed("https://example.com/a".toHttpUrl()))
        serve({ redirect(at("example.com", "/Houselights-0.4.1.apk")) })
        assertThrows(RefusedHopException::class.java) { fetch() }
        assertEquals(listOf("github.com"), asked())
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun aBodyLongerThanItsManifestIsCut() {
        serve({ MockResponse().setChunkedBody(Buffer().write(bytes).write(ByteArray(10)), 8_192) })
        assertEquals(UpdateFailure.CORRUPT, failure())
        assertEquals(emptyList<String>(), left())
        serve({ MockResponse().setBody(Buffer().write(bytes).write(ByteArray(10))) })
        assertEquals("Its length says so before any byte", UpdateFailure.CORRUPT, failure())
    }

    @Test
    fun aShortBodyFailsAndLeavesNoFile() {
        serve({ MockResponse().setChunkedBody(Buffer().write(bytes.copyOf(150_000)), 8_192) })
        assertEquals(UpdateFailure.DOWNLOAD_CUT, failure())
        assertEquals(emptyList<String>(), left())
        serve({ MockResponse().setBody(Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) })
        assertEquals("The connection lost on the way", UpdateFailure.DOWNLOAD_CUT, failure())
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun aMissingContentLengthIsAllowed() {
        serve({ MockResponse().setChunkedBody(Buffer().write(bytes), 8_192) })
        assertArrayEquals(bytes, fetch().readBytes())
    }

    @Test
    fun cancellingDeletesThePart() = runBlocking {
        serve({ MockResponse().setBody(Buffer().write(bytes)).throttleBody(16_384, 200, TimeUnit.MILLISECONDS) })
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { download().fetch(apk()) { _, _ -> started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        assertTrue(left().single().endsWith(".part"))
        job.cancel()
        withTimeout(10_000) { job.join() }
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun aWrongSha256IsRefused() {
        serve({ MockResponse().setBody(Buffer().write(bytes)) })
        assertEquals(UpdateFailure.CORRUPT, failure(apk(sha = "0".repeat(64))))
        assertEquals(emptyList<String>(), left())
    }

    @Test
    fun tooLittleSpaceSaysSoBeforeDownloading() {
        serve({ MockResponse().setBody(Buffer().write(bytes)) })
        assertEquals(UpdateFailure.STORAGE, failure(space = bytes.size * 3L - 1))
        assertEquals(0, server.requestCount)
        assertEquals(bytes.size * 3L, apk().spaceNeeded)
    }

    @Test
    fun githubRefusingTheFileSaysWhy() {
        serve({ MockResponse().setResponseCode(404) })
        val refused = assertThrows(GithubStatusException::class.java) { fetch() }
        assertEquals(UpdateFailure.GITHUB_FAILED, failureOf(refused, UpdateFailure.OFFLINE))
        assertEquals(emptyList<String>(), left())
    }
}
