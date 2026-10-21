package io.grovs.inapp

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.util.AtomicFile
import com.google.gson.JsonElement
import io.grovs.inapp.model.InAppJson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Which campaigns have their images on the device; a campaign shows only once all of them are.
 * What is stored is kept in memory, so deciding what to show never touches the disk.
 */
internal class InAppImages(
    private val folder: File,
    private val loader: (url: String, done: (ByteArray?) -> Unit) -> Unit,
    private val decodes: (ByteArray) -> Boolean,
    private val background: Executor = deletions,
) {
    private val loading = mutableSetOf<String>()
    /** Called on the loading thread after a picture is stored. */
    @Volatile
    var onStored: () -> Unit = {}
    /** File names in [folder], read from it on first use. Guarded by itself. */
    private val stored: MutableSet<String> by lazy {
        folder.listFiles()?.mapTo(mutableSetOf()) { it.name } ?: mutableSetOf()
    }

    /** Starts loading what is missing. Failures are retried by the next call, after the next sync. */
    fun preload(campaigns: List<JsonElement>) {
        for (url in campaigns.flatMap(::images).distinct()) {
            if (has(url)) continue
            val started = synchronized(loading) { loading.add(url) }
            if (!started) continue
            loader(url) { data ->
                // Decoded once here, so a file that is not an image never counts as ready.
                val kept = data != null && data.size <= MAX_BYTES && decodes(data) && store(url, data)
                synchronized(loading) { loading.remove(url) }
                if (kept) onStored()
            }
        }
    }

    /** Reads the folder now, so the first check on the main thread does not have to. */
    fun warmUp() {
        synchronized(stored) {}
    }

    fun has(url: String): Boolean = name(url).let { name -> synchronized(stored) { name in stored } }

    fun ready(campaign: JsonElement): Boolean = images(campaign).all(::has)

    /** The stored picture for [url], for the presenter to decode; null when it is not on disk. */
    fun file(url: String?): File? = url?.takeIf(::has)?.let(::path)

    /** Removes pictures no stored campaign names any more. */
    fun keepOnly(campaigns: List<JsonElement>) {
        val wanted = campaigns.flatMap(::images).map(::name).toSet()
        synchronized(stored) { stored.retainAll(wanted) }
        try {
            background.execute {
                // A picture stored after this call started is kept.
                val unknown = synchronized(stored) { folder.listFiles()?.filter { it.name !in stored } }
                unknown?.forEach { it.delete() }
            }
        } catch (e: RejectedExecutionException) {
            InAppLog.error("Could not remove old in-app pictures")
        }
    }

    private fun name(url: String) = InAppHash.sha256Hex(url)

    private fun path(url: String) = File(folder, name(url))

    private fun store(url: String, data: ByteArray): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) return false
        val file = AtomicFile(path(url))
        val stream = try {
            file.startWrite()
        } catch (e: IOException) {
            return false
        }
        return try {
            stream.write(data)
            file.finishWrite(stream)
            synchronized(stored) { stored.add(name(url)) }
            true
        } catch (e: IOException) {
            file.failWrite(stream)
            false
        }
    }

    companion object {
        /** Larger than any picture a message needs; a bigger file is not kept. */
        const val MAX_BYTES = 5 * 1024 * 1024

        private val deletions: Executor by lazy {
            Executors.newSingleThreadExecutor { work -> Thread(work, "grovs-in-app-images").apply { isDaemon = true } }
        }

        /**
         * Not the SDK's client: no project or device header may reach an image host.
         * A redirect that changes the scheme is not followed, so an https image never ends up on http.
         */
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followSslRedirects(false).build()
        }

        fun images(campaign: JsonElement): List<String> {
            val raw = campaign.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            // An html page's leftover native images never gate it.
            if (InAppJson.string(raw.get("template")) == "html") return emptyList()
            val image = raw.get("content")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("image")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            return listOf("portrait", "landscape")
                .mapNotNull { InAppJson.string(image.get(it)) }
                .filter { it.startsWith("https://") }
        }

        fun forDevice(context: Context, key: String): InAppImages = InAppImages(
            folder = File(context.cacheDir, "grovs-in-app/${InAppHash.short(key)}"),
            loader = ::download,
            decodes = { data ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0
            },
        )

        fun download(url: String, done: (ByteArray?) -> Unit) {
            val request = try {
                Request.Builder().url(url).build()
            } catch (e: IllegalArgumentException) {
                done(null)
                return
            }
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = done(null)

                override fun onResponse(call: Call, response: Response) {
                    val data = try {
                        response.use(::readCapped)
                    } catch (e: IOException) {
                        null
                    }
                    done(data)
                }
            })
        }

        /** Reads at most [MAX_BYTES]; a larger or failed body gives null. */
        fun readCapped(response: Response): ByteArray? {
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val source = body.source()
            if (source.request(MAX_BYTES + 1L)) return null
            return source.buffer.readByteArray()
        }
    }
}
