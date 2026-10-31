package com.folio.reader.data.office

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.io.FileOutputStream

/**
 * Hosts the LibreOffice document engine in its own process (":office"), so a
 * native crash or runaway document can never take the reader down with it.
 *
 * The engine is single-threaded: every request runs on one HandlerThread.
 * A request names an input file and an output path; the reply says whether a
 * PDF was written. Files only ever come from ReadStash's private storage.
 */
class OfficeConvertService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger
    private var office: Office? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("office-engine").apply { start() }
        messenger = Messenger(Handler(thread.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        if (msg.what != MSG_CONVERT) return
        val data = msg.data
        val id = data.getInt(KEY_ID)
        val result = Bundle().apply { putInt(KEY_ID, id) }
        try {
            convert(File(data.getString(KEY_IN)!!), File(data.getString(KEY_OUT)!!))
            result.putBoolean(KEY_OK, true)
        } catch (t: Throwable) {
            Log.w(TAG, "Conversion failed", t)
            result.putBoolean(KEY_OK, false)
            result.putString(KEY_ERROR, t.message ?: t.javaClass.simpleName)
        }
        val reply = Message.obtain(null, MSG_RESULT).apply { this.data = result }
        runCatching { msg.replyTo?.send(reply) }
    }

    private fun engine(): Office {
        office?.let { return it }
        unpackSupportFiles()
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
        LibreOfficeKit.init(this)
        if (!LibreOfficeKit.isInitialized()) throw IllegalStateException("The document engine couldn't start on this device.")
        return Office(LibreOfficeKit.getLibreOfficeKitHandle()).also { office = it }
    }

    private fun convert(input: File, output: File) {
        require(input.isFile) { "The document file is missing." }
        val o = engine()
        val doc = o.documentLoad(input.path)
            ?: throw IllegalStateException(o.error?.takeIf { it.isNotBlank() } ?: "The document couldn't be opened (it may be damaged or password-protected).")
        try {
            output.delete()
            output.parentFile?.mkdirs()
            doc.saveAs("file://" + output.path, "pdf", "")
        } finally {
            doc.destroy()
        }
        if (!output.isFile || output.length() == 0L) throw IllegalStateException("The document engine produced no pages.")
    }

    /**
     * LibreOffice needs a few configuration files on disk (assets/unpack -> app data dir),
     * as its own Android app does. Done once per app version.
     */
    private fun unpackSupportFiles() {
        val dataDir = File(applicationInfo.dataDir)
        val version = packageManager.getPackageInfo(packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
        val marker = File(dataDir, ".lo-unpacked")
        if (marker.isFile && marker.readText() == version.toString()) return
        copyAssets("unpack", dataDir)
        marker.writeText(version.toString())
    }

    private fun copyAssets(from: String, to: File) {
        val names = assets.list(from) ?: return
        for (name in names) {
            val path = "$from/$name"
            val children = assets.list(path)
            if (children.isNullOrEmpty()) {
                to.mkdirs()
                assets.open(path).use { input -> FileOutputStream(File(to, name)).use { input.copyTo(it) } }
            } else copyAssets(path, File(to, name))
        }
    }

    companion object {
        private const val TAG = "OfficeEngine"
        const val MSG_CONVERT = 1
        const val MSG_RESULT = 2
        const val KEY_ID = "id"
        const val KEY_IN = "in"
        const val KEY_OUT = "out"
        const val KEY_OK = "ok"
        const val KEY_ERROR = "error"
        const val PROCESS_SUFFIX = ":office"
    }
}
