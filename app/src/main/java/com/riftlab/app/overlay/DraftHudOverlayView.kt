package com.riftlab.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Full-screen visual layer for draft intelligence.
 *
 * Normal/locked mode is hosted by a FLAG_NOT_TOUCHABLE window so the video app underneath keeps
 * every tap/swipe. Edit mode temporarily makes only this HUD window touchable so users can drag
 * modules. Leaving edit mode restores full pass-through immediately.
 */
class DraftHudOverlayView(
    context: Context,
    private val onModuleSelected: (DraftHudModule) -> Unit
) : FrameLayout(context) {
    private val topStatus = pill("RIFTSCREEN · DRAFT SIM", 10f, 0xFF8CEBFF.toInt())
    private val progress = pill("0 / 10", 9f, Color.WHITE)
    private val blueCard = pickCard(blue = true)
    private val redCard = pickCard(blue = false)
    private val matchupCard = matchupCard()
    private val watermark = text("SIMULATION · TEST FIXTURE · 非真实赛事统计", 9f, 0xAAFFFFFF.toInt(), bold = true)
    private val safeZoneGuide = text("LPL BROADCAST SAFE ZONE · 默认避让，可手动覆盖", 10f, 0x99FFFFFF.toInt(), bold = true).apply {
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
    private var lockedStatusVisible = true
    private val hideLockedStatus = Runnable {
        if (currentState.active && currentState.finished && !editing) {
            lockedStatusVisible = false
            applyRuntimeVisibility()
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(safeZoneGuide, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            gravity = Gravity.BOTTOM
        })
        addModule(DraftHudModule.STATUS, topStatus, LayoutParams(LayoutParams.WRAP_CONTENT, dp(30)))
        addModule(DraftHudModule.PROGRESS, progress, LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)))
        addModule(DraftHudModule.BLUE_PICK, blueCard.root, LayoutParams(dp(252), LayoutParams.WRAP_CONTENT))
        addModule(DraftHudModule.RED_PICK, redCard.root, LayoutParams(dp(252), LayoutParams.WRAP_CONTENT))
        addModule(DraftHudModule.MATCHUP, matchupCard.root, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT))
        addModule(DraftHudModule.WATERMARK, watermark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        DraftHudModule.entries.forEach { module -> attachModuleDrag(module, viewFor(module)) }
        post { ensureProfile(force = true) }
    }

    fun render(state: DraftHudState) {
        val enteredFinished = state.finished && !currentState.finished
        currentState = state
        visibility = if (state.active) View.VISIBLE else View.GONE
        if (!state.active) {
            removeCallbacks(hideLockedStatus)
            lockedStatusVisible = true
            return
        }

        topStatus.text = when {
            state.finished -> "RIFTSCREEN · DRAFT LOCKED"
            state.autoPlay -> "RIFTSCREEN · DRAFT SIM · AUTO"
            else -> "RIFTSCREEN · DRAFT SIM · MANUAL"
        }
        progress.text = "${state.step} / ${state.totalSteps}"

        if (state.finished && enteredFinished) {
            showLockedStatusBriefly()
        } else if (!state.finished) {
            removeCallbacks(hideLockedStatus)
            lockedStatusVisible = true
        }

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
            removeCallbacks(hideLockedStatus)
            lockedStatusVisible = true
            ensureProfile()
            selectedModule = selectedModule.takeIf { placements[it]?.visible == true } ?: DraftHudModule.BLUE_PICK
            onModuleSelected(selectedModule)
        } else if (currentState.finished) {
            showLockedStatusBriefly()
        }
        applyRuntimeVisibility()
    }

    fun isEditing(): Boolean = editing

    fun selectedModule(): DraftHudModule = selectedModule

    fun selectedPlacement(): DraftHudPlacement =
        placements[selectedModule] ?: DraftHudLayoutStore.load(context, selectedModule, isLandscape())

    fun cycleSelection(direction: Int) {
        val entries = DraftHudModule.entries
        val current = entries.indexOf(selectedModule).coerceAtLeast(0)
        selectedModule = entries[(current + direction + entries.size) % entries.size]
        onModuleSelected(selectedModule)
    }

    fun adjustSelectedScale(delta: Float) {
        mutateSelected { it.copy(scale = (it.scale + delta).coerceIn(0.55f, 1.45f)) }
    }

    fun adjustSelectedAlpha(delta: Float) {
        mutateSelected { it.copy(alpha = (it.alpha + delta).coerceIn(0.30f, 1f)) }
    }

    fun toggleSelectedVisibility() {
        mutateSelected { it.copy(visible = !it.visible) }
        applyRuntimeVisibility()
    }

    fun resetCurrentLayout() {
        DraftHudLayoutStore.reset(context, isLandscape())
        placements.clear()
        ensureProfile(force = true)
        applyRuntimeVisibility()
        onModuleSelected(selectedModule)
    }

    private fun showLockedStatusBriefly() {
        removeCallbacks(hideLockedStatus)
        lockedStatusVisible = true
        applyRuntimeVisibility()
        postDelayed(hideLockedStatus, 1_800L)
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
                    val desiredX = startTx + event.rawX - downX
                    val desiredY = startTy + event.rawY - downY
                    val clamped = clampTranslation(target, desiredX, desiredY)
                    target.translationX = clamped.first
                    target.translationY = clamped.second
                    true
                }
                MotionEvent.ACTION_UP -> {
                    savePosition(module, target)
                    target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
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
        val centerX = (view.translationX + view.width / 2f) / width.toFloat()
        val centerY = (view.translationY + view.height / 2f) / height.toFloat()
        val value = old.copy(x = centerX.coerceIn(0f, 1f), y = centerY.coerceIn(0f, 1f))
        placements[module] = value
        DraftHudLayoutStore.save(context, module, isLandscape(), value)
        onModuleSelected(module)
    }

    private fun ensureProfile(force: Boolean = false) {
        val landscape = isLandscape()
        if (!force && lastLandscape == landscape && placements.size == DraftHudModule.entries.size) return
        lastLandscape = landscape
        placements.clear()
        DraftHudModule.entries.forEach { module ->
            placements[module] = DraftHudLayoutStore.load(context, module, landscape)
        }
        safeZoneGuide.layoutParams = (safeZoneGuide.layoutParams as LayoutParams).apply {
            height = if (landscape) (height.coerceAtLeast(resources.displayMetrics.heightPixels) * 0.40f).toInt()
            else (height.coerceAtLeast(resources.displayMetrics.heightPixels) * 0.28f).toInt()
            gravity = Gravity.BOTTOM
        }
        post { applyAllPlacements() }
    }

    private fun applyAllPlacements() {
        if (width <= 0 || height <= 0) return
        DraftHudModule.entries.forEach { module ->
            applyPlacement(module, placements[module] ?: return@forEach)
        }
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
        val desiredX = value.x * width - view.width / 2f
        val desiredY = value.y * height - view.height / 2f
        val clamped = clampTranslation(view, desiredX, desiredY)
        view.translationX = clamped.first
        view.translationY = clamped.second
    }

    private fun clampTranslation(view: View, desiredX: Float, desiredY: Float): Pair<Float, Float> {
        val scale = view.scaleX.coerceAtLeast(0.01f)
        val scaledWidth = view.width * scale
        val scaledHeight = view.height * scale
        val halfW = scaledWidth / 2f
        val halfH = scaledHeight / 2f
        val desiredCenterX = desiredX + view.width / 2f
        val desiredCenterY = desiredY + view.height / 2f
        val centerX = desiredCenterX.coerceIn(halfW, (width - halfW).coerceAtLeast(halfW))
        val centerY = desiredCenterY.coerceIn(halfH, (height - halfH).coerceAtLeast(halfH))
        return (centerX - view.width / 2f) to (centerY - view.height / 2f)
    }

    private fun applyRuntimeVisibility() {
        if (!currentState.active) return
        val landscape = isLandscape()
        DraftHudModule.entries.forEach { module ->
            val userVisible = placements[module]?.visible ?: true
            val runtimeVisible = when (module) {
                DraftHudModule.STATUS -> editing || !currentState.finished || lockedStatusVisible
                DraftHudModule.MATCHUP -> currentState.matchup != null
                DraftHudModule.WATERMARK -> landscape || editing
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

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
        val summary = text("MATCHUP", 11f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val detail = text("CSD@15 — · SAMPLE —", 9f, 0xFFB9C3D3.toInt()).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(6))
            background = panelBackground(0xD911151C.toInt(), 0xAA8CEBFF.toInt())
            addView(summary)
            addView(detail)
            visibility = View.GONE
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
            player.text = pick?.let { "${it.player} · ${it.role.name}" } ?: "—"
            version.text = pick?.let { "SIM WR %.1f%% · %d局".format(it.versionWinRate, it.sampleGames) } ?: "SIM WR —"
            comfort.text = pick?.let { "PLAYER %d局 · %.1f%%".format(it.playerGames, it.playerWinRate) } ?: "PLAYER —"
            hint.text = pick?.let { "ROLE ${it.roleHint}" } ?: "ROLE —"
        }
    }

    private inner class MatchupCard(
        val root: LinearLayout,
        private val summary: TextView,
        private val detail: TextView
    ) {
        fun bind(matchup: DraftHudMatchup?) {
            matchup ?: return
            summary.text = "${matchup.role.name} · ${matchup.blueChampion} ↔ ${matchup.redChampion}"
            val prefix = if (matchup.csd15 >= 0) "+" else "−"
            detail.text = "%s · CSD@15 %s%.1f · %d局 · %s置信度".format(
                matchup.verdict,
                prefix,
                abs(matchup.csd15),
                matchup.sampleGames,
                matchup.confidence
            )
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

    override fun onDetachedFromWindow() {
        removeCallbacks(hideLockedStatus)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Small edge dock. Collapsed by default; expanded controls never make the normal HUD touchable. */
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
    private val stateText = text("0/10", 8f, 0xFF8CEBFF.toInt(), true)
    private val normalPanel = LinearLayout(context).apply { orientation = VERTICAL }
    private val editPanel = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE }
    private val play = button("▶") { onToggleAuto() }
    private val edit = button("EDIT") { onToggleEdit() }
    private val selected = text("蓝方 Pick", 8f, Color.WHITE, true)
    private val visibility = button("SHOW") { onToggleVisibility() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(5), dp(5), dp(6))
        background = GradientDrawable().apply {
            setColor(0xE611151C.toInt())
            setStroke(dp(1), 0xAA8CEBFF.toInt())
            cornerRadius = dp(10).toFloat()
        }

        addView(header, LayoutParams(dp(48), dp(30)))
        addView(stateText, LayoutParams(dp(48), dp(18)))

        normalPanel.addView(play, LayoutParams(LayoutParams.MATCH_PARENT, dp(34)))
        normalPanel.addView(button("NEXT") { onNext() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        normalPanel.addView(edit, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        normalPanel.addView(button("×") { onStop() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
        addView(normalPanel, LayoutParams(dp(64), LayoutParams.WRAP_CONTENT))

        selected.gravity = Gravity.CENTER
        editPanel.addView(selected, LayoutParams(dp(92), dp(24)))
        editPanel.addView(twoButtons("◀", { onPreviousModule() }, "▶", { onNextModule() }))
        editPanel.addView(twoButtons("S−", { onScaleDown() }, "S+", { onScaleUp() }))
        editPanel.addView(twoButtons("A−", { onAlphaDown() }, "A+", { onAlphaUp() }))
        editPanel.addView(visibility, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        editPanel.addView(button("RESET") { onResetLayout() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        editPanel.addView(button("LOCK") { onToggleEdit() }, LayoutParams(LayoutParams.MATCH_PARENT, dp(30)))
        addView(editPanel, LayoutParams(dp(96), LayoutParams.WRAP_CONTENT))

        applyCollapsedState()
    }

    fun render(
        state: DraftHudState,
        editing: Boolean,
        selectedModule: DraftHudModule,
        placement: DraftHudPlacement
    ) {
        stateText.text = if (editing) "EDIT" else "${state.step}/${state.totalSteps}"
        play.text = when {
            state.finished -> "↻"
            state.autoPlay -> "Ⅱ"
            else -> "▶"
        }
        normalPanel.visibility = if (!collapsed && !editing) View.VISIBLE else View.GONE
        editPanel.visibility = if (!collapsed && editing) View.VISIBLE else View.GONE
        selected.text = selectedModule.label
        visibility.text = if (placement.visible) "HIDE" else "SHOW"
        edit.text = "EDIT"
    }

    private fun applyCollapsedState() {
        normalPanel.visibility = if (collapsed) View.GONE else View.VISIBLE
        editPanel.visibility = View.GONE
        stateText.visibility = if (collapsed) View.GONE else View.VISIBLE
        header.text = if (collapsed) "RIFT" else "RIFT ‹"
        requestLayout()
    }

    private fun twoButtons(left: String, leftAction: () -> Unit, right: String, rightAction: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(button(left, leftAction), LayoutParams(0, dp(28), 1f))
            addView(button(right, rightAction), LayoutParams(0, dp(28), 1f))
        }

    private fun button(value: String, action: () -> Unit) = TextView(context).apply {
        text = value
        textSize = if (value.length > 2) 9f else 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        includeFontPadding = false
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