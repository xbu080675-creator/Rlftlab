package com.riftlab.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Full-screen visual layer for draft intelligence. The WindowManager window hosting this view is
 * FLAG_NOT_TOUCHABLE, so taps/swipes continue to the video app underneath. The bottom broadcast
 * package area is intentionally left empty; only the separate control dock is touchable.
 */
class DraftHudOverlayView(context: Context) : FrameLayout(context) {
    private val topStatus = pill("RIFTSCREEN · DRAFT SIM", 10f, 0xFF8CEBFF.toInt())
    private val progress = pill("0 / 10", 9f, Color.WHITE)
    private val blueCard = pickCard(blue = true)
    private val redCard = pickCard(blue = false)
    private val matchupCard = matchupCard()
    private val watermark = text("SIMULATION · TEST FIXTURE · 非真实赛事统计", 9f, 0xAAFFFFFF.toInt(), bold = true)

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(topStatus, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })
        addView(progress, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(12)
            marginEnd = dp(18)
        })
        addView(blueCard.root, LayoutParams(dp(252), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(58)
            marginStart = dp(18)
        })
        addView(redCard.root, LayoutParams(dp(252), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(58)
            marginEnd = dp(18)
        })
        addView(matchupCard.root, LayoutParams(dp(294), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(154)
        })
        addView(watermark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(245)
        })
    }

    fun render(state: DraftHudState) {
        visibility = if (state.active) View.VISIBLE else View.GONE
        if (!state.active) return

        topStatus.text = when {
            state.finished -> "RIFTSCREEN · DRAFT LOCKED"
            state.autoPlay -> "RIFTSCREEN · DRAFT SIM · AUTO"
            else -> "RIFTSCREEN · DRAFT SIM · MANUAL"
        }
        progress.text = "${state.step} / ${state.totalSteps}"

        val latestBlue = state.bluePicks.lastOrNull()
        val latestRed = state.redPicks.lastOrNull()
        blueCard.bind(latestBlue)
        redCard.bind(latestRed)
        matchupCard.bind(state.matchup)

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val compactWidth = if (landscape) 252 else 158
        (blueCard.root.layoutParams as LayoutParams).width = dp(compactWidth)
        (redCard.root.layoutParams as LayoutParams).width = dp(compactWidth)
        (matchupCard.root.layoutParams as LayoutParams).apply {
            width = dp(if (landscape) 294 else 228)
            topMargin = dp(if (landscape) 154 else 250)
        }
        watermark.visibility = if (landscape) View.VISIBLE else View.GONE
        requestLayout()
    }

    private fun pickCard(blue: Boolean): PickCard {
        val title = text(if (blue) "BLUE PICK" else "RED PICK", 10f, if (blue) 0xFF6CEBFF.toInt() else 0xFFFF6F79.toInt(), bold = true)
        val champion = text("等待锁定", 20f, Color.WHITE, bold = true)
        val player = text("—", 10f, 0xFFB9C3D3.toInt())
        val version = text("SIM WR —", 10f, 0xFFE6EBF2.toInt())
        val comfort = text("PLAYER —", 10f, 0xFFE6EBF2.toInt())
        val hint = text("ROLE —", 9f, 0xFF8E9AAE.toInt())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            background = panelBackground(if (blue) 0xCC071D29.toInt() else 0xCC291015.toInt(), if (blue) 0xFF4EDCF4.toInt() else 0xFFFF5D69.toInt())
            addView(title)
            addView(champion)
            addView(player)
            addView(version)
            addView(comfort)
            addView(hint)
        }
        return PickCard(root, champion, player, version, comfort, hint)
    }

    private fun matchupCard(): MatchupCard {
        val header = text("MATCHUP", 9f, 0xFF8CEBFF.toInt(), bold = true).apply { gravity = Gravity.CENTER }
        val champions = text("等待形成对位", 15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        val verdict = text("—", 11f, 0xFFFFD76C.toInt(), bold = true).apply { gravity = Gravity.CENTER }
        val detail = text("CSD@15 — · SAMPLE —", 9f, 0xFFB9C3D3.toInt()).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(9))
            background = panelBackground(0xD911151C.toInt(), 0xAA8CEBFF.toInt())
            addView(header)
            addView(champions)
            addView(verdict)
            addView(detail)
            visibility = View.GONE
        }
        return MatchupCard(root, header, champions, verdict, detail)
    }

    private inner class PickCard(
        val root: LinearLayout,
        private val champion: TextView,
        private val player: TextView,
        private val version: TextView,
        private val comfort: TextView,
        private val hint: TextView
    ) {
        fun bind(pick: DraftHudPick?) {
            root.alpha = if (pick == null) 0.42f else 1f
            champion.text = pick?.champion ?: "等待锁定"
            player.text = pick?.let { "${it.player} · ${it.role.name}" } ?: "—"
            version.text = pick?.let { "SIM WR %.1f%% · %d局".format(it.versionWinRate, it.sampleGames) } ?: "SIM WR —"
            comfort.text = pick?.let { "PLAYER %d局 · %.1f%%".format(it.playerGames, it.playerWinRate) } ?: "PLAYER —"
            hint.text = pick?.let { "ROLE ${it.roleHint}" } ?: "ROLE —"
        }
    }

    private inner class MatchupCard(
        val root: LinearLayout,
        private val header: TextView,
        private val champions: TextView,
        private val verdict: TextView,
        private val detail: TextView
    ) {
        fun bind(matchup: DraftHudMatchup?) {
            root.visibility = if (matchup == null) View.GONE else View.VISIBLE
            matchup ?: return
            header.text = "${matchup.role.name} MATCHUP"
            champions.text = "${matchup.blueChampion}  ↔  ${matchup.redChampion}"
            verdict.text = matchup.verdict
            val prefix = if (matchup.csd15 >= 0) "+" else "−"
            detail.text = "CSD@15 $prefix%.1f · SAMPLE %d · 置信度 %s".format(abs(matchup.csd15), matchup.sampleGames, matchup.confidence)
        }
    }

    private fun pill(value: String, sp: Float, color: Int) = text(value, sp, color, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        background = panelBackground(0xD911151C.toInt(), 0x667B8799)
    }

    private fun panelBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(8).toFloat()
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Only this small window consumes touch input while the full-screen HUD remains pass-through. */
class DraftHudControlView(
    context: Context,
    private val onToggleAuto: () -> Unit,
    private val onNext: () -> Unit,
    private val onStop: () -> Unit
) : LinearLayout(context) {
    private val play = button("Ⅱ") { onToggleAuto() }
    private val step = button("›") { onNext() }
    private val close = button("×") { onStop() }
    private val stateText = text("SIM", 8f, 0xFF8CEBFF.toInt(), true)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(6), dp(5), dp(6))
        background = GradientDrawable().apply {
            setColor(0xE611151C.toInt())
            setStroke(dp(1), 0xAA8CEBFF.toInt())
            cornerRadius = dp(10).toFloat()
        }
        addView(text("RIFT", 9f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LayoutParams(dp(44), dp(22)))
        addView(stateText, LayoutParams(dp(44), dp(20)))
        addView(play, LayoutParams(dp(44), dp(38)))
        addView(step, LayoutParams(dp(44), dp(38)))
        addView(close, LayoutParams(dp(44), dp(38)))
    }

    fun render(state: DraftHudState) {
        stateText.text = "${state.step}/${state.totalSteps}"
        play.text = when {
            state.finished -> "↻"
            state.autoPlay -> "Ⅱ"
            else -> "▶"
        }
    }

    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setOnClickListener { action() }
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = sp
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
