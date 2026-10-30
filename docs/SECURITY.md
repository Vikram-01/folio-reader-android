# Security report (v1.0)

Threat model: imported documents, downloaded files and visited websites are untrusted. The device owner is trusted. Folio has no server, no account and no telemetry.

## Network

| Control | Implementation |
|---|---|
| Only the browser uses the network | The app's only network code is the WebView browser and its download function (`BrowserRepository.download`). Readers, search, thumbnails, favicons and bookmarks make no requests; favicons are captured from pages the user actually opened. |
| HTTPS only | `network_security_config.xml` disables cleartext app-wide; the browser upgrades `http://` links to `https://`; downloads refuse non-HTTPS URLs and redirects. |
| Certificate errors | `onReceivedSslError` always cancels. User-installed CAs are not trusted. |
| Mixed content | `MIXED_CONTENT_NEVER_ALLOW`. |

## WebView hardening

Browser: file and content access off, no JavaScript interfaces (legacy bridges removed), no multiple windows / popups, geolocation off, camera/microphone permission requests denied, file uploads refused, Safe Browsing on, third-party cookies blocked by default, JavaScript user-toggleable, non-web schemes (`intent:`, `mailto:`, `market:` …) only after a user tap **and** a confirmation dialog, `javascript:` / `file:` / `content:` / `data:` navigations blocked.

Document WebViews (EPUB, HTML, Markdown, saved pages, reader mode): JavaScript off, DOM storage off, no cache, and a `shouldInterceptRequest` that serves only files inside the unpacked book via `https://folio.local/` and returns 403 for every other request, so a document can't load trackers or remote content. A restrictive Content-Security-Policy is attached to served chapters. SVG is never served as a top-level document. Links leave the reader only through the in-app browser.

## Untrusted files

| Risk | Control |
|---|---|
| Disguised files | Format detected from magic bytes (`FormatDetector`); mismatches, executables (PE, ELF, DEX, Mach-O) and unknown binaries rejected. |
| Zip slip | Every archive path is canonicalised and must stay inside the target folder (`SafeZip.resolveInside`); `..`, absolute paths, backslashes and drive letters are rejected. |
| Zip bombs | Entry count, per-entry and total size limits plus compression-ratio check before extraction, and byte counting during extraction. |
| Huge files | 1 GB import/download cap, 32 MB text display cap, 200k CSV rows, 8M characters indexed per document, image pixel cap. |
| XXE | XML parsed with jsoup's XML parser, which does not resolve external entities or DTDs. |
| Script in HTML / Markdown / saved pages | Sanitised with jsoup `Safelist` (no scripts, iframes, forms, event handlers, images); rendered with JavaScript off. |
| Macros | Office files are never opened or executed; they are only stored. |
| Malformed PDFs | Rendering and text extraction run inside try/catch; failures are shown as messages, not crashes. Password-protected PDFs are reported, not cracked. |
| Renderer crashes | `onRenderProcessGone` rebuilds the browser WebView instead of crashing the app. |
| Downloaded content | Saved to private storage and added to the library only if it's a recognised document/image. Nothing is ever installed, executed or opened by another app automatically. |

## Storage and privacy

- All data in app-private internal storage. No storage permissions requested; import uses the Storage Access Framework, and only `content://` URIs are accepted from other apps.
- `allowBackup=false` and data-extraction rules exclude everything from Android cloud backup and device transfer. Backups are explicit and can be encrypted (AES-256-GCM, PBKDF2-SHA256 210k iterations).
- Temporary files are created in the private cache with random names and deleted after use and at app start.
- Declared permission: `android.permission.INTERNET` only. WorkManager (used for background search indexing) merges in `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE`; none of these are runtime/dangerous permissions. CI fails the build if any permission outside this set appears.
- Release builds are minified with R8 and log calls below WARN are stripped.

## Android Keystore

v1.0 stores no credentials, API keys or tokens (there is no account or sync), so nothing needs Keystore-backed encryption. Backup passwords are never stored. If sync or credentials are added later, they must be kept in Keystore-wrapped storage.

## Dependency scanning

CI submits the Gradle dependency graph to GitHub (`gradle/actions/dependency-submission`) so Dependabot raises alerts for known-vulnerable libraries, and `dependabot.yml` opens weekly update PRs.

## Known limitations

- The SQLite database itself is not encrypted; it relies on Android's app sandbox and file-based encryption. A rooted device can read it.
- WebView security depends on the system WebView being kept up to date via Play Store.
- PDF rendering uses the platform PDFium; its patch level follows the device's Android security updates.
