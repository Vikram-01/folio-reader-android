package com.folio.reader.data.office

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.files.FileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for [OfficeConvertService]: lays out Office documents (Word, Excel,
 * PowerPoint, OpenDocument, legacy binary formats) as PDF with the bundled
 * LibreOffice engine. Requests are serialised; the engine process is released
 * after a couple of idle minutes to give its memory back.
 */
@Singleton
class OfficeEngine @Inject constructor(@ApplicationContext private val context: Context) {

    /** The engine ships for 64-bit ARM only; elsewhere callers fall back to the text view. */
    val available: Boolean by lazy {
        Build.SUPPORTED_ABIS.contains("arm64-v8a") &&
            File(context.applicationInfo.nativeLibraryDir ?: "", "liblo-native-code.so").isFile
    }

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val ids = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requests = Mutex()
    private var idleJob: Job? = null

    // Main-thread state
    private var service: Messenger? = null
    private var connecting: CompletableDeferred<Messenger>? = null
    private var bound = false

    private val replies = Messenger(Handler(Looper.getMainLooper()) { m ->
        if (m.what == OfficeConvertService.MSG_RESULT) pending.remove(m.data.getInt(OfficeConvertService.KEY_ID))?.complete(m.data)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder)
            service = m
            connecting?.complete(m)
            connecting = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The engine process died (crash or system kill). Android restarts it on the next request.
            service = null
            failAll("The document engine stopped while working on this file.")
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            if (bound) runCatching { context.unbindService(this) }
            bound = false
            failAll("The document engine stopped while working on this file.")
            connecting?.completeExceptionally(IOException("The document engine stopped."))
            connecting = null
        }

        override fun onNullBinding(name: ComponentName?) {
            connecting?.completeExceptionally(IOException("The document engine isn't available."))
            connecting = null
        }
    }

    private fun failAll(reason: String) {
        val err = Bundle().apply { putBoolean(OfficeConvertService.KEY_OK, false); putString(OfficeConvertService.KEY_ERROR, reason) }
        pending.values.forEach { it.complete(err) }
        pending.clear()
    }

    private suspend fun messenger(): Messenger = withContext(Dispatchers.Main) {
        service?.let { return@withContext it }
        val waiter = connecting ?: CompletableDeferred<Messenger>().also { d ->
            connecting = d
            if (!bound) {
                bound = context.bindService(Intent(context, OfficeConvertService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) { connecting = null; throw IOException("The document engine couldn't start.") }
            }
        }
        waiter.await()
    }

    /** Converts [input] to a PDF at [output]. Throws [IOException] with a readable reason on failure. */
    suspend fun toPdf(input: File, output: File) {
        if (!available) throw IOException("The document engine isn't included for this device's processor.")
        requests.withLock {
            idleJob?.cancel()
            try {
                val result = withTimeoutOrNull(TIMEOUT_MS) {
                    val m = messenger()
                    val id = ids.incrementAndGet()
                    val done = CompletableDeferred<Bundle>()
                    pending[id] = done
                    val msg = Message.obtain(null, OfficeConvertService.MSG_CONVERT).apply {
                        data = Bundle().apply {
                            putInt(OfficeConvertService.KEY_ID, id)
                            putString(OfficeConvertService.KEY_IN, input.path)
                            putString(OfficeConvertService.KEY_OUT, output.path)
                        }
                        replyTo = replies
                    }
                    try { m.send(msg) } catch (e: RemoteException) { pending.remove(id); throw IOException("The document engine stopped.") }
                    try { done.await() } finally { pending.remove(id) }
                }
                if (result == null) {
                    restartEngine()
                    throw IOException("This document took too long to lay out.")
                }
                if (!result.getBoolean(OfficeConvertService.KEY_OK)) {
                    throw IOException(result.getString(OfficeConvertService.KEY_ERROR) ?: "The document couldn't be laid out.")
                }
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            if (bound && pending.isEmpty()) {
                runCatching { context.unbindService(connection) }
                bound = false
                service = null
            }
        }
    }

    /** Stops a stuck engine: unbind and end its process (same app, so this is allowed). */
    private suspend fun restartEngine() = withContext(Dispatchers.Main) {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
        connecting = null
        val am = context.getSystemService(ActivityManager::class.java)
        am?.runningAppProcesses?.firstOrNull { it.processName == context.packageName + OfficeConvertService.PROCESS_SUFFIX }
            ?.let { Process.killProcess(it.pid) }
    }

    companion object {
        private const val TIMEOUT_MS = 3 * 60_000L
        private const val IDLE_RELEASE_MS = 2 * 60_000L
    }
}

/**
 * Converted PDFs for library documents, kept in the cache (regenerable) as
 * cache/office-pdf/doc-<id>.pdf, plus one-off conversions for the viewer.
 */
@Singleton
class OfficePdfCache @Inject constructor(private val engine: OfficeEngine, private val files: FileStore) {

    private val lock = Mutex()

    val available: Boolean get() = engine.available

    fun cachedFile(key: String): File = File(files.officePdfDir, "doc-$key.pdf")

    fun cached(key: String): File? = cachedFile(key).takeIf { it.isFile && it.length() > 0 }

    fun evict(key: String) { cachedFile(key).delete() }

    /** Returns the laid-out PDF for library document [key], converting [source] on first use. */
    suspend fun ensure(source: File, key: String): File = lock.withLock {
        cached(key)?.let { return@withLock it }
        val out = cachedFile(key)
        convert(source.extension.ifBlank { "docx" }, out) { target -> source.copyTo(target, overwrite = true) }
        out
    }

    /**
     * Converts a document written by [writeSource] (into a private work folder, named with
     * [extension] so the engine recognises it) to a PDF at [out]. The work folder is always deleted.
     */
    suspend fun convert(extension: String, out: File, writeSource: (File) -> Unit) = withContext(Dispatchers.IO) {
        val work = File(files.tmpDir, "lo-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifBlank { "docx" }
            val src = File(work, "document.$ext")
            writeSource(src)
            val pdf = File(work, "document.pdf")
            engine.toPdf(src, pdf)
            out.parentFile?.mkdirs()
            if (!pdf.renameTo(out)) pdf.copyTo(out, overwrite = true)
        } finally {
            work.deleteRecursively()
        }
    }

    companion object {
        /** Formats the engine lays out. The OOXML ones also have a text-only fallback. */
        fun handles(format: DocFormat): Boolean = format in setOf(
            DocFormat.DOCX, DocFormat.XLSX, DocFormat.PPTX, DocFormat.DOC, DocFormat.XLS, DocFormat.PPT, DocFormat.ODF,
        )
        fun hasTextFallback(format: DocFormat): Boolean = format == DocFormat.DOCX || format == DocFormat.XLSX || format == DocFormat.PPTX
    }
}
