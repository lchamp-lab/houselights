package app.houselights.ui.testing

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.size.Size
import coil.size.pxOrElse

/**
 * A server for Coil whose every picture comes back at the size asked, as a server's decoded to it would be
 * ([OPEN_PX] a side where the size is open), once [answer] has returned for its address and size.
 */
fun pictureServer(answer: suspend (data: Any, size: Size) -> Unit = { _, _ -> }) = Fetcher.Factory<Any> { data, options, _ ->
    Fetcher {
        answer(data, options.size)
        val (width, height) = options.size.let { it.width.pxOrElse { OPEN_PX } to it.height.pxOrElse { OPEN_PX } }
        val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        DrawableResult(BitmapDrawable(options.context.resources, picture), isSampled = false, dataSource = DataSource.NETWORK)
    }
}

private const val OPEN_PX = 4
