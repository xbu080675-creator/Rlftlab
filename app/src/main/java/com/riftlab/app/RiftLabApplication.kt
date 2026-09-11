package com.riftlab.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.riftlab.app.ai.LocalAiCore
import com.riftlab.app.ai.LocalModelManager
import com.riftlab.app.data.ComprehensiveDataCenter
import com.riftlab.app.data.MatchLifecycleArchive
import com.riftlab.app.data.MatchLifecycleCapture
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.MatchTimelineCapture
import com.riftlab.app.data.MatchTimelineStore
import com.riftlab.app.data.QualificationCenterStore
import com.riftlab.app.data.RiotPersistedMirror
import com.riftlab.app.data.StorageCacheManager
import com.riftlab.app.data.TournamentEditionArchiveStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        RiotPersistedMirror.initialize(this)
        MatchTimelineStore.initialize(this)
        MatchLifecycleArchive.initialize(this)
        TournamentEditionArchiveStore.initialize(this)
        MatchTimelineCapture.start()
        MatchLifecycleCapture.start()

        // Local AI remains optional. Profile the device and restore only verified model-file state;
        // no model is downloaded or enabled automatically at startup.
        LocalAiCore.initialize(this)
        LocalModelManager.initialize(this)

        // Cache pressure guard. Only cacheDir/externalCacheDir are eligible. Persistent archives,
        // encrypted provider keys, user settings and downloaded local-AI models are never touched.
        maintenanceScope.launch {
            StorageCacheManager.trimIfNeeded(this@RiftLabApplication)
        }

        // Existing providers remain the source of truth. dev.68 normalizes the active graph,
        // dev.69 retains Tournament Editions, and dev.70 keeps qualification paths/annual points
        // separate from ordinary standings while preserving evidence on each route.
        MatchSessionStore.ensureDataRunning()
        TournamentEditionArchiveStore.ensureRunning()
        QualificationCenterStore.ensureRunning()
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
