package com.example.freetv.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.freetv.di.ApplicationScope
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background refresh of auto-refresh playlists and the XMLTV guide. */
@HiltWorker
class RefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        val maxAge = TimeUnit.HOURS.toMillis(s.refreshIntervalHours.toLong()) - TimeUnit.MINUTES.toMillis(10)
        return try {
            if (s.autoRefreshEnabled) {
                val count = playlists.refreshDue(maxAge)
                Log.i(TAG, "Auto-refreshed $count playlist(s)")
            }
            val epgUrl = s.epgUrl
            if (s.epgAutoRefresh && !epgUrl.isNullOrBlank()) {
                val last = epg.observeLastRefresh().first() ?: 0L
                if (System.currentTimeMillis() - last >= maxAge) epg.refresh(epgUrl, s.epgOffsetMinutes)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background refresh failed", e)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "RefreshWorker"
        const val UNIQUE_NAME = "freetv_refresh"
    }
}

/** Keeps the periodic refresh job in sync with the settings. */
@Singleton
class RefreshScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            settings.settings
                .map { Triple(it.autoRefreshEnabled || it.epgAutoRefresh, it.refreshIntervalHours, it.epgUrl) }
                .distinctUntilChanged()
                .collect { (enabled, hours, _) -> apply(enabled, hours) }
        }
    }

    private fun apply(enabled: Boolean, hours: Int) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(RefreshWorker.UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(RefreshWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
