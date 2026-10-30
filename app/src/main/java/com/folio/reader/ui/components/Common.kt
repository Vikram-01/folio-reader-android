package com.folio.reader.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.folio.reader.core.format.DocFormat
import com.folio.reader.ui.theme.formatColor
import java.io.File

fun formatIcon(f: DocFormat?): ImageVector = when (f) {
    DocFormat.PDF -> Icons.Outlined.PictureAsPdf
    DocFormat.EPUB, DocFormat.MOBI, DocFormat.FB2 -> Icons.Outlined.MenuBook
    DocFormat.DOC, DocFormat.DOCX, DocFormat.ODF, DocFormat.RTF -> Icons.Outlined.Description
    DocFormat.XLS, DocFormat.XLSX, DocFormat.CSV, DocFormat.TSV -> Icons.Outlined.GridOn
    DocFormat.PPT, DocFormat.PPTX -> Icons.Outlined.Slideshow
    DocFormat.JPEG, DocFormat.PNG, DocFormat.WEBP, DocFormat.GIF, DocFormat.BMP, DocFormat.TIFF, DocFormat.DJVU -> Icons.Outlined.Image
    DocFormat.JSON, DocFormat.XML, DocFormat.YAML, DocFormat.HTML -> Icons.Outlined.Code
    DocFormat.MARKDOWN -> Icons.Outlined.Article
    DocFormat.TXT, DocFormat.LOG -> Icons.Outlined.TextSnippet
    null -> Icons.Outlined.InsertDriveFile
}

/** Document cover: the generated thumbnail, or a typographic tile in the format's colour. */
@Composable
fun DocCover(thumb: File?, format: DocFormat?, title: String, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val color = formatColor(format)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null && thumb.exists()) {
            AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(formatIcon(format), null, tint = color, modifier = Modifier.size(22.dp))
                Text(
                    title, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = MaterialTheme.typography.titleLarge.fontFamily),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                Text(format?.label?.uppercase() ?: "FILE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun FormatBadge(format: DocFormat?, modifier: Modifier = Modifier) {
    val c = formatColor(format)
    Text(
        format?.label?.uppercase() ?: "FILE",
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.16f)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = c, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, destructive: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String = "", label: String = "Name", confirm: String = "Save", singleLine: Boolean = true,
                    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Comma / enter separated tag editor with removable chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditor(tags: List<String>, onChange: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    fun commit(raw: String) {
        val new = raw.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
        if (new.isNotEmpty()) onChange((tags + new).distinctBy { it.lowercase() })
        input = ""
    }
    Column(modifier) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    InputChip(selected = false, onClick = { onChange(tags - t) }, label = { Text("#$t") },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) })
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.endsWith(",") || it.endsWith("\n")) commit(it.dropLast(1)) else input = it },
            label = { Text("Add tags (comma separated)") },
            singleLine = true,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit(input) }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun relativeTime(ms: Long?): String =
    if (ms == null || ms == 0L) "—" else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Renders "[[match]]" markers from the FTS snippet as bold highlighted spans. */
fun highlightSnippet(s: String, highlight: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var rest = s.replace('\n', ' ')
        while (true) {
            val a = rest.indexOf("[[")
            if (a < 0) { append(rest); break }
            val b = rest.indexOf("]]", a + 2)
            if (b < 0) { append(rest.replace("[[", "")); break }
            append(rest.substring(0, a))
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, background = highlight))
            append(rest.substring(a + 2, b))
            pop()
            rest = rest.substring(b + 2)
        }
    }
