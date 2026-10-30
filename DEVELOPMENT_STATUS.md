# Development status

Last updated: 2026-10-30

| # | Milestone | Status | Notes |
|---|---|---|---|
| 1 | Architecture & database | Done | Hilt, Room (15 tables + FTS4), DataStore, WorkManager |
| 2 | Document import & storage | Done | SAF import, share/open-with, signature detection, SHA-256 duplicates, private storage, folders, tags, collections, export |
| 3 | PDF & Office readers | PDF done; Office not started | Office formats are stored and exportable only |
| 4 | Ebook readers | EPUB done; MOBI/AZW3/FB2/DjVu not started | |
| 5 | Bookmark management | Done | Share sheet, collections, tags, notes, favorites, bulk actions, HTML import/export, duplicate detection |
| 6 | In-app browser | Done | Tabs, history, downloads to library, PDF links into reader, reader mode, offline save, find, zoom, external open |
| 7 | Search & indexing | Done | Background FTS4 indexing with progress, cancel, incremental; unified search with filters and snippets |
| 8 | Annotations & reading features | Partial | Bookmarks, notes, progress restore, themes, TTS done. Text highlighting and PDF ink annotations not started |
| 9 | Modern UI & customization | Done | Material 3, light/dark/dynamic colour, grid/list, sorting, tablet navigation rail |
| 10 | Security testing & release | In progress | Unit/security/DB tests and CI build in place; on-device verification pending |

## Next up

1. Verify the APK on physical devices (Android 8 → 15) and fix issues found.
2. DOCX/XLSX/PPTX text-mode viewers (Apache POI is too heavy for Android; evaluate a streaming OOXML text extractor).
3. Text selection highlights in EPUB/text readers.
4. Instrumented UI tests (Compose) on an emulator in CI.
5. Performance pass with 2,000+ page PDFs and 10k+ bookmarks.
