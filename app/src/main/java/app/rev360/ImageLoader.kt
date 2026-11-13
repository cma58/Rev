package app.rev360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a picture off the camera and decodes it small enough to show.
 *
 * A Gear 360 still is a dual fisheye of roughly 7776x3888. Decoding one at
 * full size costs over 100 MB and kills the app, so measure first and let
 * BitmapFactory subsample on the way in.
 */
object ImageLoader {

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(url: String, maxPx: Int) = "$url@$maxPx"

    fun cached(url: String, maxPx: Int): Bitmap? = cache.get(key(url, maxPx))

    /** Blocking; call from a background thread. */
    fun load(network: Network?, url: String, maxPx: Int): Bitmap? {
        cached(url, maxPx)?.let { return it }
        val bytes = fetch(network, url) ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        cache.put(key(url, maxPx), bitmap)
        return bitmap
    }

    /** Halve until the longest edge fits; powers of two decode fastest. */
    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxPx) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun fetch(network: Network?, url: String): ByteArray? = try {
        val target = URL(url)
        val connection = (network?.openConnection(target) ?: target.openConnection())
                as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 30000
        val bytes = if (connection.responseCode in 200..299) {
            connection.inputStream.use { it.readBytes() }
        } else {
            null
        }
        connection.disconnect()
        bytes
    } catch (e: Exception) {
        null
    }
}
