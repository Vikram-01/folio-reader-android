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
import androidx.compose.ui.Modifier
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
                Text("This file is larger than Folio displays at once; only the first part is shown. The whole file is kept in your library and can be exported.",
                    style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

/** CSV / TSV viewer: sticky header, shared horizontal scroll, row numbers. */
@Composable
fun TableReader(rows: List<List<String>>, truncated: Boolean, colors: ReaderColors, initialRow: Int, jump: Int?, onJumped: () -> Unit,
                find: String, onRow: (Int) -> Unit, onTap: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialRow.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { onRow(it) } }
    LaunchedEffect(jump) { jump?.let { state.scrollToItem(it.coerceIn(0, (rows.size - 1).coerceAtLeast(0))); onJumped() } }
    val cols = remember(rows) { rows.take(500).maxOfOrNull { it.size } ?: 0 }
    val widths = remember(rows) {
        val density = 7.5f
        (0 until cols).map { c -> (rows.take(300).maxOfOrNull { it.getOrNull(c)?.length ?: 0 } ?: 0).coerceIn(4, 40) * density + 24f }
    }
    val h = rememberScrollState()
    val header = rows.firstOrNull().orEmpty()
    val cell = TextStyle(fontSize = 13.sp, color = colors.text)
    val hl = Color(0x66FFC107)
    Column(Modifier.fillMaxSize().background(colors.background).padding(top = 64.dp).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        Row(Modifier.horizontalScroll(h).background(colors.muted.copy(alpha = 0.15f)).padding(vertical = 8.dp)) {
            Text("#", Modifier.width(56.dp).padding(start = 8.dp), style = cell.copy(fontWeight = FontWeight.Bold, color = colors.muted))
            (0 until cols).forEach { c ->
                Text(header.getOrNull(c) ?: "", Modifier.width(widths[c].dp).padding(horizontal = 6.dp), style = cell.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            itemsIndexed(rows.drop(1), key = { i, _ -> i }) { i, row ->
                val match = find.isNotBlank() && row.any { it.contains(find, true) }
                Row(Modifier.horizontalScroll(h).background(if (match) hl else if (i % 2 == 0) Color.Transparent else colors.muted.copy(alpha = 0.06f)).padding(vertical = 6.dp)) {
                    Text("${i + 2}", Modifier.width(56.dp).padding(start = 8.dp), style = cell.copy(color = colors.muted))
                    (0 until cols).forEach { c ->
                        Text(row.getOrNull(c) ?: "", Modifier.width(widths[c].dp).padding(horizontal = 6.dp), style = cell, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (truncated) item {
                Text("Showing the first ${rows.size} rows. The full file stays in your library.", style = MaterialTheme.typography.bodySmall,
                    color = colors.muted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
fun ImageReader(file: File, colors: ReaderColors, onTap: () -> Unit) {
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
            model = ImageRequest.Builder(ctx).data(file).size(4096).build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
    }
}
