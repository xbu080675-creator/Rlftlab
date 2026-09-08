package com.riftlab.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.riftlab.app.data.LiveSnapshot
import com.riftlab.app.data.MatchSessionStore
import kotlin.math.abs

class RiftOverlayView(
    context: Context,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    enum class Mode { MINI, COMPACT, EXPANDED }

    private var mode = Mode.COMPACT

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(12))
    }

    private val title = text("LIVE · G2", 11f, 0xFF6CEBFF.toInt(), bold = true)
    private val timer = text("18:42", 11f, 0xFF94A0B2.toInt())
    private val modeChip = text("COMPACT ›", 9f, 0xFF6CEBFF.toInt(), bold = true)
    private val blue = text("BLG", 18f, Color.WHITE, bold = true)
    private val red = text("AL", 18f, Color.WHITE, bold = true)
    private val goldDiff = text("+1.6K", 24f, 0xFF6CEBFF.toInt(), bold = true)
    private val miniLine = text("BLG   +1.6K   AL", 17f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
    }
    private val metrics = text("K 8:6   T 4:3   D 2:1", 12f, 0xFFD1D7E2.toInt())
    private val goldLine = text("GOLD 34.7K : 33.1K", 11f, 0xFFD1D7E2.toInt())
    private val event = text("EVENT · 18:37 · BLG 获得小龙", 10f, 0xFF8C98AA.toInt())
    private val hint = text("点击切换尺寸 · 拖动可移动", 9f, 0xFF667386.toInt())
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

        addView(root, LayoutParams(dp(300), LayoutParams.WRAP_CONTENT))

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

        root.addView(miniLine)

        teams.addView(blue, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        teams.addView(goldDiff)
        teams.addView(red, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
        })
        root.addView(teams)

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

    fun render(snapshot: LiveSnapshot) {
        title.text = "LIVE · G${snapshot.game}"
        timer.text = MatchSessionStore.formatTime(snapshot.elapsedSeconds)
        blue.text = snapshot.blue
        red.text = snapshot.red
        val diff = formatDiff(snapshot.goldDiff)
        goldDiff.text = diff
        goldDiff.setTextColor(if (snapshot.goldDiff >= 0) 0xFF6CEBFF.toInt() else 0xFFFF667A.toInt())
        miniLine.text = "${snapshot.blue}   $diff   ${snapshot.red}"
        miniLine.setTextColor(if (snapshot.goldDiff >= 0) 0xFF6CEBFF.toInt() else 0xFFFF667A.toInt())
        metrics.text = "K ${snapshot.blueKills}:${snapshot.redKills}   T ${snapshot.blueTowers}:${snapshot.redTowers}   D ${snapshot.blueDragons}:${snapshot.redDragons}"
        goldLine.text = "GOLD %.1fK : %.1fK   LEAD $diff".format(snapshot.blueGold / 1000f, snapshot.redGold / 1000f)
        event.text = "EVENT · ${snapshot.latestEvent}"
        flashAccent()
    }

    private fun applyMode() {
        when (mode) {
            Mode.MINI -> {
                modeChip.text = "MINI ›"
                timer.visibility = View.GONE
                miniLine.visibility = View.VISIBLE
                teams.visibility = View.GONE
                metrics.visibility = View.GONE
                goldLine.visibility = View.GONE
                event.visibility = View.GONE
                hint.visibility = View.GONE
                setRootWidth(220)
            }
            Mode.COMPACT -> {
                modeChip.text = "COMPACT ›"
                timer.visibility = View.VISIBLE
                miniLine.visibility = View.GONE
                teams.visibility = View.VISIBLE
                metrics.visibility = View.VISIBLE
                goldLine.visibility = View.GONE
                event.visibility = View.GONE
                hint.visibility = View.GONE
                setRootWidth(300)
            }
            Mode.EXPANDED -> {
                modeChip.text = "EXPANDED ›"
                timer.visibility = View.VISIBLE
                miniLine.visibility = View.GONE
                teams.visibility = View.VISIBLE
                metrics.visibility = View.VISIBLE
                goldLine.visibility = View.VISIBLE
                event.visibility = View.VISIBLE
                hint.visibility = View.VISIBLE
                setRootWidth(340)
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
