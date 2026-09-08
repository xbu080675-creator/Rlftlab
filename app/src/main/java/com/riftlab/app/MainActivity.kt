package com.riftlab.app

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.riftlab.app.overlay.RiftOverlayService
import com.riftlab.app.ui.RiftLabApp

class MainActivity : ComponentActivity() {
    private var overlayPermissionBeforeBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayPermissionBeforeBackground = Settings.canDrawOverlays(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { RiftLabApp() }
    }

    override fun onStart() {
        super.onStart()
        RiftOverlayService.setHostForeground(this, true)
    }

    override fun onResume() {
        super.onResume()
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        if (!overlayPermissionBeforeBackground && hasOverlayPermission && !RiftOverlayService.isRunning) {
            RiftOverlayService.start(this)
        }
        overlayPermissionBeforeBackground = hasOverlayPermission
    }

    override fun onStop() {
        overlayPermissionBeforeBackground = Settings.canDrawOverlays(this)
        RiftOverlayService.setHostForeground(this, false)
        super.onStop()
    }
}
