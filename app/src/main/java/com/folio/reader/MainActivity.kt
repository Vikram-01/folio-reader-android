package com.folio.reader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.core.security.UrlSafety
import com.folio.reader.ui.AppRoot
import com.folio.reader.ui.MainViewModel
import com.folio.reader.ui.theme.FolioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.prefs.value == null }
        enableEdgeToEdge()
        setContent {
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            prefs?.let { p -> FolioTheme(theme = p.theme, dynamic = p.dynamicColor) { AppRoot(vm, p); com.folio.reader.core.crash.CrashReportDialog() } }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = streamExtra(intent)
                if (stream != null && stream.scheme == "content") {
                    vm.importUris(listOf(stream))
                } else if (intent.type?.startsWith("text/") == true) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val url = UrlSafety.extractUrl(text)
                    if (url != null) vm.startLinkDraft(url, intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty())
                    else vm.message("No link found in the shared text.")
                }
            }
            Intent.ACTION_VIEW -> {
                // "Open with Folio" from a file manager: show the file read-only, straight from the source.
                // Nothing is copied into the library and no history is kept (sharing a file still imports it).
                // Only content:// is accepted (other apps granting us a file); file:// would expose arbitrary paths.
                intent.data?.takeIf { it.scheme == "content" }?.let { vm.openViewer(it) }
            }
        }
        // Consume the intent so rotation or process restore doesn't re-import.
        setIntent(Intent(this, MainActivity::class.java))
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
