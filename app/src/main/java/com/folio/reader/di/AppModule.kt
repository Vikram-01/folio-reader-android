package com.folio.reader.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.folio.reader.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun documentDao(db: AppDatabase) = db.documents()
    @Provides fun folderDao(db: AppDatabase) = db.folders()
    @Provides fun collectionDao(db: AppDatabase) = db.collections()
    @Provides fun bookmarkDao(db: AppDatabase) = db.bookmarks()
    @Provides fun tagDao(db: AppDatabase) = db.tags()
    @Provides fun noteDao(db: AppDatabase) = db.notes()
    @Provides fun readingDao(db: AppDatabase) = db.reading()
    @Provides fun annotationDao(db: AppDatabase) = db.annotations()
    @Provides fun browserDao(db: AppDatabase) = db.browser()
    @Provides fun searchDao(db: AppDatabase) = db.search()

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
