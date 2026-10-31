package com.folio.reader.core.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows the report from the previous crash once, with Copy and Share. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReporter.takeReport(context)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text("ReadStash closed unexpectedly") },
        text = {
            SelectionContainer(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ReadStash crash report", text))
                Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share crash report"))
            }) { Text("Share") }
        },
    )
}
