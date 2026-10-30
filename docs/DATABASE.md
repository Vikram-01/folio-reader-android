# Database

Room (SQLite) database `folio.db`, schema version 1, WAL journal mode. Exported schema JSON lives in `app/schemas/` once the project is built.

## Tables

| Table | Purpose | Key columns |
|---|---|---|
| `documents` | Library items | `storedName` (file in private `files/library/`), `format`, `sha256` (duplicate detection), `sourceUrl`, `folderId`→folders (SET NULL), `isFavorite`, `addedAt`, `lastOpenedAt`, `pageCount`, `thumbnailName`, `indexedAt`, `indexError` |
| `folders` | Nested folders for documents | `parentId`→folders (CASCADE) |
| `collections` | Nested collections for bookmarks and documents | `parentId`→collections (CASCADE), `color` |
| `bookmarks` | Saved links | `url`, `normalizedUrl` (duplicate detection), `type` (LINK/ARTICLE/VIDEO/IMAGE/PDF/PAGE), `note`, `collectionId`→collections (SET NULL), `faviconName`, `thumbnailName`, `offlineName` (sanitised saved page) |
| `tags` | Shared tag names | unique `name` |
| `document_tags`, `bookmark_tags` | Tag links | composite PK, CASCADE on both sides |
| `document_collections` | Documents in collections (many-to-many) | composite PK, CASCADE |
| `notes` | Free-form notes on a document or bookmark | `documentId` / `bookmarkId` (CASCADE) |
| `reading_progress` | Last position per document | PK `documentId`; `position` (page/chapter/line/row), `offset` (0..1 within position), `fraction` (overall 0..1) |
| `reading_history` | Every open | `documentId`, `openedAt` |
| `annotations` | In-document bookmarks and notes | `documentId`, `kind`, `position`, `label`, `quote`, `note` |
| `browser_history` | Visited pages (optional) | `url`, `title`, `visitedAt` |
| `downloads` | Browser downloads | `url`, `fileName`, `status` (RUNNING/DONE/FAILED/BLOCKED), `documentId` |
| `search_index` | FTS4 virtual table (unicode61 tokenizer) | `ownerType` (`doc`/`bookmark`), `ownerId`, `location`, `locationLabel` (all not indexed), `content` (indexed) |

User preferences are stored with Jetpack DataStore (typed, transactional) rather than a Room table.

## Integrity

- Foreign keys enforce cascades; deleting a folder or collection never deletes documents or bookmarks.
- **Migrations:** `AppDatabase.MIGRATIONS` holds explicit migrations. Destructive fallback is never enabled, so an upgrade can't silently wipe data.
- Settings → Check data integrity runs `PRAGMA integrity_check`, `PRAGMA foreign_key_check`, and verifies every library file exists.

## Backup format

`.folio` file = ZIP of `manifest.json` (all rows above except history and index) + `library/`, `thumbs/`, `pages/`, `favicons/`. With a password, the ZIP is wrapped in chunked AES-256-GCM (PBKDF2-HMAC-SHA256, 210,000 iterations, random salt; per-chunk nonce and final-chunk flag in the AAD so chunks can't be reordered or truncated). Restore merges by SHA-256 / normalised URL and re-indexes.
