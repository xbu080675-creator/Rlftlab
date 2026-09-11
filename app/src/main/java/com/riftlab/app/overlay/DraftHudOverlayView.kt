package com.riftlab.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** 全屏 BP 信息层。正常状态完全穿透触摸；编辑状态才允许拖动模块。 */
class DraftHudOverlayView(
    context: Context,
    private val onModuleSelected: (DraftHudModule) -> Unit
) : FrameLayout(context) {
    private val topStatus = pill("RiftLab · BP 模拟", 10f, 0xFF8CEBFF.toInt())
    private val progress = pill("0 / 10", 10f, Color.WHITE)
    private val blueCard = pickCard(blue = true)
    private val redCard = pickCard(blue = false)
    private val matchupCard = matchupCard()
    private val watermark = text("模拟数据 · BLG vs AL · 2026 LPL 第三赛段 · 第一局", 10f, 0xAAFFFFFF.toInt(), true)
    private val safeZoneGuide = text("直播画面避让区 · 可在编辑模式中调整", 10f, 0x99FFFFFF.toInt(), true).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(10), 0, 0)
        setBackgroundColor(0x2218A7C4)
        visibility = View.GONE
    }

    private val placements = mutableMapOf<DraftHudModule, DraftHudPlacement>()
    private var currentState = DraftHudState()
    private var selectedModule = DraftHudModule.BLUE_PICK
    private var editing = false
    private var lastLandscape: Boolean? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(safeZoneGuide, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { gravity = Gravity.BOTTOM })
        addModule(DraftHudModule.STATUS, topStatus, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)))
        addModule(DraftHudModule.PROGRESS, progress, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)))
        addModule(DraftHudModule.BLUE_PICK, blueCard.root, LayoutParams(dp(252), LayoutParams.WRAP_CONTENT))
        addModule(DraftHudModule.RED_PICK, redCard.root, LayoutParams(dp(252), LayoutParams.WRAP_CONTENT))
        addModule(DraftHudModule.MATCHUP, matchupCard.root, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT))
        addModule(DraftHudModule.WATERMARK, watermark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        DraftHudModule.entries.forEach { module -> attachModuleDrag(module, viewFor(module)) }
        post { ensureProfile(true) }
    }

    fun render(state: DraftHudState) {
        currentState = state
        visibility = if (state.active) View.VISIBLE else View.GONE
        if (!state.active) return
        topStatus.text = when {
            state.finished -> "RiftLab · BP 已锁定"
            state.autoPlay -> "RiftLab · BP 自动模拟"
            else -> "RiftLab · BP 手动模拟"
        }
        progress.text = "${state.step} / ${state.totalSteps}"
        blueCard.bind(state.bluePicks.lastOrNull())
        redCard.bind(state.redPicks.lastOrNull())
        matchupCard.bind(state.matchup)
        val landscape = isLandscape()
        (blueCard.root.layoutParams as LayoutParams).width = dp(if (landscape) 252 else 178)
        (redCard.root.layoutParams as LayoutParams).width = dp(if (landscape) 252 else 178)
        (matchupCard.root.layoutParams as LayoutParams).width = dp(if (landscape) 320 else 250)
        ensureProfile()
        post {
            applyAllPlacements()
            applyRuntimeVisibility()
        }
    }

    fun setEditMode(enabled: Boolean) {
        editing = enabled
        safeZoneGuide.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            ensureProfile()
            selectedModule = selectedModule.takeIf { placements[it]?.visible == true } ?: DraftHudModule.BLUE_PICK
            onModuleSelected(selectedModule)
        }
        applyRuntimeVisibility()
    }

    fun selectedModule(): DraftHudModule = selectedModule

    fun selectedPlacement(): DraftHudPlacement =
        placements[selectedModule] ?: DraftHudLayoutStore.load(context, selectedModule, isLandscape())

    fun cycleSelection(direction: Int) {
        val entries = DraftHudModule.entries
        val current = entries.indexOf(selectedModule).coerceAtLeast(0)
        selectedModule = entries[(current + direction + entries.size) % entries.size]
        onModuleSelected(selectedModule)
    }

    fun adjustSelectedScale(delta: Float) = mutateSelected { it.copy(scale = (it.scale + delta).coerceIn(0.55f, 1.45f)) }

    fun adjustSelectedAlpha(delta: Float) = mutateSelected { it.copy(alpha = (it.alpha + delta).coerceIn(0.30f, 1f)) }

    fun toggleSelectedVisibility() {
        mutateSelected { it.copy(visible = !it.visible) }
        applyRuntimeVisibility()
    }

    fun resetCurrentLayout() {
        DraftHudLayoutStore.reset(context, isLandscape())
        placements.clear()
        ensureProfile(true)
        applyRuntimeVisibility()
        onModuleSelected(selectedModule)
    }

    private fun mutateSelected(block: (DraftHudPlacement) -> DraftHudPlacement) {
        val value = block(selectedPlacement())
        placements[selectedModule] = value
        DraftHudLayoutStore.save(context, selectedModule, isLandscape(), value)
        applyPlacement(selectedModule, value)
        onModuleSelected(selectedModule)
    }

    private fun addModule(module: DraftHudModule, view: View, lp: LayoutParams) {
        lp.gravity = Gravity.TOP or Gravity.START
        view.tag = module
        addView(view, lp)
    }

    private fun attachModuleDrag(module: DraftHudModule, view: View) {
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        view.setOnTouchListener { target, event ->
            if (!editing) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectedModule = module
                    onModuleSelected(module)
                    downX = event.rawX
                    downY = event.rawY
                    startTx = target.translationX
                    startTy = target.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val point = clampTranslation(target, startTx + event.rawX - downX, startTy + event.rawY - downY)
                    target.translationX = point.first
                    target.translationY = point.second
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePosition(module, target)
                    true
                }
                else -> false
            }
        }
    }

    private fun savePosition(module: DraftHudModule, view: View) {
        if (width <= 0 || height <= 0) return
        val old = placements[module] ?: DraftHudLayoutStore.load(context, module, isLandscape())
        val value = old.copy(
            x = ((view.translationX + view.width / 2f) / width).coerceIn(0f, 1f),
            y = ((view.translationY + view.height / 2f) / height).coerceIn(0f, 1f)
        )
        placements[module] = value
        DraftHudLayoutStore.save(context, module, isLandscape(), value)
        onModuleSelected(module)
    }

    private fun ensureProfile(force: Boolean = false) {
        val landscape = isLandscape()
        if (!force && lastLandscape == landscape && placements.size == DraftHudModule.entries.size) return
        lastLandscape = landscape
        placements.clear()
        DraftHudModule.entries.forEach { placements[it] = DraftHudLayoutStore.load(context, it, landscape) }
        safeZoneGuide.layoutParams = (safeZoneGuide.layoutParams as LayoutParams).apply {
            height = if (landscape) (resources.displayMetrics.heightPixels * 0.40f).toInt()
            else (resources.displayMetrics.heightPixels * 0.28f).toInt()
            gravity = Gravity.BOTTOM
        }
        post { applyAllPlacements() }
    }

    private fun applyAllPlacements() {
        if (width <= 0 || height <= 0) return
        DraftHudModule.entries.forEach { module -> applyPlacement(module, placements[module] ?: return@forEach) }
    }

    private fun applyPlacement(module: DraftHudModule, value: DraftHudPlacement) {
        val view = viewFor(module)
        if (view.width <= 0 || view.height <= 0 || width <= 0 || height <= 0) {
            view.post { applyPlacement(module, placements[module] ?: value) }
            return
        }
        view.scaleX = value.scale
        view.scaleY = value.scale
        view.alpha = value.alpha
        val point = clampTranslation(view, value.x * width - view.width / 2f, value.y * height - view.height / 2f)
        view.translationX = point.first
        view.translationY = point.second
    }

    private fun clampTranslation(view: View, desiredX: Float, desiredY: Float): Pair<Float, Float> {
        val scale = view.scaleX.coerceAtLeast(0.01f)
        val halfW = view.width * scale / 2f
        val halfH = view.height * scale / 2f
        val centerX = (desiredX + view.width / 2f).coerceIn(halfW, (width - halfW).coerceAtLeast(halfW))
        val centerY = (desiredY + view.height / 2f).coerceIn(halfH, (height - halfH).coerceAtLeast(halfH))
        return (centerX - view.width / 2f) to (centerY - view.height / 2f)
    }

    private fun applyRuntimeVisibility() {
        if (!currentState.active) return
        DraftHudModule.entries.forEach { module ->
            val userVisible = placements[module]?.visible ?: true
            val runtimeVisible = when (module) {
                DraftHudModule.MATCHUP -> currentState.matchup != null
                DraftHudModule.WATERMARK -> isLandscape() || editing
                else -> true
            }
            viewFor(module).visibility = if (userVisible && runtimeVisible) View.VISIBLE else View.GONE
        }
        safeZoneGuide.visibility = if (editing) View.VISIBLE else View.GONE
    }

    private fun viewFor(module: DraftHudModule): View = when (module) {
        DraftHudModule.STATUS -> topStatus
        DraftHudModule.PROGRESS -> progress
        DraftHudModule.BLUE_PICK -> blueCard.root
        DraftHudModule.RED_PICK -> redCard.root
        DraftHudModule.MATCHUP -> matchupCard.root
        DraftHudModule.WATERMARK -> watermark
    }

    private fun isLandscape(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun roleName(role: DraftRole): String = when (role) {
        DraftRole.TOP -> "上路"
        DraftRole.JUG -> "打野"
        DraftRole.MID -> "中路"
        DraftRole.BOT -> "下路"
        DraftRole.SUP -> "辅助"
    }

    private fun pickCard(blue: Boolean): PickCard {
        val title = text(if (blue) "BLG · 蓝方" else "AL · 红方", 10f, if (blue) 0xFF6CEBFF.toInt() else 0xFFFF6F79.toInt(), true)
        val champion = text("等待锁定", 20f, Color.WHITE, true)
        val player = text("—", 10f, 0xFFB9C3D3.toInt())
        val version = text("版本数据 —", 10f, 0xFFE6EBF2.toInt())
        val comfort = text("选手数据 —", 10f, 0xFFE6EBF2.toInt())
        val hint = text("位置 —", 10f, 0xFF8E9AAE.toInt())
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            background = panelBackground(if (blue) 0xCC071D29.toInt() else 0xCC291015.toInt(), if (blue) 0xFF4EDCF4.toInt() else 0xFFFF5D69.toInt())
            addView(title); addView(champion); addView(player); addView(version); addView(comfort); addView(hint)
        }
        return PickCard(root, champion, player, version, comfort, hint)
    }

    private fun matchupCard(): MatchupCard {
        val summary = text("对位信息", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val detail = text("15分钟补刀差 — · 样本 —", 10f, 0xFFB9C3D3.toInt()).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(6))
            background = panelBackground(0xD911151C.toInt(), 0xAA8CEBFF.toInt())
            addView(summary); addView(detail); visibility = View.GONE
        }
        return MatchupCard(root, summary, detail)
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
            champion.text = pick?.champion ?: "等待锁定"
            player.text = pick?.let { "${it.player} · ${roleName(it.role)}" } ?: "—"
            version.text = pick?.let { "模拟版本胜率 %.1f%% · %d局".format(it.versionWinRate, it.sampleGames) } ?: "版本数据 —"
            comfort.text = pick?.let { "选手样本 %d局 · %.1f%%".format(it.playerGames, it.playerWinRate) } ?: "选手数据 —"
            hint.text = pick?.let { "位置 ${it.roleHint}" } ?: "位置 —"
        }
    }

    private inner class MatchupCard(
        val root: LinearLayout,
        private val summary: TextView,
        private val detail: TextView
    ) {
        fun bind(matchup: DraftHudMatchup?) {
            root.visibility = if (matchup == null) View.GONE else View.VISIBLE
            matchup ?: return
            summary.text = "${roleName(matchup.role)} · ${matchup.blueChampion} ↔ ${matchup.redChampion}"
            val signed = if (matchup.csd15 >= 0) "+%.1f".format(matchup.csd15) else "%.1f".format(matchup.csd15)
            detail.text = "${matchup.verdict} · 15分钟补刀差 $signed · ${matchup.sampleGames}局 · ${matchup.confidence}置信度"
        }
    }

    private fun pill(value: String, sp: Float, color: Int) = text(value, sp, color, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        background = panelBackground(0xD911151C.toInt(), 0x667B8799)
    }

    private fun panelBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(8).toFloat()
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = sp; setTextColor(color); includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** BP 模拟边缘控制面板。 */
class DraftHudControlView(
    context: Context,
    private val onToggleAuto: () -> Unit,
    private val onNext: () -> Unit,
    private val onStop: () -> Unit,
    private val onToggleEdit: () -> Unit,
    private val onPreviousModule: () -> Unit,
    private val onNextModule: () -> Unit,
    private val onScaleDown: () -> Unit,
    private val onScaleUp: () -> Unit,
    private val onAlphaDown: () -> Unit,
    private val onAlphaUp: () -> Unit,
    private val onToggleVisibility: () -> Unit,
    private val onResetLayout: () -> Unit
) : LinearLayout(context) {
    private var collapsed = true
    private val header = button("RIFT") { collapsed = !collapsed; applyCollapsedState() }
    private val stateText = text("0/10", 10f, 0xFF8CEBFF.toInt(), true)
    private val normalPanel = LinearLayout(context).apply { orientation = VERTICAL }
    private val editPanel = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
    private val play = button("▶") { onToggleAuto() }
    private val edit = button("编辑") { onToggleEdit() }
    private val selected = text("蓝方选择", 10f, Color.WHITE, true)
    private val visibilityButton = button("隐藏") { onToggleVisibility() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(5), dp(5), dp(6))
        background = GradientDrawable().apply {
            setColor(0xE611151C.toInt()); setStroke(dp(1), 0xAA8CEBFF.toInt()); cornerRadius = dp(10).toFloat()
        }
        addView(header, LayoutParams(dp(48), dp(30)))
        addView(stateText, LayoutParams(dp(48), dp(18)))
        normalPanel.addView(play, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        normalPanel.addView(button("下一步") { onNext() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        normalPanel.addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        normalPanel.addView(button("停止") { onStop() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        addView(normalPanel, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
        selected.gravity = Gravity.CENTER
        editPanel.addView(selected, LayoutParams(dp(96), dp(24)))
        editPanel.addView(twoButtons("◀", { onPreviousModule() }, "▶", { onNextModule() }))
        editPanel.addView(twoButtons("缩−", { onScaleDown() }, "缩+", { onScaleUp() }))
        editPanel.addView(twoButtons("透−", { onAlphaDown() }, "透+", { onAlphaUp() }))
        editPanel.addView(visibilityButton, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        editPanel.addView(button("重置") { onResetLayout() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        editPanel.addView(button("锁定") { onToggleEdit() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        addView(editPanel, LayoutParams(dp(100), LayoutParams.WRAP_CONTENT))
        applyCollapsedState()
    }

    fun render(state: DraftHudState, editing: Boolean, selectedModule: DraftHudModule, placement: DraftHudPlacement) {
        stateText.text = if (editing) "编辑" else "${state.step}/${state.totalSteps}"
        play.text = when { state.finished -> "↻"; state.autoPlay -> "Ⅱ"; else -> "▶" }
        normalPanel.visibility = if (!collapsed && !editing) View.VISIBLE else View.GONE
        editPanel.visibility = if (!collapsed && editing) View.VISIBLE else View.GONE
        selected.text = selectedModule.label
        visibilityButton.text = if (placement.visible) "隐藏" else "显示"
        edit.text = "编辑"
    }

    private fun applyCollapsedState() {
        normalPanel.visibility = if (collapsed) View.GONE else View.VISIBLE
        editPanel.visibility = View.GONE
        stateText.visibility = if (collapsed) View.GONE else View.VISIBLE
        header.text = if (collapsed) "RIFT" else "RIFT ‹"
        requestLayout()
    }

    private fun twoButtons(left: String, leftAction: () -> Unit, right: String, rightAction: () -> Unit) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(button(left, leftAction), LayoutParams(0, dp(28), 1f))
            addView(button(right, rightAction), LayoutParams(0, dp(28), 1f))
        }

    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value; textSize = if (value.length > 2) 10f else 16f; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER; includeFontPadding = false; setOnClickListener { action() }
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = sp; setTextColor(color); gravity = Gravity.CENTER; includeFontPadding = false
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
