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
                else -> source.buffer.readByteArray().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }
    } catch (e: Exception) {
        DebugLogger.instance.log(LogLevel.INFO, "Push image not loaded: ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        DebugLogger.instance.log(LogLevel.INFO, "Push image too large to decode")
        null
    }
}
