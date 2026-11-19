package com.example.freetv.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a real version-1 database from the exported schema (app/schemas/.../1.json),
 * then opens it with the current Room database and MIGRATION_1_2.
 * Room validates the migrated schema against the v2 entities on open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun createVersion1(file: File) {
        val json = JSONObject(File("schemas/com.example.freetv.data.database.FreeTvDatabase/1.json").readText())
            .getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = json.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = json.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.execSQL(
            "INSERT INTO playlists (name, url, sourceType, importedAt, enabled, channelCount, status, autoRefresh) " +
                "VALUES ('My list', 'https://example.com/a.m3u', 'URL', 1, 1, 42, 'OK', 1)",
        )
        db.version = 1
        db.close()
    }

    @Test
    fun migrate1To2KeepsPlaylistsAndAddsLanguageColumns() = runBlocking {
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
        createVersion1(file)

        val db = Room.databaseBuilder(context, FreeTvDatabase::class.java, file.absolutePath)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        val playlists = db.playlistDao().getAll()
        db.close()

        assertEquals(1, playlists.size)
        assertEquals("My list", playlists[0].name)
        assertEquals(42, playlists[0].channelCount)
        assertNull(playlists[0].languageCode)
        assertNull(playlists[0].folder)
    }
}
