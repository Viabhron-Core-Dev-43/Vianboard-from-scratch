package com.example.ime.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.example.ime.keyboard.KeyboardGeometry
import com.example.ime.keyboard.KeyboardTheme
import kotlin.math.hypot

/**
 * High-security 9-dot pattern unlock modal for VianBoard.
 *
 * Requirements:
 * 1. Height matching normal keyboard layout dynamically (onMeasure).
 * 2. 9 dots grid with intermediate jumping support and pattern verification.
 * 3. Discrete '✕' cross button in the top-right corner with 48dp touch target.
 * 4. Stealth Mode with authentic keyboard layout visually overlaid on top, with the 9 dots subtly visible.
 * 5. Mild tactile vibration feedback on every dot touched and action performed.
 */
class VianPatternUnlockView(
    context: Context,
    private val vaultType: VaultType = VaultType.SECURITY
) : View(context) {

    var onUnlockSuccess: ((VaultType) -> Unit)? = null
    var onDismissToAlpha: (() -> Unit)? = null
    var onUsePhonePinClicked: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var presentationMode = MasterPatternStore.getPresentationMode(context)
    private var bottomNavInsetPx = 0f

    // Theme & Paints
    private val theme = KeyboardTheme.loadFromPrefs(context)
    private val bgPaint = Paint().apply {
        color = theme.backgroundColor
        style = Paint.Style.FILL
    }
    private val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.textColor
        textSize = 14f * density
        textAlign = Paint.Align.LEFT
    }
    private val dotNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64748B.toInt() // Slate 500
        style = Paint.Style.FILL
    }
    private val dotSubtlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Subtly visible for stealth mode: 35% opacity slate
        color = 0x5964748B
        style = Paint.Style.FILL
    }
    private val dotSubtleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40000000
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val dotSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt() // Sky 600
        style = Paint.Style.FILL
    }
    private val dotSuccessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10B981.toInt() // Emerald 500
        style = Paint.Style.FILL
    }
    private val dotErrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEF4444.toInt() // Red 500
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0284C7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x330284C7
        style = Paint.Style.FILL
    }

    // Keycap paints for authentic keyboard visual overlay
    private val keycapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.keyBackgroundColor
        style = Paint.Style.FILL
    }
    private val keycapActionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.actionKeyColor
        style = Paint.Style.FILL
    }
    private val keyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.keyBottomBevelColor
        style = Paint.Style.FILL
    }
    private val actionKeyBevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.actionKeyBevelColor
        style = Paint.Style.FILL
    }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.textColor
        textSize = 19f * density
        textAlign = Paint.Align.CENTER
    }
    private val keyActionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.textColor
        textSize = 13.5f * density
        textAlign = Paint.Align.CENTER
    }
    private val decoyToolbarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (theme.textColor and 0x00FFFFFF) or 0x80000000.toInt()
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }

    // Top action bar
    private val topBarHeightPx = (theme.toolbarHeightDp * density).coerceAtLeast(40f * density)
    private val closeButtonRect = RectF()
    private val modeToggleRect = RectF()
    private val phonePinButtonRect = RectF()

    // 3x3 Dot Matrix state
    private val dotCenters = Array(9) { floatArrayOf(0f, 0f) }
    private val selectedDots = mutableListOf<Int>()
    private var currentTouchX = 0f
    private var currentTouchY = 0f
    private var isTouching = false
    private var unlockState: UnlockState = UnlockState.IDLE

    // Keyboard visual overlay keys
    private data class OverlaidKey(
        val label: String,
        val isAction: Boolean,
        val bounds: RectF
    )
    private val overlaidKeys = mutableListOf<OverlaidKey>()

    private enum class UnlockState {
        IDLE,
        TOUCHING,
        SUCCESS,
        ERROR
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val navInsets = insets.getInsets(WindowInsets.Type.navigationBars())
                bottomNavInsetPx = navInsets.bottom.toFloat()
            } else {
                @Suppress("DEPRECATION")
                bottomNavInsetPx = insets.systemWindowInsetBottom.toFloat()
            }
            requestLayout()
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = 4
        val verticalGapPx = theme.verticalGapDp * density
        val rowsTotalHeight = theme.keyHeightDp * rowCount * density
        val totalVerticalGaps = (rowCount - 1) * verticalGapPx
        val toolbarHeight = theme.toolbarHeightDp * density
        val paddingVPx = KeyboardGeometry.HELIBOARD_PADDING_DP * density

        // Exact calculated keyboard height matching VianKeyboardView: toolbar + verticalGap + rows + gaps + padding + insets
        val calculatedHeight = (toolbarHeight + verticalGapPx + rowsTotalHeight + totalVerticalGaps + (paddingVPx * 2f) + bottomNavInsetPx).toInt()
        val finalHeight = calculatedHeight.coerceAtLeast((220 * density).toInt())

        val specMode = MeasureSpec.getMode(heightMeasureSpec)
        val finalHeightResolved = if (specMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            finalHeight
        }
        setMeasuredDimension(width, finalHeightResolved)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout(w, h)
    }

    private fun computeLayout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return

        // 1. Top-Right Corner Cross Button: minimum 48dp x 48dp touch region
        val closeBtnWidth = (48f * density).coerceAtLeast(topBarHeightPx)
        val closeBtnHeight = topBarHeightPx
        closeButtonRect.set(w - closeBtnWidth, 0f, w.toFloat(), closeBtnHeight)

        // 2. Mode Toggle button & PIN button in header
        val pinBtnWidth = 76f * density
        val pinBtnHeight = 28f * density
        val pinTop = (topBarHeightPx - pinBtnHeight) / 2f
        phonePinButtonRect.set(
            closeButtonRect.left - pinBtnWidth - (6f * density),
            pinTop,
            closeButtonRect.left - (6f * density),
            pinTop + pinBtnHeight
        )

        val toggleWidth = 78f * density
        modeToggleRect.set(
            phonePinButtonRect.left - toggleWidth - (6f * density),
            pinTop,
            phonePinButtonRect.left - (6f * density),
            pinTop + pinBtnHeight
        )

        // 3. 3x3 Dot Matrix Coordinates (Covers the interaction area evenly)
        val gridTop = topBarHeightPx + (8f * density)
        val gridBottom = h - bottomNavInsetPx - (10f * density)
        val gridHeight = gridBottom - gridTop
        val gridWidth = w.toFloat()

        val colPositions = floatArrayOf(gridWidth * 0.22f, gridWidth * 0.50f, gridWidth * 0.78f)
        val rowPositions = floatArrayOf(
            gridTop + gridHeight * 0.20f,
            gridTop + gridHeight * 0.50f,
            gridTop + gridHeight * 0.80f
        )

        var idx = 0
        for (r in 0..2) {
            for (c in 0..2) {
                dotCenters[idx][0] = colPositions[c]
                dotCenters[idx][1] = rowPositions[r]
                idx++
            }
        }

        // 4. Keyboard Visual Overlay Layout (Authentic 4-row HeliBoard geometry)
        overlaidKeys.clear()
        val kbTop = topBarHeightPx + (theme.verticalGapDp * density)
        val availableKbHeight = h - kbTop - bottomNavInsetPx - (KeyboardGeometry.HELIBOARD_PADDING_DP * density)
        val rowHeight = availableKbHeight / 4f
        val gapH = theme.horizontalGapDp * density
        val gapV = theme.verticalGapDp * density

        // Row 1: Q W E R T Y U I O P (10 keys)
        val r1Chars = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        val r1KeyWidth = (w - (gapH * 11)) / 10f
        for (i in r1Chars.indices) {
            val left = gapH + i * (r1KeyWidth + gapH)
            val top = kbTop + (gapV / 2f)
            val rect = RectF(left, top, left + r1KeyWidth, top + rowHeight - gapV)
            overlaidKeys.add(OverlaidKey(r1Chars[i], false, rect))
        }

        // Row 2: A S D F G H J K L (9 keys, inset)
        val r2Chars = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
        val r2SideMargin = (r1KeyWidth / 2f) + gapH
        val r2KeyWidth = (w - (r2SideMargin * 2) - (gapH * 8)) / 9f
        for (i in r2Chars.indices) {
            val left = r2SideMargin + i * (r2KeyWidth + gapH)
            val top = kbTop + rowHeight + (gapV / 2f)
            val rect = RectF(left, top, left + r2KeyWidth, top + rowHeight - gapV)
            overlaidKeys.add(OverlaidKey(r2Chars[i], false, rect))
        }

        // Row 3: Shift, Z X C V B N M, Del
        val r3Chars = listOf("Z", "X", "C", "V", "B", "N", "M")
        val functionalWidth = r1KeyWidth * 1.35f
        val r3RemainingWidth = w - (functionalWidth * 2) - (gapH * 8)
        val r3KeyWidth = r3RemainingWidth / 7f

        val shiftRect = RectF(gapH, kbTop + (rowHeight * 2) + (gapV / 2f), gapH + functionalWidth, kbTop + (rowHeight * 3) - (gapV / 2f))
        overlaidKeys.add(OverlaidKey("⇧", true, shiftRect))

        for (i in r3Chars.indices) {
            val left = gapH + functionalWidth + gapH + i * (r3KeyWidth + gapH)
            val top = kbTop + (rowHeight * 2) + (gapV / 2f)
            val rect = RectF(left, top, left + r3KeyWidth, top + rowHeight - gapV)
            overlaidKeys.add(OverlaidKey(r3Chars[i], false, rect))
        }

        val delLeft = w - gapH - functionalWidth
        val delRect = RectF(delLeft, kbTop + (rowHeight * 2) + (gapV / 2f), delLeft + functionalWidth, kbTop + (rowHeight * 3) - (gapV / 2f))
        overlaidKeys.add(OverlaidKey("⌫", true, delRect))

        // Row 4: ?123, comma, space, period, enter
        val r4Top = kbTop + (rowHeight * 3) + (gapV / 2f)
        val r4Bottom = kbTop + (rowHeight * 4) - (gapV / 2f)
        val symWidth = functionalWidth
        val enterWidth = functionalWidth * 1.2f
        val smallKeyWidth = r1KeyWidth

        overlaidKeys.add(OverlaidKey("?123", true, RectF(gapH, r4Top, gapH + symWidth, r4Bottom)))
        val commaLeft = gapH + symWidth + gapH
        overlaidKeys.add(OverlaidKey(",", true, RectF(commaLeft, r4Top, commaLeft + smallKeyWidth, r4Bottom)))
        val enterLeft = w - gapH - enterWidth
        overlaidKeys.add(OverlaidKey("↵", true, RectF(enterLeft, r4Top, enterLeft + enterWidth, r4Bottom)))
        val periodLeft = enterLeft - gapH - smallKeyWidth
        overlaidKeys.add(OverlaidKey(".", true, RectF(periodLeft, r4Top, periodLeft + smallKeyWidth, r4Bottom)))
        val spaceLeft = commaLeft + smallKeyWidth + gapH
        val spaceRight = periodLeft - gapH
        overlaidKeys.add(OverlaidKey("", false, RectF(spaceLeft, r4Top, spaceRight, r4Bottom)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (presentationMode == PatternPresentationMode.KEYBOARD_DISGUISE) {
            drawStealthKeyboardOverlay(canvas)
        } else {
            drawStandardGrid(canvas)
        }
    }

    private fun drawStandardGrid(canvas: Canvas) {
        // 1. Header Toolbar
        val title = if (vaultType == VaultType.SECURITY) "🔒 Security Vault" else "🛡️ Privacy Vault"
        headerTextPaint.color = theme.textColor
        headerTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(title, 16f * density, topBarHeightPx * 0.62f, headerTextPaint)

        // PIN Button
        val pinBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.actionKeyColor
            style = Paint.Style.FILL
        }
        val pinTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textColor
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(phonePinButtonRect, 6f * density, 6f * density, pinBgPaint)
        canvas.drawText("📱 PIN", phonePinButtonRect.centerX(), phonePinButtonRect.centerY() + (4f * density), pinTextPaint)

        // Stealth Mode Toggle
        val toggleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.actionKeyColor
            style = Paint.Style.FILL
        }
        val toggleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textColor
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(modeToggleRect, 6f * density, 6f * density, toggleBgPaint)
        canvas.drawText("⌨ Stealth", modeToggleRect.centerX(), modeToggleRect.centerY() + (4f * density), toggleTextPaint)

        // Top-Right Cross Button (✕)
        val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textColor
            textSize = 20f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("✕", closeButtonRect.centerX(), closeButtonRect.centerY() + (7f * density), closeTextPaint)

        // 2. Connecting Lines
        drawConnectingLines(canvas, isStealth = false)

        // 3. 9 Dots
        val dotRadius = 9.5f * density
        val haloRadius = 24f * density

        for (i in 0 until 9) {
            val cx = dotCenters[i][0]
            val cy = dotCenters[i][1]
            val isSelected = selectedDots.contains(i)

            if (isSelected) {
                val curHaloPaint = when (unlockState) {
                    UnlockState.SUCCESS -> Paint(haloPaint).apply { color = 0x3310B981 }
                    UnlockState.ERROR -> Paint(haloPaint).apply { color = 0x33EF4444.toInt() }
                    else -> haloPaint
                }
                canvas.drawCircle(cx, cy, haloRadius, curHaloPaint)

                val curDotPaint = when (unlockState) {
                    UnlockState.SUCCESS -> dotSuccessPaint
                    UnlockState.ERROR -> dotErrorPaint
                    else -> dotSelectedPaint
                }
                canvas.drawCircle(cx, cy, dotRadius * 1.3f, curDotPaint)
            } else {
                canvas.drawCircle(cx, cy, dotRadius, dotNormalPaint)
            }
        }
    }

    private fun drawStealthKeyboardOverlay(canvas: Canvas) {
        // 1. Top Decoy Header Strip
        // Discrete mode switch button on left
        val toggleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.actionKeyColor
            style = Paint.Style.FILL
        }
        val toggleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = theme.textColor
            textSize = 11f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRoundRect(modeToggleRect, 6f * density, 6f * density, toggleBgPaint)
        canvas.drawText("☷ Grid", modeToggleRect.centerX(), modeToggleRect.centerY() + (4f * density), toggleTextPaint)

        // Decoy suggestions placeholder in center
        val decoyCenterY = topBarHeightPx * 0.62f
        canvas.drawText("Suggestions", (modeToggleRect.left) / 2f, decoyCenterY, decoyToolbarTextPaint)

        // Top-Right Discrete Cross Button (✕)
        val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (theme.textColor and 0x00FFFFFF) or 0x99000000.toInt()
            textSize = 18f * density
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("✕", closeButtonRect.centerX(), closeButtonRect.centerY() + (6f * density), closeTextPaint)

        // 2. Draw Keycaps Overlay (Authentic HeliBoard appearance)
        val keyRadius = theme.keyCornerRadiusDp * density
        val bevelInset = 1f * density

        for (k in overlaidKeys) {
            val isAction = k.isAction
            val bg = if (isAction) keycapActionPaint else keycapPaint
            val bevel = if (isAction) actionKeyBevelPaint else keyBevelPaint

            // Bottom bevel
            canvas.drawRoundRect(k.bounds, keyRadius, keyRadius, bevel)

            // Top keycap surface
            val surface = RectF(k.bounds.left, k.bounds.top, k.bounds.right, k.bounds.bottom - bevelInset)
            canvas.drawRoundRect(surface, keyRadius, keyRadius, bg)

            // Key label
            if (k.label.isNotEmpty()) {
                val tp = if (isAction) keyActionTextPaint else keyTextPaint
                val textY = surface.centerY() + (tp.textSize / 3f)
                canvas.drawText(k.label, surface.centerX(), textY, tp)
            }
        }

        // 3. Connecting Lines across stealth keyboard
        drawConnectingLines(canvas, isStealth = true)

        // 4. Subtly visible 9 Dots overlaid on top of keyboard
        val subtleDotRadius = 8f * density
        for (i in 0 until 9) {
            val cx = dotCenters[i][0]
            val cy = dotCenters[i][1]
            val isSelected = selectedDots.contains(i)

            if (isSelected) {
                val curDotPaint = when (unlockState) {
                    UnlockState.SUCCESS -> dotSuccessPaint
                    UnlockState.ERROR -> dotErrorPaint
                    else -> dotSelectedPaint
                }
                canvas.drawCircle(cx, cy, subtleDotRadius * 1.3f, curDotPaint)
            } else {
                // In stealth mode: dots are subtly visible markers
                canvas.drawCircle(cx, cy, subtleDotRadius, dotSubtlePaint)
                canvas.drawCircle(cx, cy, subtleDotRadius, dotSubtleRingPaint)
            }
        }
    }

    private fun drawConnectingLines(canvas: Canvas, isStealth: Boolean) {
        if (selectedDots.isEmpty()) return

        val curLinePaint = when (unlockState) {
            UnlockState.SUCCESS -> dotSuccessPaint
            UnlockState.ERROR -> dotErrorPaint
            else -> if (isStealth) {
                Paint(linePaint).apply { color = 0xAA0284C7.toInt() }
            } else {
                linePaint
            }
        }

        val path = Path()
        val first = dotCenters[selectedDots[0]]
        path.moveTo(first[0], first[1])

        for (i in 1 until selectedDots.size) {
            val pt = dotCenters[selectedDots[i]]
            path.lineTo(pt[0], pt[1])
        }

        if (isTouching && unlockState == UnlockState.TOUCHING) {
            path.lineTo(currentTouchX, currentTouchY)
        }

        canvas.drawPath(path, curLinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Top-Right Discrete Cross button (✕)
                if (closeButtonRect.contains(x, y)) {
                    triggerMildHaptic()
                    onDismissToAlpha?.invoke()
                    return true
                }

                // Phone PIN button (Standard Mode only)
                if (presentationMode == PatternPresentationMode.STANDARD_GRID && phonePinButtonRect.contains(x, y)) {
                    triggerMildHaptic()
                    onUsePhonePinClicked?.invoke()
                    return true
                }

                // Mode toggle (Standard vs Stealth)
                if (modeToggleRect.contains(x, y)) {
                    triggerMildHaptic()
                    presentationMode = if (presentationMode == PatternPresentationMode.STANDARD_GRID) {
                        PatternPresentationMode.KEYBOARD_DISGUISE
                    } else {
                        PatternPresentationMode.STANDARD_GRID
                    }
                    MasterPatternStore.setPresentationMode(context, presentationMode)
                    resetState()
                    invalidate()
                    return true
                }

                handleTouchDown(x, y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                handleTouchMove(x, y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleTouchRelease()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchDown(x: Float, y: Float) {
        if (unlockState != UnlockState.IDLE) return
        resetState()
        isTouching = true
        currentTouchX = x
        currentTouchY = y

        val dotIdx = findDotNear(x, y)
        if (dotIdx != null) {
            selectedDots.add(dotIdx)
            unlockState = UnlockState.TOUCHING
            triggerMildHaptic()
        }
        invalidate()
    }

    private fun handleTouchMove(x: Float, y: Float) {
        if (!isTouching) return
        currentTouchX = x
        currentTouchY = y

        val dotIdx = findDotNear(x, y)
        if (dotIdx != null && !selectedDots.contains(dotIdx)) {
            // Check intermediate dot jumping (e.g., from 0 to 2 passes through 1)
            if (selectedDots.isNotEmpty()) {
                val lastIdx = selectedDots.last()
                val intermediate = getIntermediateDot(lastIdx, dotIdx)
                if (intermediate != null && !selectedDots.contains(intermediate)) {
                    selectedDots.add(intermediate)
                    triggerMildHaptic()
                }
            }

            selectedDots.add(dotIdx)
            unlockState = UnlockState.TOUCHING
            triggerMildHaptic()
        }
        invalidate()
    }

    private fun handleTouchRelease() {
        if (!isTouching) return
        isTouching = false

        if (selectedDots.size < 3) {
            resetState()
            invalidate()
            return
        }

        // Verify pattern with MasterPatternStore
        val isVerified = MasterPatternStore.verifyPattern(context, selectedDots, vaultType)
        if (isVerified) {
            unlockState = UnlockState.SUCCESS
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            invalidate()
            postDelayed({
                onUnlockSuccess?.invoke(vaultType)
            }, 180L)
        } else {
            unlockState = UnlockState.ERROR
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            invalidate()
            postDelayed({
                resetState()
                invalidate()
            }, 400L)
        }
    }

    private fun triggerMildHaptic() {
        // Mild tactile vibration pulse on dot interaction
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun findDotNear(x: Float, y: Float): Int? {
        val touchThreshold = 38f * density
        for (i in 0 until 9) {
            val dist = hypot(x - dotCenters[i][0], y - dotCenters[i][1])
            if (dist <= touchThreshold) {
                return i
            }
        }
        return null
    }

    private fun getIntermediateDot(from: Int, to: Int): Int? {
        val r1 = from / 3; val c1 = from % 3
        val r2 = to / 3; val c2 = to % 3

        if ((r1 == r2 && kotlin.math.abs(c1 - c2) == 2) ||
            (c1 == c2 && kotlin.math.abs(r1 - r2) == 2) ||
            (kotlin.math.abs(r1 - r2) == 2 && kotlin.math.abs(c1 - c2) == 2)
        ) {
            val midR = (r1 + r2) / 2
            val midC = (c1 + c2) / 2
            return midR * 3 + midC
        }
        return null
    }

    private fun resetState() {
        selectedDots.clear()
        unlockState = UnlockState.IDLE
        isTouching = false
    }
}
