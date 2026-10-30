package com.folio.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folio.reader.data.files.PdfLock
import com.folio.reader.data.prefs.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Wraps Android's built-in PdfRenderer (PDFium, part of the OS — no extra
 * native code shipped). One page is open at a time and every call goes
 * through [PdfLock], because the renderer is not thread-safe.
 */
class PdfDocumentHolder(file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    val pageCount: Int = renderer.pageCount
    private val sizes = HashMap<Int, Pair<Int, Int>>()
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 6).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var closed = false

    fun size(page: Int): Pair<Int, Int> = sizes[page] ?: PdfLock.withLock {
        if (closed) 1 to 1 else renderer.openPage(page).use { (it.width to it.height).also { s -> sizes[page] = s } }
    }

    /** Renders [page] [width] pixels wide. Cached; returns null once closed. */
    fun render(page: Int, width: Int): Bitmap? {
        val key = "$page@$width"
        cache.get(key)?.let { if (!it.isRecycled) return it }
        return PdfLock.withLock {
            if (closed || page !in 0 until pageCount) return@withLock null
            renderer.openPage(page).use { p ->
                sizes[page] = p.width to p.height
                val w = width.coerceIn(16, 2600)
                val h = (w.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(16, 4000)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bmp)
                bmp
            }
        }
    }

    override fun close() {
        PdfLock.withLock {
            closed = true
            cache.evictAll()
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    companion object {
        fun pageCount(file: File): Int = PdfLock.withLock {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
        }
    }
}

fun readerColorFilter(theme: ReaderTheme): ColorFilter? = when (theme) {
    ReaderTheme.LIGHT -> null
    // Invert luminance for night reading; colours stay recognisable-ish.
    ReaderTheme.DARK -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        -0.85f, 0f, 0f, 0f, 230f,
        0f, -0.85f, 0f, 0f, 228f,
        0f, 0f, -0.85f, 0f, 222f,
        0f, 0f, 0f, 1f, 0f)))
    ReaderTheme.SEPIA -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
        0.96f, 0f, 0f, 0f, 0f,
        0f, 0.91f, 0f, 0f, 0f,
        0f, 0f, 0.80f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f)))
}

@Composable
fun PdfReader(
    file: File,
    pageCount: Int,
    initialPage: Int,
    theme: ReaderTheme,
    jump: Int?,
    onJumped: () -> Unit,
    onPage: (Int) -> Unit,
    onTap: () -> Unit,
) {
    val holder = remember(file) { PdfDocumentHolder(file) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var renderScale by remember { mutableFloatStateOf(1f) }
    val filter = remember(theme) { readerColorFilter(theme) }
    val firstAspect = remember(holder) { holder.size(0).let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) } }

    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex + if (list.firstVisibleItemScrollOffset > 400) 1 else 0 }
            .distinctUntilChanged().collect { onPage(it.coerceIn(0, pageCount - 1)) }
    }
    LaunchedEffect(jump) { jump?.let { list.scrollToItem(it.coerceIn(0, pageCount - 1)); onJumped() } }
    // Re-render sharper pages once a zoom gesture settles.
    LaunchedEffect(scale) { delay(350); renderScale = if (scale > 1.4f) 2f else 1f }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(if (theme == ReaderTheme.DARK) androidx.compose.ui.graphics.Color(0xFF1A1A1A) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = (size.width * (scale - 1f)) / 2f
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                            event.changes.forEach { it.consume() }
                        } else if (scale > 1f) {
                            val maxX = (size.width * (scale - 1f)) / 2f
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.02f) { scale = 1f; offsetX = 0f }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offsetX = 0f } else scale = 2.2f },
                )
            },
    ) {
        val widthPx = with(LocalDensity.current) { (maxWidth - 16.dp).roundToPx() }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offsetX
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        ) {
            items(pageCount, key = { it }) { page ->
                val target = (widthPx * renderScale).toInt()
                val bmp by produceState<Bitmap?>(initialValue = null, page, target) {
                    value = withContext(Dispatchers.IO) { runCatching { holder.render(page, target) }.getOrNull() }
                }
                val aspect = bmp?.let { it.width.toFloat() / it.height } ?: firstAspect
                Box(
                    Modifier.padding(horizontal = 8.dp).fillMaxWidth().aspectRatio(aspect.coerceIn(0.2f, 5f))
                        .shadow(2.dp, RoundedCornerShape(2.dp)).background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    val b = bmp
                    if (b != null && !b.isRecycled) Image(b.asImageBitmap(), "Page ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = filter)
                    else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
fun PdfThumbnails(file: File, pageCount: Int, current: Int, onPick: (Int) -> Unit) {
    val holder = remember(file) { PdfDocumentHolder(file) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = current.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    LazyVerticalGrid(GridCells.Adaptive(96.dp), state = grid, contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 560.dp)) {
        items(pageCount, key = { it }) { p ->
            val bmp by produceState<Bitmap?>(null, p) { value = withContext(Dispatchers.IO) { runCatching { holder.render(p, 200) }.getOrNull() } }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onPick(p) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(androidx.compose.ui.graphics.Color.White)
                    .then(if (p == current) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)) else Modifier)) {
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(if (p == current) 3.dp else 0.dp), contentScale = ContentScale.Fit) }
                }
                Text("${p + 1}", style = MaterialTheme.typography.labelSmall, color = if (p == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
