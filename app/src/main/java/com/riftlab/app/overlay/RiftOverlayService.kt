package com.riftlab.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.riftlab.app.R
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.LiveSourceStatus
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduledEsportsMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class RiftOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: RiftOverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var draftHud: DraftHudOverlayView? = null
    private var draftDock: DraftHudControlView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var draftHudJob: Job? = null

    private data class OverlayUiState(
        val snapshot: LiveSnapshot,
        val status: LiveSourceStatus,
        val target: ScheduledEsportsMatch?
    )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        startAsForeground()
        MatchSessionStore.ensureDataRunning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            ACTION_SHOW -> showOverlay()
            ACTION_STOP -> stopSelf()
            else -> syncOverlayVisibility()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.post { clampOverlayToDisplay() }
        draftHud?.post { draftHud?.render(DraftHudSimulation.state.value) }
    }

    private fun syncOverlayVisibility() {
        if (hostInForeground) hideOverlay() else showOverlay()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlay == null) createOverlay()
        updateDraftMode(DraftHudSimulation.state.value)
    }

    private fun hideOverlay() {
        overlay?.visibility = View.GONE
        draftHud?.visibility = View.GONE
        draftDock?.visibility = View.GONE
    }

    private fun createOverlay() {
        if (overlay != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = RiftOverlayView(this) { stopSelf() }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(120)
        }
        params = layoutParams
        overlay = view
        attachDrag(view, layoutParams)
        windowManager.addView(view, layoutParams)
        view.post { clampOverlayToDisplay() }

        collectJob = scope.launch {
            combine(
                MatchSessionStore.live,
                MatchSessionStore.liveSourceStatus,
                MatchSessionStore.targetMatch
            ) { snapshot, status, target ->
                OverlayUiState(snapshot, status, target)
            }.collect { state ->
                view.render(state.snapshot, state.status, state.target)
            }
        }

        draftHudJob = scope.launch {
            DraftHudSimulation.state.collect { state ->
                updateDraftMode(state)
            }
        }
    }

    private fun createDraftWindows() {
        if (draftHud != null && draftDock != null) return

        val hud = DraftHudOverlayView(this)
        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(hud, hudParams)
        draftHud = hud

        val dock = DraftHudControlView(
            this,
            onToggleAuto = { DraftHudSimulation.toggleAuto() },
            onNext = { DraftHudSimulation.next() },
            onStop = { DraftHudSimulation.stop() }
        )
        val dockParams = WindowManager.LayoutParams(
            dp(56),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(4)
        }
        windowManager.addView(dock, dockParams)
        draftDock = dock
    }

    private fun updateDraftMode(state: DraftHudState) {
        if (hostInForeground) {
            hideOverlay()
            return
        }
        if (state.active) {
            createDraftWindows()
            overlay?.visibility = View.GONE
            draftHud?.apply {
                visibility = View.VISIBLE
                render(state)
            }
            draftDock?.apply {
                visibility = View.VISIBLE
                render(state)
            }
        } else {
            draftHud?.visibility = View.GONE
            draftDock?.visibility = View.GONE
            overlay?.visibility = View.VISIBLE
            overlay?.post { clampOverlayToDisplay() }
        }
    }

    private fun attachDrag(view: RiftOverlayView, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val threshold = dp(6)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > threshold || abs(dy) > threshold) moved = true
                    if (moved) {
                        lp.x = (startX - dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        clampLayoutParams(view, lp)
                        windowManager.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        view.cycleMode()
                        view.post { clampOverlayToDisplay() }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun clampOverlayToDisplay() {
        val view = overlay ?: return
        val lp = params ?: return
        if (view.width <= 0 || view.height <= 0) return
        clampLayoutParams(view, lp)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun clampLayoutParams(view: View, lp: WindowManager.LayoutParams) {
        val (screenWidth, screenHeight) = displaySize()
        val maxX = (screenWidth - view.width).coerceAtLeast(0)
        val maxY = (screenHeight - view.height).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
    }

    private fun displaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(42, notification)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_rift)
        .setContentTitle("RiftScreen 正在运行")
        .setContentText("赛事副屏 / 全屏 HUD · 仅控制区接收触摸")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "赛事副屏", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        collectJob?.cancel()
        draftHudJob?.cancel()
        DraftHudSimulation.stop()
        if (this::windowManager.isInitialized) {
            overlay?.let { runCatching { windowManager.removeView(it) } }
            draftHud?.let { runCatching { windowManager.removeView(it) } }
            draftDock?.let { runCatching { windowManager.removeView(it) } }
        }
        overlay = null
        draftHud = null
        draftDock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "riftscreen_overlay"
        const val ACTION_SHOW = "com.riftlab.app.overlay.SHOW"
        const val ACTION_HIDE = "com.riftlab.app.overlay.HIDE"
        const val ACTION_STOP = "com.riftlab.app.overlay.STOP"
        private const val ACTION_SYNC = "com.riftlab.app.overlay.SYNC"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var hostInForeground: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, RiftOverlayService::class.java).setAction(ACTION_SYNC)
            ContextCompat.startForegroundService(context, intent)
        }

        fun setHostForeground(context: Context, foreground: Boolean) {
            hostInForeground = foreground
            if (!isRunning) return
            val action = if (foreground) ACTION_HIDE else ACTION_SHOW
            context.startService(Intent(context, RiftOverlayService::class.java).setAction(action))
        }
    }
}
