package com.folio.reader.core.crash

import android.content.Context
import android.os.Build
import com.folio.reader.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of an uncaught exception to app-private storage so it
 * can be shown (and copied) the next time the app starts. Nothing leaves the
 * device unless the user copies or shares it.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("Folio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(sw.toString().take(30_000))
                }
                File(app.filesDir, FILE).writeText(text)
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Returns and deletes the saved report, if any. */
    fun takeReport(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
