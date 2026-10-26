package app.houselights.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Where an offer's APK is downloaded and checked against its manifest ([ApkDownload]). */
interface Downloads {
    /** [offer]'s APK, verified against its size and SHA-256; [progress] told at most once per hundredth. */
    suspend fun fetch(offer: UpdateOffer, progress: (read: Long, total: Long) -> Unit): File

    /** Forgets every download: at each start. */
    fun clear()
}

/**
 * Downloads a release's APK into the app's cache ([dir], cleared at each start), checking it as it comes: no more bytes
 * than its manifest says, all of them, and its SHA-256. Written to a .part file renamed once whole; a download cut,
 * cancelled or refused leaves no file. Never resumed: the file is small, and GitHub's download addresses expire.
 */
class ApkDownload(
    private val dir: File,
    private val http: OkHttpClient,
    /** The room left where [dir] is. */
    private val space: (File) -> Long = File::getUsableSpace,
    private val clock: () -> Long = System::currentTimeMillis,
) : Downloads {
    override suspend fun fetch(offer: UpdateOffer, progress: (read: Long, total: Long) -> Unit): File {
        val apk = File(dir, ReleaseChoice.apkName(offer.versionName))
        val part = File(dir, apk.name + PART)
        val free = withContext(Dispatchers.IO) {
            dir.mkdirs()
            apk.delete()
            part.delete()
            space(dir)
        }
        if (free < offer.spaceNeeded) fail(UpdateFailure.STORAGE, "$free bytes free, ${offer.spaceNeeded} needed")
        var whole = false
        try {
            val call = http.newCall(Request.Builder().url(offer.url).build())
            call.await().use { response -> opened(response, offer).byteStream().use { copy(call, it, part, offer, progress) } }
            whole = withContext(Dispatchers.IO) { part.renameTo(apk) }
            if (!whole) throw IOException("${part.name} could not be renamed")
            return apk
        } finally {
            if (!whole) withContext(NonCancellable + Dispatchers.IO) { part.delete() }
        }
    }

    /** The body of GitHub's answer, refused unless it serves the file the manifest lists. */
    private fun opened(response: Response, offer: UpdateOffer): ResponseBody {
        if (!response.isSuccessful) throw statusFailure(response, clock())
        val body = response.body ?: fail(UpdateFailure.DOWNLOAD_CUT, "No body")
        // A missing length (chunked, or compressed on its way) is allowed: the bytes counted and the hash decide.
        val length = body.contentLength()
        if (length >= 0 && length != offer.size) fail(UpdateFailure.CORRUPT, "$length bytes served, ${offer.size} listed")
        return body
    }

    /** Copies [input] into [part], counting and hashing it; cancelling stops a read that waits, through [call]. */
    private suspend fun copy(call: Call, input: InputStream, part: File, offer: UpdateOffer, progress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            // A read blocked on a stalled connection ends when the call is cancelled, not when the coroutine is.
            val stop = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK)
            var read = 0L
            var told = -1L
            part.outputStream().use { out ->
                while (true) {
                    ensureActive()
                    val n = read(input, buffer, read)
                    if (n < 0) break
                    read += n
                    if (read > offer.size) fail(UpdateFailure.CORRUPT, "More than ${offer.size} bytes")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    val hundredth = read * HUNDRED / offer.size
                    if (hundredth != told) {
                        told = hundredth
                        progress(read, offer.size)
                    }
                }
            }
            stop.cancel()
            if (read < offer.size) fail(UpdateFailure.DOWNLOAD_CUT, "$read of ${offer.size} bytes")
            val sha256 = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            if (sha256 != offer.sha256) fail(UpdateFailure.CORRUPT, "SHA-256 $sha256, listed ${offer.sha256}")
        }

    /** The next bytes of [input], a connection lost or stalled after [read] bytes being a download cut. */
    private fun read(input: InputStream, buffer: ByteArray, read: Long): Int = try {
        input.read(buffer)
    } catch (e: IOException) {
        fail(UpdateFailure.DOWNLOAD_CUT, "Cut after $read bytes", e)
    }

    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val PART = ".part"
        const val CHUNK = 64 * 1024
        const val HUNDRED = 100L
    }
}
