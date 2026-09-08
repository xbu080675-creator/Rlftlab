package com.riftlab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.riftlab.app.overlay.RiftOverlayService
import com.riftlab.app.stream.StreamLauncher
import com.riftlab.app.ui.RiftLabRoot
import com.riftlab.app.ui.RiftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            RiftTheme {
                RiftLabRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        RiftOverlayService.setHostForeground(this, true)
    }

    override fun onResume() {
        super.onResume()
        StreamLauncher.resumePendingIfReady(this)
    }

    override fun onStop() {
        RiftOverlayService.setHostForeground(this, false)
        super.onStop()
    }
}
