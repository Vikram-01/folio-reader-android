package com.folio.reader.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.folio.reader.core.format.DocFormat
import com.folio.reader.data.db.AppDatabase
import com.folio.reader.data.files.FileStore
import com.folio.reader.data.files.TextExtractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class IndexState(val running: Boolean, val done: Int, val total: Int, val current: String?)

@Singleton
class IndexScheduler @Inject constructor(private val wm: WorkManager) {

    /** Indexes every document not yet indexed. Incremental: finished documents are skipped. */
    fun indexPending() {
        wm.enqueueUniqueWork(IndexWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<IndexWorker>().build())
    }

    fun rebuild() {
        wm.enqueueUniqueWork(IndexWorker.NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf(IndexWorker.KEY_REBUILD to true)).build())
    }

    fun cancel() { wm.cancelUniqueWork(IndexWorker.NAME) }

    val state: Flow<IndexState> = wm.getWorkInfosForUniqueWorkFlow(IndexWorker.NAME).map { infos ->
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (running == null) IndexState(false, 0, 0, null)
        else IndexState(
            true,
            running.progress.getInt(IndexWorker.KEY_DONE, 0),
            running.progress.getInt(IndexWorker.KEY_TOTAL, 0),
            running.progress.getString(IndexWorker.KEY_CURRENT),
        )
    }
}

/**
 * Background full-text indexer. Runs on WorkManager so it survives the app
 * going to the background; it can be cancelled from Settings, and each
 * document is committed separately so progress is never lost.
 */
@HiltWorker
class IndexWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val files: FileStore,
    private val extractor: TextExtractor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_REBUILD, false)) {
            db.search().clear()
            db.documents().resetIndexState()
            for (b in db.bookmarks().all()) indexBookmarkOffline(b.id, b.offlineName)
        }
        val pending = db.documents().unindexed()
        pending.forEachIndexed { i, doc ->
            setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to pending.size, KEY_CURRENT to doc.title))
            val format = DocFormat.entries.firstOrNull { it.name == doc.format }
            if (format == null || !format.supported) {
                db.documents().markIndexed(doc.id, System.currentTimeMillis(), null)
                return@forEachIndexed
            }
            try {
                val owner = doc.id.toString()
                db.search().deleteOwner("doc", owner)
                val batch = ArrayList<Triple<String, String, String>>()
                suspend fun flush() {
                    if (batch.isEmpty()) return
                    db.withTransaction { batch.forEach { (l, lab, t) -> db.search().insert("doc", owner, l, lab, t) } }
                    batch.clear()
                }
                extractor.extract(files.libraryFile(doc.storedName), format, owner) { b ->
                    batch += Triple(b.location.toString(), b.label, b.text)
                    if (batch.size >= 100) flush()
                }
                flush()
                db.documents().markIndexed(doc.id, System.currentTimeMillis(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                db.search().deleteOwner("doc", doc.id.toString())
                db.documents().markIndexed(doc.id, null, (e.message ?: e.javaClass.simpleName).take(200))
            }
        }
        setProgress(workDataOf(KEY_DONE to pending.size, KEY_TOTAL to pending.size))
        return Result.success()
    }

    private suspend fun indexBookmarkOffline(id: Long, offlineName: String?) {
        if (offlineName == null) return
        val f = runCatching { files.pageFile(offlineName) }.getOrNull() ?: return
        if (!f.isFile) return
        val text = org.jsoup.Jsoup.parse(f, "UTF-8").body()?.text().orEmpty()
        db.search().replaceOwner("bookmark", id.toString(), listOf(Triple("0", "Saved page", text.take(2_000_000))))
    }

    companion object {
        const val NAME = "folio-index"
        const val KEY_REBUILD = "rebuild"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"
    }
}
