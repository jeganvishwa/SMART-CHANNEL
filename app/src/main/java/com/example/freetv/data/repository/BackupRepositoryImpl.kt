package com.example.freetv.data.repository

import android.content.Context
import android.net.Uri
import coil3.SingletonImageLoader
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.di.IoDispatcher
import com.example.freetv.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Database backup / restore. The backup is the SQLite file itself (after a WAL
 * checkpoint), so it contains playlists, channels, numbers, groups, streams,
 * favourites and the EPG. After a restore the app must restart.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FreeTvDatabase,
    private val okHttpClient: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupRepository {

    private val dbFile: File get() = context.getDatabasePath(FreeTvDatabase.NAME)

    override fun quickBackupFile(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "freetv-backup.db")

    private fun checkpoint() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    override suspend fun backupTo(uri: Uri): Result<Unit> = withContext(io) {
        runCatching {
            checkpoint()
            val out = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to the selected file")
            out.use { o -> dbFile.inputStream().use { it.copyTo(o) } }
            Unit
        }
    }

    override suspend fun quickBackup(): Result<File> = withContext(io) {
        runCatching {
            checkpoint()
            val target = quickBackupFile()
            target.parentFile?.mkdirs()
            dbFile.copyTo(target, overwrite = true)
            target
        }
    }

    override suspend fun restoreFrom(uri: Uri): Result<Unit> = withContext(io) {
        runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read the selected file")
            restore(input)
        }
    }

    override suspend fun quickRestore(): Result<Unit> = withContext(io) {
        runCatching {
            val file = quickBackupFile()
            if (!file.exists()) throw IOException("No backup found at ${file.path}")
            restore(file.inputStream())
        }
    }

    private fun restore(input: InputStream) {
        val staged = File(context.cacheDir, "restore.db")
        input.use { inp -> staged.outputStream().use { inp.copyTo(it) } }
        val header = staged.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") {
            staged.delete()
            throw IOException("This file is not a FreeTV backup.")
        }
        db.close()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        staged.copyTo(dbFile, overwrite = true)
        staged.delete()
    }

    override suspend fun clearCache(): Long = withContext(io) {
        val before = dirSize(context.cacheDir)
        runCatching { okHttpClient.cache?.evictAll() }
        runCatching {
            val loader = SingletonImageLoader.get(context)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name != "http_cache" && f.name != "image_cache") f.deleteRecursively()
        }
        (before - dirSize(context.cacheDir)).coerceAtLeast(0)
    }

    private fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
