package com.riftlab.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.riftlab.app.data.EsportsAssetCache
import com.riftlab.app.data.EsportsTeamRef
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.LiveSourcePhase
import com.riftlab.app.data.LiveSourceStatus
import com.riftlab.app.data.MatchSessionStore
import com.riftlab.app.data.ScheduledEsportsMatch
import kotlin.math.abs

class RiftOverlayView(
    context: Context,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    enum class Mode { MINI, COMPACT, EXPANDED }

    private var mode = Mode.COMPACT
    private val imageLoader = ImageLoader.Builder(context).build()

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }

    private val title = text("等待数据", 11f, 0xFF94A0B2.toInt(), bold = true)
    private val timer = text("--:--", 11f, 0xFF94A0B2.toInt())
    private val modeChip = text("COMPACT ›", 9f, 0xFF6CEBFF.toInt(), bold = true)

    private val blueLogo = logoView(38)
    private val redLogo = logoView(38)
    private val blue = text("—", 9f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
    private val red = text("—", 9f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
    private val blueTeam = teamIdentity(blueLogo, blue)
    private val redTeam = teamIdentity(redLogo, red)

    private val goldDiff = text("—", 22f, 0xFF94A0B2.toInt(), bold = true).apply {
        gravity = Gravity.CENTER
        minWidth = dp(84)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private val miniBlueLogo = logoView(25)
    private val miniRedLogo = logoView(25)
    private val miniCenter = text("VS", 16f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
    }
    private val miniTeams = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(miniBlueLogo, LinearLayout.LayoutParams(dp(25), dp(25)))
        addView(miniCenter, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(miniRedLogo, LinearLayout.LayoutParams(dp(25), dp(25)))
    }

    private val metrics = text("K —   T —   D —", 12f, 0xFFD1D7E2.toInt())
    private val goldLine = text("GOLD — : —   LEAD —", 11f, 0xFFD1D7E2.toInt())
    private val event = text("STATUS · 等待 Riot 数据源", 10f, 0xFF8C98AA.toInt())
    private val hint = text("轻点切换尺寸 · 拖动可移动", 9f, 0xFF667386.toInt())
    private val accent = View(context)
    private val teams = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        elevation = dp(14).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0xEE0B1019.toInt())
            setStroke(dp(1), 0xFF233347.toInt())
        }

        addView(root, LayoutParams(dp(308), LayoutParams.WRAP_CONTENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(timer)
        modeChip.setPadding(dp(10), 0, 0, 0)
        modeChip.setOnClickListener { cycleMode() }
        top.addView(modeChip)
        val close = text("×", 20f, 0xFF8C98AA.toInt(), bold = true).apply {
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { onClose() }
        }
        top.addView(close)
        root.addView(top)

        accent.setBackgroundColor(0xFF6CEBFF.toInt())
        root.addView(accent, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(2)).apply {
            topMargin = dp(6)
            bottomMargin = dp(9)
        })

        root.addView(miniTeams)

        teams.addView(blueTeam, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        teams.addView(goldDiff, LinearLayout.LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))
        teams.addView(redTeam, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        root.addView(teams)

        metrics.gravity = Gravity.CENTER_HORIZONTAL
        metrics.setPadding(0, dp(5), 0, 0)
        root.addView(metrics)
        goldLine.setPadding(0, dp(6), 0, 0)
        root.addView(goldLine)
        event.setPadding(0, dp(5), 0, 0)
        root.addView(event)
        hint.setPadding(0, dp(7), 0, 0)
        root.addView(hint)

        applyMode()
    }

    fun cycleMode() {
        mode = when (mode) {
            Mode.MINI -> Mode.COMPACT
            Mode.COMPACT -> Mode.EXPANDED
            Mode.EXPANDED -> Mode.MINI
        }
        applyMode()
    }

    fun render(
        snapshot: LiveSnapshot,
        status: LiveSourceStatus,
        target: ScheduledEsportsMatch?
    ) {
        val isLive = status.phase == LiveSourcePhase.LIVE
        val scheduledLeft = target?.teams?.getOrNull(0)?.displayCode().orEmpty()
        val scheduledRight = target?.teams?.getOrNull(1)?.displayCode().orEmpty()
        val left = if (isLive) snapshot.blue else scheduledLeft.ifBlank { "—" }
        val right = if (isLive) snapshot.red else scheduledRight.ifBlank { "—" }
        val leftTeam = target?.teams?.firstOrNull { sameLabel(left, it) } ?: target?.teams?.getOrNull(0)
        val rightTeam = target?.teams?.firstOrNull { sameLabel(right, it) } ?: target?.teams?.getOrNull(1)

        title.text = when (status.phase) {
            LiveSourcePhase.LIVE -> "LIVE · G${snapshot.game}"
            LiveSourcePhase.BETWEEN_GAMES -> "等待本局"
            LiveSourcePhase.WAITING_FOR_MATCH -> "等待比赛"
            LiveSourcePhase.ERROR -> "数据源异常"
            LiveSourcePhase.IDLE -> "等待数据"
        }
        title.setTextColor(
            when (status.phase) {
                LiveSourcePhase.LIVE -> 0xFF6CEBFF.toInt()
                LiveSourcePhase.ERROR -> 0xFFFF667A.toInt()
                else -> 0xFF94A0B2.toInt()
            }
        )
        timer.text = if (isLive) MatchSessionStore.formatTime(snapshot.elapsedSeconds) else "--:--"
        blue.text = left
        red.text = right
        loadLogo(blueLogo, left, leftTeam?.imageUrl.orEmpty())
        loadLogo(redLogo, right, rightTeam?.imageUrl.orEmpty())
        loadLogo(miniBlueLogo, left, leftTeam?.imageUrl.orEmpty())
        loadLogo(miniRedLogo, right, rightTeam?.imageUrl.orEmpty())

        if (isLive) {
            val diff = formatDiff(snapshot.goldDiff)
            val leadColor = if (snapshot.goldDiff >= 0) 0xFF6CEBFF.toInt() else 0xFFFF667A.toInt()
            goldDiff.text = diff
            goldDiff.setTextColor(leadColor)
            miniCenter.text = diff
            miniCenter.setTextColor(leadColor)
            metrics.text = "K ${snapshot.blueKills}:${snapshot.redKills}   T ${snapshot.blueTowers}:${snapshot.redTowers}   D ${snapshot.blueDragons}:${snapshot.redDragons}"
            goldLine.text = "GOLD %.1fK : %.1fK   LEAD $diff".format(snapshot.blueGold / 1000f, snapshot.redGold / 1000f)
            event.text = "EVENT · ${snapshot.latestEvent}"
        } else {
            goldDiff.text = "VS"
            goldDiff.setTextColor(0xFF94A0B2.toInt())
            miniCenter.text = "VS"
            miniCenter.setTextColor(Color.WHITE)
            metrics.text = "K —   T —   D —"
            goldLine.text = "GOLD — : —   LEAD —"
            event.text = "STATUS · ${status.message}"
        }

        flashAccent()
    }

    private fun EsportsTeamRef.displayCode(): String = code.ifBlank { name }.ifBlank { "—" }

    private fun sameLabel(label: String, team: EsportsTeamRef): Boolean {
        val a = token(label)
        if (a.isBlank()) return false
        return listOf(team.code, team.name, team.slug, team.id).any { raw ->
            val b = token(raw)
            b.isNotBlank() && (a == b || a.contains(b) || b.contains(a))
        }
    }

    private fun token(value: String): String = value.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private fun loadLogo(view: ImageView, code: String, directUrl: String) {
        val resolved = EsportsAssetCache.normalize(directUrl).ifBlank { EsportsAssetCache.team(code) }
        if (resolved.isBlank()) {
            view.setImageDrawable(null)
            return
        }
        imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(resolved)
                .target(view)
                .build()
        )
    }

    private fun logoView(sizeDp: Int): ImageView = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        adjustViewBounds = true
    }

    private fun teamIdentity(logo: ImageView, label: TextView): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(logo, LinearLayout.LayoutParams(dp(38), dp(38)))
        label.setPadding(0, dp(3), 0, 0)
        addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun applyMode() {
        when (mode) {
            Mode.MINI -> {
                modeChip.text = "MINI ›"
                timer.visibility = View.GONE
                miniTeams.visibility = View.VISIBLE
                teams.visibility = View.GONE
                metrics.visibility = View.GONE
                goldLine.visibility = View.GONE
                event.visibility = View.GONE
                hint.visibility = View.GONE
                setRootWidth(228)
            }
            Mode.COMPACT -> {
                modeChip.text = "COMPACT ›"
                timer.visibility = View.VISIBLE
                miniTeams.visibility = View.GONE
                teams.visibility = View.VISIBLE
                metrics.visibility = View.VISIBLE
                goldLine.visibility = View.GONE
                event.visibility = View.GONE
                hint.visibility = View.GONE
                setRootWidth(308)
            }
            Mode.EXPANDED -> {
                modeChip.text = "EXPANDED ›"
                timer.visibility = View.VISIBLE
                miniTeams.visibility = View.GONE
                teams.visibility = View.VISIBLE
                metrics.visibility = View.VISIBLE
                goldLine.visibility = View.VISIBLE
                event.visibility = View.VISIBLE
                hint.visibility = View.VISIBLE
                setRootWidth(348)
            }
        }
        requestLayout()
    }

    private fun setRootWidth(widthDp: Int) {
        root.layoutParams = LayoutParams(dp(widthDp), LayoutParams.WRAP_CONTENT)
    }

    private fun flashAccent() {
        ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
            duration = 320
            addUpdateListener { accent.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun formatDiff(value: Int): String {
        val prefix = if (value >= 0) "+" else "-"
        val absolute = abs(value)
        return if (absolute >= 1000) "$prefix%.1fK".format(absolute / 1000f) else "$prefix$absolute"
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
