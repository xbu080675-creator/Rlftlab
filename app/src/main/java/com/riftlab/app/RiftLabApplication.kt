package com.riftlab.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.riftlab.app.data.ComprehensiveDataCenter
import com.riftlab.app.data.MatchLifecycleArchive
import com.riftlab.app.data.MatchLifecycleCapture
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.MatchTimelineCapture
import com.riftlab.app.data.MatchTimelineStore
import com.riftlab.app.data.RiotPersistedMirror

/**
 * One ImageLoader for the whole app.
 *
 * Esports artwork stays remote (team logos, player portraits and champion icons are not bundled in
 * the APK). Coil decodes to the on-screen target size, keeps hot images in memory and persists the
 * network response in an evictable disk cache for later visits/offline reuse.
 */
class RiftLabApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RiotPersistedMirror.initialize(this)
        MatchTimelineStore.initialize(this)
        MatchLifecycleArchive.initialize(this)
        MatchTimelineCapture.start()
        MatchLifecycleCapture.start()

        // dev.68: keep the existing providers as truth, but continuously normalize their output
        // into one provenance-aware graph. Missing fields stay explicitly missing.
        MatchSessionStore.ensureDataRunning()
        ComprehensiveDataCenter.ensureRunning()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.12)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("esports_image_cache"))
                .maxSizeBytes(96L * 1024L * 1024L)
                .build()
        }
        .crossfade(true)
        .build()
}
