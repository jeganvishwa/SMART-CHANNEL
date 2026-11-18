package com.example.freetv.data.network

import com.example.freetv.di.IoDispatcher
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext

class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Downloads playlists / EPG files to disk with progress, timeout and retries. */
@Singleton
class Downloader @Inject constructor(
    okHttpClient: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val client = okHttpClient.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * Downloads [url] into [target]. [onProgress] receives (bytesRead, totalBytes or -1).
     * Retries transient failures up to [retries] times.
     */
    suspend fun download(
        url: String,
        target: File,
        retries: Int = 2,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(io) {
        if (!UrlSanitizer.isValidRemoteUrl(url)) throw DownloadException("Invalid URL. Use a full http:// or https:// address.")
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= retries) {
            try {
                return@withContext downloadOnce(url.trim(), target, onProgress)
            } catch (e: DownloadException) {
                throw e // non-transient (HTTP status, invalid URL)
            } catch (e: IOException) {
                lastError = e
                attempt++
                if (attempt <= retries) delay(1_500L * attempt)
            }
        }
        throw DownloadException(friendlyMessage(lastError), lastError)
    }

    private suspend fun downloadOnce(url: String, target: File, onProgress: (Long, Long) -> Unit): File {
        val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadException(
                    when (response.code) {
                        401, 403 -> "Access denied by the server (HTTP ${response.code}). Check the playlist link or your subscription."
                        404 -> "Playlist not found on the server (HTTP 404)."
                        else -> "Server error (HTTP ${response.code})."
                    },
                )
            }
            val body = response.body
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            body.byteStream().use { input ->
                tmp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastReport = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 128 * 1024) {
                            onProgress(done, total)
                            lastReport = done
                        }
                    }
                    onProgress(done, total)
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return target
        }
    }

    companion object {
        fun friendlyMessage(e: Throwable?): String = when (e) {
            is UnknownHostException -> "Cannot reach the server. Check the URL and your internet connection."
            is SocketTimeoutException -> "The server took too long to respond (timeout)."
            null -> "Download failed."
            else -> e.message?.takeIf { it.isNotBlank() } ?: "Download failed."
        }
    }
}
