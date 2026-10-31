# Format compatibility (v1.0)

Every imported file is checked by its **content signature**, not just its extension. Files whose bytes don't match their claimed type, executables and app packages are refused.

| Format | Extensions | Opens | Search inside | Notes |
|---|---|---|---|---|
| PDF | .pdf | Yes | Yes, per page | Rendered by Android's PdfRenderer. Text extraction via PdfBox-Android. Book view (page turns, two-page spreads in landscape) or continuous scroll; the choice is remembered. Password-protected PDFs: not supported. PDF form filling and drawing annotations: not supported. In-app page bookmarks and notes: yes. |
| EPUB 2 / 3 | .epub | Yes | Yes, per chapter | Reflowable books. Fixed-layout EPUBs display but aren't paginated. Scripts in books never run. DRM-protected books can't be opened. |
| Plain text | .txt .text | Yes | Yes | UTF-8/UTF-16 with BOM, falls back to Windows-1252. Shows the first 32 MB. |
| Log | .log | Yes | Yes | Monospace with line numbers. |
| JSON | .json | Yes | Yes | Pretty-printed when under ~4 MB. |
| XML / YAML | .xml .yaml .yml | Yes | Yes | Shown as text. |
| RTF | .rtf | Partial | Yes | Converted to plain text; formatting and images are dropped. |
| Markdown | .md .markdown | Yes | Yes | CommonMark + GFM tables. Images are not loaded. |
| HTML | .html .htm .xhtml | Partial | Yes | Sanitised; scripts, forms and remote resources are removed. Local images are not shown. |
| CSV / TSV | .csv .tsv .tab | Yes | Yes, per 100 rows | Spreadsheet grid (A, B, C… columns, numbered rows), up to 200,000 rows. |
| JPEG, PNG, WebP, BMP | | Yes | — | Zoom and pan. |
| GIF | .gif | Yes | — | Animated. |
| DOCX, DOC, ODT | .docx .doc .odt | Yes | Yes, per page | Laid out as pages by the bundled LibreOffice engine (fonts, images, tables, headers as in Office), shown in the PDF viewer with book view. Text-only fallback for DOCX if the engine fails. |
| XLSX, XLSM, XLS, ODS | .xlsx .xlsm .xls .ods | Yes | Yes, per page | Laid out as printed pages by the engine (cell styles, borders, charts). Text grid fallback for XLSX. Macros are never executed. |
| PPTX, PPT, ODP | .pptx .ppt .odp | Yes | Yes, per slide | Each slide laid out as a page, as designed. Text-only fallback for PPTX. |
| ODT, ODS, ODP | | No | No | Stored and exportable. |
| MOBI, AZW, AZW3 | | No | No | Stored and exportable. |
| FB2 | | No | No | Stored and exportable. |
| DjVu | | No | No | Stored and exportable. |
| TIFF | .tif .tiff | No | No | Android has no built-in TIFF decoder. |

"Stored" formats appear in the library with a "No viewer" filter; opening one explains that it can't be displayed yet and offers export.

## Opening without saving

"Open with ReadStash" from a file manager, or **Open a file** on the Home screen, shows any readable format above in a read-only viewer straight from the source file. Nothing is copied into the library, and no thumbnail, search index, reading progress or history is written. EPUB and Office files are the exceptions: they are unpacked or laid out in ReadStash's private cache while open and deleted when the viewer closes (and on the next app start). Sharing a file to ReadStash still adds it to the library.
