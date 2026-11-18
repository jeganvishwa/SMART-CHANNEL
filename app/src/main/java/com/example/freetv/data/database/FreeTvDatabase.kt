package com.example.freetv.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.freetv.data.dao.AppSettingsDao
import com.example.freetv.data.dao.ChannelDao
import com.example.freetv.data.dao.EpgDao
import com.example.freetv.data.dao.FavoriteDao
import com.example.freetv.data.dao.GroupDao
import com.example.freetv.data.dao.PlaylistDao
import com.example.freetv.data.entity.AppSettingsEntity
import com.example.freetv.data.entity.ChannelEntity
import com.example.freetv.data.entity.ChannelGroupEntity
import com.example.freetv.data.entity.ChannelGroupMemberEntity
import com.example.freetv.data.entity.ChannelStreamEntity
import com.example.freetv.data.entity.EpgChannelEntity
import com.example.freetv.data.entity.EpgProgramEntity
import com.example.freetv.data.entity.FavoriteEntity
import com.example.freetv.data.entity.PlaylistEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ChannelGroupEntity::class,
        ChannelGroupMemberEntity::class,
        ChannelStreamEntity::class,
        FavoriteEntity::class,
        AppSettingsEntity::class,
        EpgProgramEntity::class,
        EpgChannelEntity::class,
    ],
    version = FreeTvDatabase.VERSION,
    exportSchema = true,
)
abstract class FreeTvDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun groupDao(): GroupDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun epgDao(): EpgDao

    companion object {
        const val VERSION = 1
        const val NAME = "freetv.db"
    }
}

/**
 * Schema migrations. Every future schema change must bump [FreeTvDatabase.VERSION]
 * and add a Migration here (the exported schemas in app/schemas let
 * MigrationTestHelper verify them). Destructive migration is never used,
 * because the database holds the user's numbers, groups and favourites.
 */
object DatabaseMigrations {
    val ALL: Array<Migration> = arrayOf()
}

/** Seeds the default language and category groups on first creation. */
object DefaultGroupsCallback : RoomDatabase.Callback() {

    /** name, type, keywords */
    val DEFAULT_GROUPS = listOf(
        Triple("Tamil", "LANGUAGE", "tamil,தமிழ்,tam"),
        Triple("English", "LANGUAGE", "english,eng"),
        Triple("Hindi", "LANGUAGE", "hindi,हिन्दी,हिंदी,hin"),
        Triple("Malayalam", "LANGUAGE", "malayalam,മലയാളം,mal"),
        Triple("News", "CATEGORY", "news,khabar,seithi,செய்தி"),
        Triple("Movies", "CATEGORY", "movie,movies,cinema,film,films"),
        Triple("Sports", "CATEGORY", "sport,sports,cricket,football"),
        Triple("Kids", "CATEGORY", "kids,kid,children,cartoon,animation"),
        Triple("Entertainment", "CATEGORY", "entertainment,general,ent"),
        Triple("Music", "CATEGORY", "music,songs,hits"),
    )

    override fun onCreate(db: SupportSQLiteDatabase) {
        DEFAULT_GROUPS.forEachIndexed { index, (name, type, keywords) ->
            db.execSQL(
                "INSERT INTO channel_groups (name, type, position, showOnHome, hidden, keywords) VALUES (?, ?, ?, 1, 0, ?)",
                arrayOf<Any?>(name, type, index, keywords),
            )
        }
    }
}
