# ReadStash

An offline-first Android reader, document library and bookmark manager.

ReadStash keeps a permanent library of your documents (PDF, EPUB, text, data files and images), a Raindrop-style bookmark manager with nested collections and tags, a hardened in-app browser, and full-text search across all of it. Everything is stored on the device. The in-app browser is the only part of the app that uses the network.

## What works in v1.0

| Area | Status |
|---|---|
| PDF reader | Android's built-in PDFium renderer: book view with page turns (two-page spreads in landscape) or continuous scroll, remembered; lazy page rendering, pinch / double-tap zoom, page thumbnails, page bookmarks and notes, night/sepia tint, read aloud |
| EPUB 2/3 reader | Chapter view with table of contents, font size, light/sepia/dark themes, progress per chapter |
| Office | DOCX (text, headings, lists, tables), XLSX (every sheet as a table), PPTX (slide text) — parsed in-app, no Office libraries |
| Text & data | TXT, LOG, JSON (pretty-printed), XML, YAML, RTF (converted to text), Markdown, HTML, CSV/TSV table view |
| Open without saving | "Open with Folio" or Home → Open a file shows a file read-only from its source; nothing is copied or recorded |
| Images | JPEG, PNG, WebP, GIF (animated), BMP with zoom |
| Library | Import via system picker / share sheet / "Open with", copied into private storage, duplicate detection (SHA-256), nested folders, collections, tags, favorites, rename, export, delete with confirmation |
| Bookmarks | Save from any app's share sheet, notes, tags, nested collections, favorites, duplicate URL detection, bulk move/favorite/delete, HTML import/export (Chrome/Firefox/Raindrop format), offline copies |
| Browser | Tabs, history, find in page, text zoom, reader mode, save page offline, downloads straight into the library, PDF links open in the reader with the source URL kept |
| Search | On-device SQLite FTS4 index over PDF pages, EPUB chapters, text/CSV, saved pages, plus titles, URLs, notes, tags and collections; filters for type, collection, folder, tags, dates, favorites, offline |
| Backup | Single-file backup with optional AES-256-GCM password encryption; restore merges and never deletes |

Not in this version (recognised, stored, and exportable, but not displayed): legacy DOC/XLS/PPT, ODT/ODS/ODP, MOBI/AZW3, FB2, DjVu, TIFF, password-protected PDFs. See [docs/FORMAT_COMPATIBILITY.md](docs/FORMAT_COMPATIBILITY.md).

## Building

Requirements: JDK 17 and the Android SDK (platform 35). Android Studio Ladybug or newer works out of the box.

```bash
./gradlew testDebugUnitTest            # unit, database and security tests
./gradlew assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease bundleRelease
```

### Release signing

Release builds read their key from environment variables:

| Variable | Meaning |
|---|---|
| `FOLIO_KEYSTORE_PATH` | path to the `.jks` keystore |
| `FOLIO_KEYSTORE_PASSWORD` | keystore password |
| `FOLIO_KEY_ALIAS` | key alias |
| `FOLIO_KEY_PASSWORD` | key password |

Without them the release build is signed with the debug key so it still builds; don't distribute that APK.

In GitHub Actions, the same values come from repository secrets. The keystore is committed AES-256 encrypted as `signing/folio-release.jks.gpg` and decrypted with `FOLIO_KEYSTORE_PASSWORD`; a `FOLIO_KEYSTORE_B64` secret, if set, takes precedence. The workflow in `.github/workflows/build.yml` runs the tests, builds the debug APK, release APK and release AAB, checks the signature, fails if any permission outside the allowlist in `docs/SECURITY.md` appears, and uploads the files as the `readstash-builds` artifact.

## Project layout

```
app/src/main/java/com/folio/reader/
  core/format     format detection by file signature
  core/security   zip-slip / zip-bomb guards, URL rules, backup encryption, limits
  data/db         Room entities, DAOs, FTS4 search index
  data/files      private file store, text extraction, thumbnails
  data/epub       EPUB parser
  data/repo       library, bookmarks, browser, search, backup repositories
  work            WorkManager indexing job
  ui/...          Compose screens (home, documents, bookmarks, collections, search, reader, browser, settings)
```

Architecture: MVVM with Hilt, Kotlin coroutines/Flow, Jetpack Compose + Material 3, Room, DataStore, WorkManager.

## Docs

- [Format compatibility](docs/FORMAT_COMPATIBILITY.md)
- [Database](docs/DATABASE.md)
- [Security report](docs/SECURITY.md)
- [Test report](docs/TEST_REPORT.md)
- [Development status](DEVELOPMENT_STATUS.md)

## Licences of dependencies

All dependencies are Apache 2.0, MIT or BSD: AndroidX / Jetpack Compose / Room / WorkManager / Hilt (Apache 2.0), PdfBox-Android (Apache 2.0), jsoup (MIT), commonmark-java (BSD-2), Coil (Apache 2.0), kotlinx.serialization / coroutines (Apache 2.0). PDF rendering uses Android's system `PdfRenderer` (PDFium shipped with the OS), so no native PDF library is bundled.
