package com.folio.reader

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.folio.reader.data.files.FileStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FolioApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var fileStore: FileStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Coil loader for local files only (thumbnails, images, favicons); GIFs animate. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()
        com.folio.reader.core.crash.CrashReporter.install(this)
        // The document engine runs in its own process (":office"); it must not touch the app's working files.
        if (!isMainProcess()) return
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectFileUriExposure().penaltyLog().build()
            )
        }
        // Working files from a previous session are never needed again.
        Thread { runCatching { fileStore.clearTemp() } }.start()
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else runCatching { java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' } }.getOrNull()
        return name.isNullOrEmpty() || name == packageName
    }
}
