package com.folio.reader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.folio.reader.data.prefs.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Book view: one page at a time (two-page spreads in landscape, cover on its
 * own like a printed book), turned with a swipe or a tap on the page edge.
 * Each turn folds the page around the spine. Pinch or double-tap to zoom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PdfBookReader(
    holder: PdfDocumentHolder,
    pageCount: Int,
    initialPage: Int,
    theme: ReaderTheme,
    jump: Int?,
    onJumped: () -> Unit,
    onPage: (Int) -> Unit,
    onTap: () -> Unit,
) {
    val filter = remember(theme) { readerColorFilter(theme) }
    val backdrop = if (theme == ReaderTheme.DARK) Color(0xFF141414) else Color(0xFF3A3A3A)
    // Remembered across rotation so switching between single pages and spreads keeps your place.
    var currentPage by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(0, pageCount - 1)) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var renderScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(scale) { delay(350); renderScale = if (scale > 1.4f) 2f else 1f }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize().background(backdrop)) {
        val twoUp = maxWidth > maxHeight && pageCount > 2
        val layout = remember(twoUp, pageCount) { SpreadLayout(pageCount, twoUp) }

        key(layout) {
            val pager = rememberPagerState(initialPage = layout.spreadOf(currentPage)) { layout.spreadCount }

            LaunchedEffect(pager) {
                snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { s ->
                    val first = layout.pagesOf(s).first()
                    currentPage = first
                    onPage(first)
                    scale = 1f; offsetX = 0f; offsetY = 0f
                }
            }
            LaunchedEffect(jump) {
                jump?.let { pager.scrollToPage(layout.spreadOf(it.coerceIn(0, pageCount - 1))); onJumped() }
            }

            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(layout) {
                        // Two fingers zoom; one finger pans only while zoomed (otherwise the pager turns pages).
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                if (fingers >= 2 || scale > 1f) {
                                    if (fingers >= 2) scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                    val pan = event.calculatePan()
                                    val maxX = size.width * (scale - 1f) / 2f
                                    val maxY = size.height * (scale - 1f) / 2f
                                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (scale < 1.02f) { scale = 1f; offsetX = 0f; offsetY = 0f }
                        }
                    }
                    .pointerInput(layout) {
                        detectTapGestures(
                            onTap = { pos ->
                                when {
                                    scale > 1f -> onTap()
                                    pos.x < widthPx * 0.22f -> scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                                    pos.x > widthPx * 0.78f -> scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(layout.spreadCount - 1)) }
                                    else -> onTap()
                                }
                            },
                            onDoubleTap = { if (scale > 1f) { scale = 1f; offsetX = 0f; offsetY = 0f } else scale = 2.2f },
                        )
                    },
            ) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = scale <= 1f,
                    beyondViewportPageCount = 1,
                    key = { it },
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offsetX; translationY = offsetY
                    },
                ) { spread ->
                    Box(Modifier.fillMaxSize().pageTurn(pager, spread)) {
                        Spread(holder, layout, spread, maxWidth, maxHeight, renderScale, filter)
                        // Shade that deepens as a page folds over or away from this one.
                        Box(Modifier.matchParentSize().graphicsLayer { alpha = shadeFor(pager, spread) }.background(Color.Black))
                    }
                }
            }
        }
    }
}

/** Page index <-> spread index. In two-up mode the cover (page 1) sits alone on the right. */
private class SpreadLayout(val pageCount: Int, val twoUp: Boolean) {
    val spreadCount: Int = if (!twoUp) pageCount else 1 + pageCount / 2
    fun spreadOf(page: Int): Int = if (!twoUp) page else (page + 1) / 2
    fun pagesOf(spread: Int): List<Int> = when {
        !twoUp -> listOf(spread)
        spread == 0 -> listOf(0)
        else -> listOf(2 * spread - 1, 2 * spread).filter { it < pageCount }
    }
}

/** Signed distance of [spread] from the settled position: > 0 already read (left), < 0 still to come (right). */
@OptIn(ExperimentalFoundationApi::class)
private fun offsetOf(pager: PagerState, spread: Int): Float = (pager.currentPage - spread) + pager.currentPageOffsetFraction

/**
 * Pages stay put instead of sliding; the upcoming page swings down around the
 * spine (left edge) on top of the current one, and swings back up when
 * turning backwards. Later pages draw above earlier ones, which gives the
 * right stacking in both directions.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pageTurn(pager: PagerState, spread: Int): Modifier = graphicsLayer {
    val o = offsetOf(pager, spread)
    translationX = size.width * o
    if (o < 0f) {
        val f = (-o).coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(0f, 0.5f)
        cameraDistance = 14f * density
        rotationY = 90f * f
        alpha = if (f >= 0.999f) 0f else 1f
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun shadeFor(pager: PagerState, spread: Int): Float {
    val o = offsetOf(pager, spread)
    return when {
        o > 0f -> 0.35f * o.coerceIn(0f, 1f)           // being covered
        o < 0f -> 0.45f * (-o).coerceIn(0f, 1f)         // folding: darker when steep
        else -> 0f
    }
}

@Composable
private fun Spread(
    holder: PdfDocumentHolder, layout: SpreadLayout, spread: Int,
    maxWidth: Dp, maxHeight: Dp, renderScale: Float, filter: ColorFilter?,
) {
    val pages = layout.pagesOf(spread)
    val vPad = 12.dp
    if (!layout.twoUp) {
        Box(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = vPad), contentAlignment = Alignment.Center) {
            BookPage(holder, pages[0], maxWidth - 12.dp, maxHeight - vPad * 2, renderScale, filter, Alignment.Center)
        }
        return
    }
    val slotW = (maxWidth - 16.dp) / 2
    val slotH = maxHeight - vPad * 2
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = vPad), horizontalArrangement = Arrangement.Center) {
        // Left slot: empty for the cover; right slot: empty after the last page.
        val left = if (spread == 0) null else pages.getOrNull(0)
        val right = if (spread == 0) pages[0] else pages.getOrNull(1)
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            left?.let { BookPage(holder, it, slotW, slotH, renderScale, filter, Alignment.CenterEnd) }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            right?.let { BookPage(holder, it, slotW, slotH, renderScale, filter, Alignment.CenterStart) }
        }
    }
}

/** One page scaled to fit its slot, rendered off the main thread at the displayed size. */
@Composable
private fun BookPage(
    holder: PdfDocumentHolder, page: Int, slotW: Dp, slotH: Dp, renderScale: Float, filter: ColorFilter?, align: Alignment,
) {
    val aspect by produceState(0.707f, page) {
        value = withContext(Dispatchers.IO) {
            runCatching { holder.size(page).let { (w, h) -> w.toFloat() / h.coerceAtLeast(1) } }.getOrDefault(0.707f)
        }.coerceIn(0.2f, 5f)
    }
    val slotAspect = slotW.value / slotH.value.coerceAtLeast(1f)
    val w: Dp = if (aspect > slotAspect) slotW else slotH * aspect
    val h: Dp = if (aspect > slotAspect) slotW / aspect else slotH
    val targetPx = with(LocalDensity.current) { (w.toPx() * renderScale).toInt() }
    val bmp by produceState<Bitmap?>(null, page, targetPx) {
        value = withContext(Dispatchers.IO) { runCatching { holder.render(page, targetPx) }.getOrNull() }
    }
    Box(Modifier.size(w, h).background(Color.White), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null && !b.isRecycled) Image(b.asImageBitmap(), "Page ${page + 1}", Modifier.matchParentSize(), contentScale = ContentScale.FillBounds, colorFilter = filter)
        else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}
