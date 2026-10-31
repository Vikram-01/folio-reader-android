#!/usr/bin/env bash
# Fetches the LibreOffice document engine (LibreOffice Viewer 26.2.6.3, arm64-v8a, as built from
# source and published by F-Droid) and extracts its native libraries and support files into
# third_party/libreoffice/, where the app build picks them up. The download is pinned by SHA-256.
#
# LibreOffice is licensed under the MPL 2.0; its source is at
# https://git.libreoffice.org/core/+/refs/tags/libreoffice-26.2.6.3 (see docs/THIRD_PARTY.md).
set -euo pipefail

URL="https://f-droid.org/repo/org.documentfoundation.libreoffice_131.apk"
SHA256="751ac3836890b79a56801bf6d9a2788cda3761ab5131a4f0b6abfbad822bb475"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/third_party/libreoffice"
if [ -f "$OUT/.done-$SHA256" ]; then
  echo "LibreOffice engine already present."
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Downloading LibreOffice engine…"
curl -fsSL --retry 3 -o "$TMP/lo.apk" "$URL"
echo "$SHA256  $TMP/lo.apk" | sha256sum -c -

rm -rf "$OUT"
mkdir -p "$OUT/jniLibs" "$OUT/assets"
unzip -q "$TMP/lo.apk" 'lib/arm64-v8a/*' -d "$TMP/lib"
mv "$TMP/lib/lib/arm64-v8a" "$OUT/jniLibs/arm64-v8a"
unzip -q "$TMP/lo.apk" 'assets/*' -x 'assets/dexopt/*' -d "$TMP/assets"
cp -R "$TMP/assets/assets/." "$OUT/assets/"
touch "$OUT/.done-$SHA256"
du -sh "$OUT/jniLibs" "$OUT/assets"
