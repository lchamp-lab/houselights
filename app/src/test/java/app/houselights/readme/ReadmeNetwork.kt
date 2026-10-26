package app.houselights.readme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Scale
import coil.size.Size
import coil.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TMDB and its pictures as the captures read them, over HTTPS: each answer and each picture fetched once and kept in
 * [dir] for the run's other shots. TMDB's answers are kept by their address without the key, which the app's own
 * client adds ([tmdb]); a picture is decoded at the size the screen asks, as Coil would ([loader]).
 */
internal class ReadmeNetwork(private val dir: File) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** TMDB's transport for the app's [app.houselights.providers.local.TmdbClient]: kept answers, else the network. */
    val tmdb: Interceptor = TmdbAnswers(dir.resolve("tmdb"))

    /** Coil for the screens: every picture from TMDB's CDN, at once (no dispatcher), without a crossfade. */
    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .dispatcher(Dispatchers.Unconfined)
        .crossfade(false)
        .components { add(Fetcher.Factory<Any> { data, options, _ -> fetcher(data.toString(), options) }) }
        .build()

    private fun fetcher(address: String, options: Options): Fetcher? {
        if (!address.startsWith("https://")) return null
        return Fetcher {
            val picture = picture(address, options.size, options.scale)
            DrawableResult(BitmapDrawable(options.context.resources, picture), isSampled = true, dataSource = DataSource.NETWORK)
        }
    }

    /** The picture at [address], decoded to cover [size] ([Scale.FILL]) or fit in it, never larger than it is. */
    private fun picture(address: String, size: Size, scale: Scale): Bitmap {
        decode(bytes(address), size, scale)?.let { return it }
        // A logo TMDB was given as SVG is served as PNG too.
        if (address.endsWith(".svg")) decode(bytes(address.removeSuffix(".svg") + ".png"), size, scale)?.let { return it }
        throw IOException("Not a picture: $address")
    }

    private fun decode(bytes: ByteArray, size: Size, scale: Scale): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val factor = factor(width, height, size.width.pxOrElse { 0 }, size.height.pxOrElse { 0 }, scale).coerceAtMost(1.0)
        val wanted = (width * factor).roundToInt().coerceAtLeast(1) to (height * factor).roundToInt().coerceAtLeast(1)
        var sample = 1
        while (width / (sample * 2) >= ceil(width * factor) && height / (sample * 2) >= ceil(height * factor)) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.width == wanted.first && decoded.height == wanted.second) return decoded
        return Bitmap.createScaledBitmap(decoded, wanted.first, wanted.second, true)
    }

    private fun factor(width: Int, height: Int, askedWidth: Int, askedHeight: Int, scale: Scale): Double {
        val across = askedWidth.toDouble() / width
        val down = askedHeight.toDouble() / height
        return when {
            askedWidth > 0 && askedHeight > 0 -> if (scale == Scale.FILL) max(across, down) else min(across, down)
            askedWidth > 0 -> across
            askedHeight > 0 -> down
            else -> 1.0
        }
    }

    /** The bytes at [address]: kept, else fetched and kept, tried three times. */
    private fun bytes(address: String): ByteArray {
        val file = dir.resolve("pictures").resolve(sha256(address))
        if (file.exists()) return file.readBytes()
        var failure: IOException? = null
        repeat(ATTEMPTS) {
            val fetched = try {
                fetch(address)
            } catch (e: IOException) {
                failure = e
                null
            }
            if (fetched != null) {
                file.parentFile?.mkdirs()
                file.writeBytes(fetched)
                return fetched
            }
        }
        throw IOException("$address could not be fetched", failure)
    }

    private fun fetch(address: String): ByteArray = http.newCall(Request.Builder().url(address).build()).execute().use { response ->
        val body = response.body
        if (!response.isSuccessful || body == null) throw IOException("HTTP ${response.code} for $address")
        body.bytes()
    }

    /** TMDB's answers kept in [dir], each under its address without the key; only answers that succeeded. */
    private class TmdbAnswers(private val dir: File) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val file = dir.resolve(sha256(withoutKey(request.url)))
            if (request.method == "GET" && file.exists()) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(OK)
                    .message("Kept")
                    .body(file.readBytes().toResponseBody(JSON))
                    .build()
            }
            val response = chain.proceed(request)
            val body = response.body
            if (request.method != "GET" || !response.isSuccessful || body == null) return response
            val bytes = body.bytes()
            dir.mkdirs()
            file.writeBytes(bytes)
            return response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }

        private fun withoutKey(url: HttpUrl): String = url.newBuilder().removeAllQueryParameters("api_key").build().toString()
    }

    private companion object {
        const val TIMEOUT_S = 60L
        const val ATTEMPTS = 3
        const val OK = 200
        val JSON = "application/json".toMediaType()

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}
