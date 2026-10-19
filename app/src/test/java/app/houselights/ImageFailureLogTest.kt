package app.houselights

import androidx.test.core.app.ApplicationProvider
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.NullRequestDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.IOException

/** A failed image load is logged with its reason, once per host and period rather than once per card. */
@RunWith(RobolectricTestRunner::class)
class ImageFailureLogTest {
    private var now = 0L
    private val log = ImageFailureLog(clock = { now })

    @Before
    fun clear() = ShadowLog.clear()

    private fun failToLoad(url: String?, why: Throwable = IOException("Connection refused")) {
        val request = ImageRequest.Builder(ApplicationProvider.getApplicationContext()).data(url).build()
        log.onError(request, ErrorResult(null, request, why))
    }

    private fun told() = ShadowLog.getLogsForTag("Images").map { it.msg }

    @Test
    fun aScreenOfFailuresFromOneHostIsToldOnce() {
        repeat(40) { failToLoad("http://192.168.1.5:8096/Items/$it/Images/Primary?api_key=secret") }
        failToLoad("https://image.tmdb.org/t/p/w500/poster.jpg")
        val lines = told()
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], "/Items/0/" in lines[0] && "secret" !in lines[0])
        assertTrue(lines[1], "image.tmdb.org" in lines[1])
        assertTrue(ShadowLog.getLogsForTag("Images").all { it.throwable is IOException })
    }

    @Test
    fun aHostStillFailingIsToldAgainLater() {
        failToLoad("file:///data/user/0/app.houselights/files/artwork/1/a.jpg")
        now += 30_000
        failToLoad("file:///data/user/0/app.houselights/files/artwork/1/b.jpg")
        now += 31_000
        failToLoad("file:///data/user/0/app.houselights/files/artwork/1/c.jpg")
        assertEquals(2, told().size)
    }

    @Test
    fun anImageWithoutDataIsNoFailure() {
        failToLoad(null, NullRequestDataException())
        assertEquals(emptyList<String>(), told())
    }
}
