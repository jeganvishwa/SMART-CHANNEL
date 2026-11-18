package com.example.freetv.data.network

import com.example.freetv.di.IoDispatcher
import com.example.freetv.domain.model.StreamTestResult
import com.example.freetv.utils.UrlSanitizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quick reachability check of a stream URL: requests the first bytes and,
 * for HLS, verifies the playlist header. It does not attempt to get around
 * any authentication or DRM – a 401/403 is simply reported.
 */
@Singleton
class StreamTester @Inject constructor(
    okHttpClient: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun test(url: String, userAgent: String?, referrer: String?): StreamTestResult = withContext(io) {
        if (!UrlSanitizer.isValidRemoteUrl(url)) return@withContext StreamTestResult(false, "Invalid URL")
        val builder = Request.Builder()
            .url(url.trim())
            .header("Range", "bytes=0-2047")
            .cacheControl(CacheControl.FORCE_NETWORK)
        userAgent?.let { builder.header("User-Agent", it) }
        referrer?.let { builder.header("Referer", it) }
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext StreamTestResult(false, "HTTP ${response.code}")
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val head = response.body.source().let { source ->
                    source.request(512)
                    source.buffer.snapshot().utf8().take(512)
                }
                val isHls = url.contains(".m3u8", true) || contentType.contains("mpegurl")
                if (isHls && !head.trimStart().startsWith("#EXTM3U")) {
                    StreamTestResult(false, "Not a valid HLS playlist")
                } else {
                    StreamTestResult(true, "OK (${contentType.ifEmpty { "stream" }})")
                }
            }
        } catch (e: Exception) {
            StreamTestResult(false, Downloader.friendlyMessage(e))
        }
    }
}
