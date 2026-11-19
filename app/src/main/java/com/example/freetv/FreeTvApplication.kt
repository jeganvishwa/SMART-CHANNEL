package com.example.freetv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.example.freetv.data.repository.LanguagePlaylistInitializer
import com.example.freetv.worker.RefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Application entry point.
 *
 * - [HiltAndroidApp] generates the application-level Hilt component.
 * - [Configuration.Provider] lets WorkManager build workers through Hilt
 *   (used for automatic playlist and EPG refresh).
 * - [SingletonImageLoader.Factory] makes Coil load channel logos with the same
 *   OkHttp client (shared connection pool, timeouts, user agent).
 */
@HiltAndroidApp
class FreeTvApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var okHttpClient: dagger.Lazy<OkHttpClient>

    @Inject
    lateinit var refreshScheduler: RefreshScheduler

    @Inject
    lateinit var languagePlaylists: LanguagePlaylistInitializer

    override fun onCreate() {
        super.onCreate() // Hilt injects the fields here
        refreshScheduler.start()
        // First launch only: add Tamil / English / Hindi / Malayalam playlists (background, no UI launch).
        languagePlaylists.initializeOnFirstLaunch()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() }))
            }
            .crossfade(true)
            .build()
}
