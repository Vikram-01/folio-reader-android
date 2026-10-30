package com.folio.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "documents",
    indices = [Index("sha256"), Index("folderId"), Index("lastOpenedAt"), Index("addedAt")],
    foreignKeys = [ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL)],
)
@Serializable
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val originalName: String,
    /** Path relative to the app's private library folder. */
    val storedName: String,
    val format: String,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
    val sourceUrl: String? = null,
    val folderId: Long? = null,
    val isFavorite: Boolean = false,
    val addedAt: Long,
    val lastOpenedAt: Long? = null,
    val pageCount: Int? = null,
    val author: String? = null,
    val thumbnailName: String? = null,
    val indexedAt: Long? = null,
    val indexError: String? = null,
)

@Entity(
    tableName = "folders",
    indices = [Index("parentId")],
    foreignKeys = [ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val createdAt: Long,
)

/** Collections hold bookmarks and (via cross-ref) documents; they can be nested. */
@Entity(
    tableName = "collections",
    indices = [Index("parentId")],
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val color: Long = 0xFFE07A4F,
    val createdAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("normalizedUrl"), Index("collectionId"), Index("createdAt")],
    foreignKeys = [ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.SET_NULL)],
)
@Serializable
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val normalizedUrl: String,
    val title: String,
    val note: String = "",
    val type: String = BookmarkType.LINK.name,
    val collectionId: Long? = null,
    val isFavorite: Boolean = false,
    val faviconName: String? = null,
    val thumbnailName: String? = null,
    /** Sanitised article HTML saved for offline reading. */
    val offlineName: String? = null,
    val offlineSavedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class BookmarkType(val label: String) { LINK("Link"), ARTICLE("Article"), VIDEO("Video"), IMAGE("Image"), PDF("PDF"), PAGE("Page") }

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
@Serializable
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
@Serializable
data class DocumentTagRef(val documentId: Long, val tagId: Long)

@Entity(
    tableName = "bookmark_tags",
    primaryKeys = ["bookmarkId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(entity = BookmarkEntity::class, parentColumns = ["id"], childColumns = ["bookmarkId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
)
@Serializable
data class BookmarkTagRef(val bookmarkId: Long, val tagId: Long)

@Entity(
    tableName = "document_collections",
    primaryKeys = ["documentId", "collectionId"],
    indices = [Index("collectionId")],
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CollectionEntity::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
)
@Serializable
data class DocumentCollectionRef(val documentId: Long, val collectionId: Long)

@Entity(
    tableName = "notes",
    indices = [Index("documentId"), Index("bookmarkId")],
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BookmarkEntity::class, parentColumns = ["id"], childColumns = ["bookmarkId"], onDelete = ForeignKey.CASCADE),
    ],
)
@Serializable
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long? = null,
    val bookmarkId: Long? = null,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class ReadingProgressEntity(
    @PrimaryKey val documentId: Long,
    /** Page (PDF), chapter (EPUB) or line/row index (text, table). */
    val position: Int,
    /** Scroll offset within the position, 0..1. */
    val offset: Float = 0f,
    /** Overall completion, 0..1. */
    val fraction: Float = 0f,
    val updatedAt: Long,
)

@Entity(
    tableName = "reading_history",
    indices = [Index("documentId"), Index("openedAt")],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
)
data class ReadingHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val openedAt: Long,
)

/** In-document bookmarks and notes, anchored to a page / chapter / line. */
@Entity(
    tableName = "annotations",
    indices = [Index("documentId")],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
)
@Serializable
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val kind: String,
    val position: Int,
    val label: String,
    val quote: String = "",
    val note: String = "",
    val color: Long = 0xFFFFD54F,
    val createdAt: Long,
)

enum class AnnotationKind { BOOKMARK, NOTE, HIGHLIGHT }

@Entity(tableName = "browser_history", indices = [Index("visitedAt"), Index("url")])
data class BrowserHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

@Entity(
    tableName = "downloads",
    indices = [Index("createdAt"), Index("documentId")],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.SET_NULL)],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mime: String?,
    val sizeBytes: Long = 0,
    val status: String,
    val error: String? = null,
    val documentId: Long? = null,
    val createdAt: Long,
)

enum class DownloadStatus { RUNNING, DONE, FAILED, BLOCKED }

/**
 * Full-text index. ownerType = "doc" | "bookmark". location is the 0-based
 * page / chapter / sheet / row block, locationLabel is what the UI shows.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["ownerType", "ownerId", "location", "locationLabel"])
@Entity(tableName = "search_index")
data class SearchIndexEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int = 0,
    val ownerType: String,
    val ownerId: String,
    val location: String,
    val locationLabel: String,
    val content: String,
)
