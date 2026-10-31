package com.folio.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.folio.reader.ui.theme.ReaderColors
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

private fun highlight(line: String, query: String, bg: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(line)
    return buildAnnotatedString {
        var i = 0
        while (i < line.length) {
            val hit = line.indexOf(query, i, ignoreCase = true)
            if (hit < 0) { append(line.substring(i)); break }
            append(line.substring(i, hit))
            pushStyle(SpanStyle(background = bg, fontWeight = FontWeight.Bold))
            append(line.substring(hit, hit + query.length))
            pop()
            i = hit + query.length
        }
    }
}

/** Plain text, logs, JSON, XML, YAML, RTF. Virtualised by line, so multi-megabyte logs stay smooth. */
@Composable
fun TextReader(
    lines: List<String>, mono: Boolean, truncated: Boolean, colors: ReaderColors, fontScale: Float,
    initialLine: Int, jump: Int?, onJumped: () -> Unit, find: String, onLine: (Int) -> Unit, onTap: () -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialLine.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { onLine(it) } }
    LaunchedEffect(jump) { jump?.let { state.scrollToItem(it.coerceIn(0, (lines.size - 1).coerceAtLeast(0))); onJumped() } }
    val style = if (mono) TextStyle(fontFamily = FontFamily.Monospace, fontSize = (13 * fontScale).sp, lineHeight = (19 * fontScale).sp, color = colors.text)
        else TextStyle(fontFamily = FontFamily.Serif, fontSize = (17 * fontScale).sp, lineHeight = (27 * fontScale).sp, color = colors.text)
    val hl = Color(0x66FFC107)
    SelectionContainer {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 72.dp, bottom = 120.dp),
        ) {
            itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
                Row {
                    if (mono) Text("${i + 1}".padStart(5), style = style.copy(color = colors.muted), modifier = Modifier.padding(end = 12.dp))
                    Text(highlight(line, find, hl), style = style, modifier = if (mono) Modifier else Modifier.padding(bottom = 2.dp))
                }
            }
            if (truncated) item {
                Text("This file is larger than ReadStash displays at once; only the first part is shown. The whole file is kept in your library and can be exported.",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

/** Spreadsheet column name for a 0-based index: 0 -> A, 25 -> Z, 26 -> AA. */
internal fun columnName(index: Int): String {
    var n = index + 1
    val sb = StringBuilder()
    while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
    return sb.reverse().toString()
}

/**
 * CSV / TSV / spreadsheet viewer laid out like a spreadsheet: A, B, C… column
 * headers, numbered rows, grid lines, and every row of the file shown as data
 * (the first row is not turned into a header). Shared horizontal scroll.
 */
@Composable
fun TableReader(rows: List<List<String>>, truncated: Boolean, colors: ReaderColors, initialRow: Int, jump: Int?, onJumped: () -> Unit,
                find: String, onRow: (Int) -> Unit, onTap: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { onRow(it) } }
    LaunchedEffect(jump) { jump?.let { state.scrollToItem(it.coerceIn(0, (rows.size - 1).coerceAtLeast(0))); onJumped() } }
    val cols = remember(rows) { (rows.take(2000).maxOfOrNull { it.size } ?: 0).coerceAtLeast(1) }
    val widths = remember(rows) {
        val density = 7.5f
        (0 until cols).map { c -> (rows.take(300).maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0).coerceIn(4, 40) * density + 24f }
    }
    val h = rememberScrollState()
    val cell = TextStyle(fontSize = 13.sp, color = colors.text)
    val headerBg = colors.muted.copy(alpha = 0.16f)
    val grid = colors.muted.copy(alpha = 0.28f)
    val hl = Color(0x66FFC107)
    val rowNumWidth = (36 + 8 * rows.size.toString().length).dp
    Column(Modifier.fillMaxSize().background(colors.background).padding(top = 64.dp).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        // Column letters
        Row(Modifier.horizontalScroll(h).background(headerBg)) {
            Box(Modifier.width(rowNumWidth).height(28.dp).gridCell(grid))
            (0 until cols).forEach { c ->
                Box(Modifier.width(widths[c].dp).height(28.dp).gridCell(grid), contentAlignment = Alignment.Center) {
                    Text(columnName(c), style = cell.copy(fontWeight = FontWeight.Medium, color = colors.muted), maxLines = 1)
                }
            }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            itemsIndexed(rows, key = { i, _ -> i }) { i, row ->
                val match = find.isNotBlank() && row.any { it.contains(find, true) }
                Row(Modifier.horizontalScroll(h).height(IntrinsicSize.Min).background(if (match) hl else Color.Transparent)) {
                    Box(Modifier.width(rowNumWidth).fillMaxHeight().background(headerBg).gridCell(grid), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = cell.copy(color = colors.muted, fontWeight = FontWeight.Medium))
                    }
                    (0 until cols).forEach { c ->
                        val v = row.getOrNull(c) ?: ""
                        val numeric = v.isNotEmpty() && v.trim().toDoubleOrNull() != null
                        Box(Modifier.width(widths[c].dp).fillMaxHeight().gridCell(grid).padding(horizontal = 6.dp, vertical = 5.dp),
                            contentAlignment = if (numeric) Alignment.CenterEnd else Alignment.CenterStart) {
                            Text(v, style = cell, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (truncated) item {
                Text("Showing the first ${rows.size} rows.", style = MaterialTheme.typography.bodySmall,
                    color = colors.muted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** Thin right and bottom grid lines, like spreadsheet cells. */
private fun Modifier.gridCell(color: Color): Modifier = drawBehind {
    val w = 1f
    drawLine(color, androidx.compose.ui.geometry.Offset(size.width - w / 2, 0f), androidx.compose.ui.geometry.Offset(size.width - w / 2, size.height), w)
    drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height - w / 2), androidx.compose.ui.geometry.Offset(size.width, size.height - w / 2), w)
}

@Composable
fun ImageReader(model: Any, colors: ReaderColors, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(model).size(4096).build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}
