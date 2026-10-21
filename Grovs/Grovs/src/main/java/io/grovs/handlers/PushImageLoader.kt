package io.grovs.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.grovs.model.DebugLogger
import io.grovs.model.LogLevel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Same limits as the backend and FCM: https only, 5 seconds, 1 MB. Runs on the caller's thread. */
internal object PushImageLoader {
    const val TIMEOUT_MS = 5_000L
    const val MAX_BYTES = 1_048_576L
    /** Longest side of the decoded picture: enough for a notification, far below the binder limit. */
    const val MAX_SIDE_PX = 1_024

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followSslRedirects(false)
            .build()
    }

    fun load(url: String?): Bitmap? =
        if (url != null && url.startsWith("https://")) download(url, client) else null

    fun download(url: String, client: OkHttpClient, maxBytes: Long = MAX_BYTES): Bitmap? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val source = response.body?.source()
            when {
                !response.isSuccessful || source == null -> null
                source.request(maxBytes + 1) -> null
                else -> decodeScaled(source.buffer.readByteArray())
            }
        }
    } catch (e: Exception) {
        DebugLogger.instance.log(LogLevel.INFO, "Push image not loaded: ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        DebugLogger.instance.log(LogLevel.INFO, "Push image too large to decode")
        null
    }

    fun decodeScaled(data: ByteArray, maxSide: Int = MAX_SIDE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxSide) sampleSize *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }
}
