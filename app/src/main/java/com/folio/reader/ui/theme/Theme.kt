package com.folio.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.prefs.AppTheme
import com.folio.reader.data.prefs.ReaderTheme

// Palette: deep ink, warm paper, a terracotta accent and a sage secondary.
val Ink = Color(0xFF1B2A33)
val Paper = Color(0xFFF6F2EA)
val Terracotta = Color(0xFFE07A4F)
val Sage = Color(0xFF6E8B74)

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E5F), onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE6EE), onPrimaryContainer = Color(0xFF062A35),
    secondary = Color(0xFFB2552C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCC), onSecondaryContainer = Color(0xFF3A1405),
    tertiary = Color(0xFF4F6B55), tertiaryContainer = Color(0xFFD3EBD6),
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFE9E3D7), onSurfaceVariant = Color(0xFF4C5257),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBF8F2),
    surfaceContainer = Color(0xFFF1ECE2),
    surfaceContainerHigh = Color(0xFFEBE5DA),
    surfaceContainerHighest = Color(0xFFE4DDD1),
    outline = Color(0xFF8A8F92), outlineVariant = Color(0xFFD6CFC2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FCBDF), onPrimary = Color(0xFF00343F),
    primaryContainer = Color(0xFF15485A), onPrimaryContainer = Color(0xFFCFE6EE),
    secondary = Color(0xFFFFB596), onSecondary = Color(0xFF55200A),
    secondaryContainer = Color(0xFF73351A), onSecondaryContainer = Color(0xFFFFDBCC),
    tertiary = Color(0xFFB7CFBA), tertiaryContainer = Color(0xFF38513E),
    background = Color(0xFF111619), onBackground = Color(0xFFE3E2DE),
    surface = Color(0xFF111619), onSurface = Color(0xFFE3E2DE),
    surfaceVariant = Color(0xFF2A3136), onSurfaceVariant = Color(0xFFBEC4C8),
    surfaceContainerLowest = Color(0xFF0C1013),
    surfaceContainerLow = Color(0xFF161C20),
    surfaceContainer = Color(0xFF1A2125),
    surfaceContainerHigh = Color(0xFF242B30),
    surfaceContainerHighest = Color(0xFF2F363B),
    outline = Color(0xFF889196), outlineVariant = Color(0xFF3B4348),
)

private val Display = FontFamily.Serif
private val base = Typography()
val FolioTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headlineSmall = base.headlineSmall.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontFamily = Display, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
)

val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun FolioTheme(theme: AppTheme = AppTheme.SYSTEM, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (theme) { AppTheme.SYSTEM -> isSystemInDarkTheme(); AppTheme.LIGHT -> false; AppTheme.DARK -> true }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = FolioTypography, shapes = FolioShapes, content = content)
}

/** Background / text colours for reading surfaces. */
data class ReaderColors(val background: Color, val text: Color, val muted: Color, val css: String)

fun readerColors(t: ReaderTheme): ReaderColors = when (t) {
    ReaderTheme.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1D1D1D), Color(0xFF6B6B6B), "background:#ffffff;color:#1d1d1d")
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF433422), Color(0xFF7C6A55), "background:#f4ecd8;color:#433422")
    ReaderTheme.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFD9D4CC), Color(0xFF8F8A83), "background:#121212;color:#d9d4cc")
}

/** A distinct, muted colour per format family for file tiles. */
fun formatColor(f: DocFormat?): Color = when (f) {
    DocFormat.PDF -> Color(0xFFC8553D)
    DocFormat.EPUB, DocFormat.MOBI, DocFormat.FB2 -> Color(0xFF5B7F5E)
    DocFormat.DOC, DocFormat.DOCX, DocFormat.ODF -> Color(0xFF3D6C9E)
    DocFormat.XLS, DocFormat.XLSX, DocFormat.CSV, DocFormat.TSV -> Color(0xFF2F7D5B)
    DocFormat.PPT, DocFormat.PPTX -> Color(0xFFC1712E)
    DocFormat.JPEG, DocFormat.PNG, DocFormat.WEBP, DocFormat.GIF, DocFormat.BMP, DocFormat.TIFF -> Color(0xFF8A5A9E)
    DocFormat.MARKDOWN, DocFormat.HTML -> Color(0xFF4E6E8E)
    DocFormat.JSON, DocFormat.XML, DocFormat.YAML, DocFormat.LOG -> Color(0xFF5E6770)
    else -> Color(0xFF6B7178)
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
