package com.example.freetv.di

import android.content.Context
import com.example.freetv.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val HTTP_CACHE_SIZE_BYTES = 50L * 1024 * 1024 // logos + playlists
    const val USER_AGENT = "FreeTV/${BuildConfig.VERSION_NAME} (Android TV)"

    /**
     * One shared client for playlist downloads, EPG downloads, logos (Coil)
     * and stream playback (Media3 OkHttp data source).
     *
     * No HTTP body logging is installed on purpose: playlist URLs often embed
     * usernames, passwords or tokens, which must never be written to logcat.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http_cache"), HTTP_CACHE_SIZE_BYTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.header("User-Agent") != null) {
                    chain.proceed(request)
                } else {
                    chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
                }
            }
            .build()
}
