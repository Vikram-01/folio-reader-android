# Third-party components

## LibreOffice document engine

ReadStash lays out Word, Excel, PowerPoint and OpenDocument files with the LibreOffice engine.

- Version: LibreOffice 26.2.6.3 (LibreOffice Viewer for Android, arm64-v8a), as built from source and published by F-Droid.
- Binaries: native libraries and support files are taken from
  `https://f-droid.org/repo/org.documentfoundation.libreoffice_131.apk`
  (SHA-256 `751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475`) by `scripts/fetch-libreoffice.sh` at build time.
- Java bridge: `app/src/main/java/org/libreoffice/kit/` is copied from LibreOffice core
  (`android/Bootstrap/src`, tag `libreoffice-26.2.6.3`). `LibreOfficeKit.java` adds an `init(Context)` overload; the other files are unmodified.
- Licence: Mozilla Public License 2.0, with bundled components under their own licences (NSS/NSPR: MPL 2.0; SQLite: public domain; libc++: Apache 2.0 with LLVM exception).
- Source code: https://git.libreoffice.org/core/+/refs/tags/libreoffice-26.2.6.3 (mirror: https://github.com/LibreOffice/core/tree/libreoffice-26.2.6.3).
