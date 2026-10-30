package com.folio.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        DocumentEntity::class, FolderEntity::class, CollectionEntity::class, BookmarkEntity::class,
        TagEntity::class, DocumentTagRef::class, BookmarkTagRef::class, DocumentCollectionRef::class,
        NoteEntity::class, ReadingProgressEntity::class, ReadingHistoryEntity::class,
        AnnotationEntity::class, BrowserHistoryEntity::class, DownloadEntity::class, SearchIndexEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun folders(): FolderDao
    abstract fun collections(): CollectionDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun tags(): TagDao
    abstract fun notes(): NoteDao
    abstract fun reading(): ReadingDao
    abstract fun annotations(): AnnotationDao
    abstract fun browser(): BrowserDao
    abstract fun search(): SearchDao

    /** Runs SQLite's own consistency checks. Returns "ok" when healthy. */
    fun integrityCheck(): String {
        val db = openHelper.writableDatabase
        val sb = StringBuilder()
        db.query("PRAGMA integrity_check").use { c -> while (c.moveToNext()) sb.appendLine(c.getString(0)) }
        db.query("PRAGMA foreign_key_check").use { c ->
            if (c.count > 0) sb.appendLine("foreign key violations: ${c.count}")
        }
        return sb.toString().trim()
    }

    /** Folds the WAL into the main file so a copy of it is complete. */
    fun checkpoint() {
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    companion object {
        const val NAME = "folio.db"

        /**
         * Schema migrations. Every future schema change must add a Migration here;
         * destructive fallback is deliberately never enabled, so user data is
         * never silently wiped by an upgrade.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()
    }
}
