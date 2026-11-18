package com.example.freetv.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.example.freetv.data.dao.AppSettingsDao
import com.example.freetv.data.dao.ChannelDao
import com.example.freetv.data.dao.EpgDao
import com.example.freetv.data.dao.FavoriteDao
import com.example.freetv.data.dao.GroupDao
import com.example.freetv.data.dao.PlaylistDao
import com.example.freetv.data.database.DatabaseMigrations
import com.example.freetv.data.database.DefaultGroupsCallback
import com.example.freetv.data.database.FreeTvDatabase
import com.example.freetv.data.repository.BackupRepositoryImpl
import com.example.freetv.data.repository.ChannelRepositoryImpl
import com.example.freetv.data.repository.EpgRepositoryImpl
import com.example.freetv.data.repository.GroupRepositoryImpl
import com.example.freetv.data.repository.PlaylistRepositoryImpl
import com.example.freetv.domain.repository.BackupRepository
import com.example.freetv.domain.repository.ChannelRepository
import com.example.freetv.domain.repository.EpgRepository
import com.example.freetv.domain.repository.GroupRepository
import com.example.freetv.domain.repository.PlaylistRepository
import com.example.freetv.domain.repository.SettingsRepository
import com.example.freetv.preferences.SettingsDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FreeTvDatabase =
        Room.databaseBuilder(context, FreeTvDatabase::class.java, FreeTvDatabase.NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .addCallback(DefaultGroupsCallback)
            .build()

    @Provides fun providePlaylistDao(db: FreeTvDatabase): PlaylistDao = db.playlistDao()
    @Provides fun provideChannelDao(db: FreeTvDatabase): ChannelDao = db.channelDao()
    @Provides fun provideGroupDao(db: FreeTvDatabase): GroupDao = db.groupDao()
    @Provides fun provideFavoriteDao(db: FreeTvDatabase): FavoriteDao = db.favoriteDao()
    @Provides fun provideAppSettingsDao(db: FreeTvDatabase): AppSettingsDao = db.appSettingsDao()
    @Provides fun provideEpgDao(db: FreeTvDatabase): EpgDao = db.epgDao()

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile("freetv_settings")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun bindPlaylistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository
    @Binds @Singleton abstract fun bindChannelRepository(impl: ChannelRepositoryImpl): ChannelRepository
    @Binds @Singleton abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository
    @Binds @Singleton abstract fun bindEpgRepository(impl: EpgRepositoryImpl): EpgRepository
    @Binds @Singleton abstract fun bindSettingsRepository(impl: SettingsDataStore): SettingsRepository
    @Binds @Singleton abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository
}
