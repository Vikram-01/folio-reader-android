# Test report

## Automated (runs in CI on every push)

`./gradlew testDebugUnitTest`

| Suite | What it checks |
|---|---|
| `SecurityTest` | Zip-slip rejection, zip-bomb rejection, bounded copies, browser URL rules (blocks `javascript:`, `file:`, `content:`, `intent:`; upgrades http), URL normalisation for duplicates, safe file names, backup encryption round-trip across chunks, wrong password and truncation detection |
| `FormatTest` | Signature-based detection (PDF, PNG, EPUB), rejection of executables / mismatched / binary-as-text files, recognition of unsupported Office files, CSV quoting and multi-line fields, RTF to text, charset fallback, EPUB path normalisation |
| `DatabaseTest` (Robolectric) | FTS4 search with snippets and page locations, hostile query syntax, SHA duplicate lookup, folder delete keeps documents, document delete cascades progress, bookmark lookup, integrity check |

Results of the latest CI run are uploaded as the `test-reports` artifact.

## Manual verification

On-device testing has not been done yet for this build. Checklist to run on a real device:

- [ ] Import a 1,000+ page PDF; scroll end to end; zoom; reopen and confirm position restored
- [ ] Import EPUB 2 and EPUB 3 books; TOC navigation; theme and font changes keep position
- [ ] Share a link from Chrome → saved; open in Folio browser
- [ ] Tap a PDF link in the browser → opens in reader, appears in library with source URL; airplane mode → still opens
- [ ] Save a page offline; airplane mode → readable
- [ ] Search finds words inside a PDF and jumps to the page
- [ ] Backup with password → uninstall → reinstall → restore
- [ ] Import a renamed `.apk` as `.pdf` → refused
- [ ] Airplane mode: all screens except the browser work
