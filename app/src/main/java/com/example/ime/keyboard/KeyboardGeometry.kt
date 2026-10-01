package com.example.ime.keyboard

import android.graphics.Rect
import android.graphics.RectF
import com.example.ime.toolbar.ToolbarTool

/**
 * Immutable, lightweight cached geometry model for VianBoard.
 *
 * Calculated once when dimensions, window insets, orientation, or layout configuration change.
 * Retained and reused across all draw frames (onDraw) and touch hit testing (onTouchEvent).
 *
 * Establishes the authentic HeliBoard geometry reference:
 * - Suggestion / toolbar strip height: 40dp
 * - Edge / expand control: 36dp x 36dp square, vertically centered within the 40dp strip
 * - Toolbar action buttons: 36dp x 36dp square, vertically centered within the 40dp strip
 * - Icons centered precisely within their button areas
 * - 3-Slot Suggestion Bar: equal 1/3 slot widths across available middle area with vertical hairline dividers
 */
class KeyboardGeometry(
    val width: Float,
    val height: Float,
    val density: Float,
    val bottomInsetPx: Float,
    val mode: KeyboardMode,
    val keyHeightDp: Float,
    val horizontalGapDp: Float,
    val verticalGapDp: Float,
    val pinnedToolsCount: Int,
    val expandedToolsCount: Int,

    // 1. Toolbar Bounds & Controls
    val toolbarBounds: RectF,
    val anchorKeyBounds: RectF,
    val anchorIconBounds: Rect,

    // 2. Collapsed Toolbar (3-Slot Suggestion Bar)
    val suggestionSlotBounds: List<RectF>, // [0] = Left, [1] = Center, [2] = Right
    val divider1X: Float,
    val divider2X: Float,
    val dividerTopY: Float,
    val dividerBottomY: Float,
    val centerDotsY: Float,
    val centerDotRadius: Float,
    val centerDotSpacing: Float,

    // 3. Expanded Toolbar (Scrollable Tools Tray)
    val toolbarScrollBounds: RectF,
    val expandedToolBounds: List<RectF>,
    val expandedIconBounds: List<Rect>,
    val maxToolbarScrollOffset: Float,

    // 4. Pinned Tools (Docked right edge)
    val pinnedToolBounds: List<RectF>,
    val pinnedIconBounds: List<Rect>,

    // 5. Keyboard Grid & Keys
    val keyboardBounds: RectF,
    val rowBounds: List<RectF>,
    val keyBoundsList: List<RectF>
) {
    /**
     * Checks if this cached geometry is still valid for the given parameters.
     */
    fun isValid(
        w: Float,
        h: Float,
        dens: Float,
        bottomInset: Float,
        currentMode: KeyboardMode,
        theme: KeyboardTheme,
        pinnedCount: Int,
        expandedCount: Int
    ): Boolean {
        return width == w &&
                height == h &&
                density == dens &&
                bottomInsetPx == bottomInset &&
                mode == currentMode &&
                keyHeightDp == theme.keyHeightDp &&
                horizontalGapDp == theme.horizontalGapDp &&
                verticalGapDp == theme.verticalGapDp &&
                pinnedToolsCount == pinnedCount &&
                expandedToolsCount == expandedCount
    }

    companion object {
        const val HELIBOARD_CONTROL_SIZE_DP = 36f
        const val HELIBOARD_CHEVRON_SIZE_DP = 20f
        const val HELIBOARD_ICON_SIZE_DP = 20f
        const val HELIBOARD_SPACING_DP = 6f
        const val HELIBOARD_PADDING_DP = 4f

        private fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
            val r = RectF()
            r.left = left
            r.top = top
            r.right = right
            r.bottom = bottom
            return r
        }

        private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect {
            val r = Rect()
            r.left = left
            r.top = top
            r.right = right
            r.bottom = bottom
            return r
        }

        fun calculate(
            width: Float,
            height: Float,
            density: Float,
            theme: KeyboardTheme,
            mode: KeyboardMode,
            pinnedTools: List<ToolbarTool>,
            expandedTools: List<ToolbarTool>,
            bottomInsetPx: Float,
            rowDefinitions: List<List<KeyData>>
        ): KeyboardGeometry {
            val paddingHPx = HELIBOARD_PADDING_DP * density
            val paddingVPx = HELIBOARD_PADDING_DP * density
            val spacingPx = HELIBOARD_SPACING_DP * density
            val controlSizePx = HELIBOARD_CONTROL_SIZE_DP * density
            val toolbarHeightPx = theme.toolbarHeightDp * density

            val availableWidth = (width - (paddingHPx * 2f)).coerceAtLeast(0f)

            // --- 1. Toolbar Bounds & Controls ---
            // HeliBoard strip height: 40dp anchored at top
            val toolbarBounds = rectF(paddingHPx, 0f, paddingHPx + availableWidth, toolbarHeightPx)

            // Edge / Expand Anchor button: 36dp square, vertically centered in 40dp strip
            val anchorTop = toolbarBounds.top + ((toolbarHeightPx - controlSizePx) / 2f)
            val anchorBottom = anchorTop + controlSizePx
            val anchorLeft = toolbarBounds.left
            val anchorRight = anchorLeft + controlSizePx
            val anchorKeyBounds = rectF(anchorLeft, anchorTop, anchorRight, anchorBottom)

            val chevronSizePx = HELIBOARD_CHEVRON_SIZE_DP * density
            val anchorCenterX = (anchorKeyBounds.left + anchorKeyBounds.right) / 2f
            val anchorCenterY = (anchorKeyBounds.top + anchorKeyBounds.bottom) / 2f
            val anchorIconLeft = (anchorCenterX - (chevronSizePx / 2f)).toInt()
            val anchorIconTop = (anchorCenterY - (chevronSizePx / 2f)).toInt()
            val anchorIconBounds = rect(
                anchorIconLeft,
                anchorIconTop,
                (anchorIconLeft + chevronSizePx).toInt(),
                (anchorIconTop + chevronSizePx).toInt()
            )

            // Pinned tools on right edge: 36dp square, vertically centered in 40dp strip
            val pinnedToolBounds = ArrayList<RectF>(pinnedTools.size)
            val pinnedIconBounds = ArrayList<Rect>(pinnedTools.size)
            val toolIconSizePx = HELIBOARD_ICON_SIZE_DP * density
            val pinnedSpacingPx = 4f * density

            val totalPinnedWidth = if (pinnedTools.isNotEmpty()) {
                (controlSizePx * pinnedTools.size) + (pinnedSpacingPx * (pinnedTools.size - 1))
            } else 0f
            val pinnedStartX = toolbarBounds.right - totalPinnedWidth

            if (pinnedTools.isNotEmpty()) {
                var pX = pinnedStartX
                for (i in pinnedTools.indices) {
                    val rect = rectF(pX, anchorTop, pX + controlSizePx, anchorBottom)
                    pinnedToolBounds.add(rect)

                    val rectCenterX = (rect.left + rect.right) / 2f
                    val rectCenterY = (rect.top + rect.bottom) / 2f
                    val icLeft = (rectCenterX - (toolIconSizePx / 2f)).toInt()
                    val icTop = (rectCenterY - (toolIconSizePx / 2f)).toInt()
                    pinnedIconBounds.add(
                        rect(icLeft, icTop, (icLeft + toolIconSizePx).toInt(), (icTop + toolIconSizePx).toInt())
                    )
                    pX += controlSizePx + pinnedSpacingPx
                }
            }

            // Middle Toolbar Area (between left expand control and right pinned tools)
            val middleLeft = anchorKeyBounds.right + spacingPx
            val middleRight = if (pinnedTools.isNotEmpty()) {
                (pinnedStartX - spacingPx).coerceAtLeast(middleLeft)
            } else {
                toolbarBounds.right
            }
            val middleAreaWidth = (middleRight - middleLeft).coerceAtLeast(0f)

            // Collapsed 3-Slot Suggestion Bar Geometry (matching HeliBoard 36% center suggestion percentile)
            val centerSlotWidth = middleAreaWidth * 0.36f
            val sideSlotWidth = ((middleAreaWidth - centerSlotWidth) / 2f).coerceAtLeast(0f)
            val slot0 = rectF(middleLeft, toolbarBounds.top, middleLeft + sideSlotWidth, toolbarBounds.bottom)
            val slot1 = rectF(middleLeft + sideSlotWidth, toolbarBounds.top, middleLeft + sideSlotWidth + centerSlotWidth, toolbarBounds.bottom)
            val slot2 = rectF(middleLeft + sideSlotWidth + centerSlotWidth, toolbarBounds.top, middleRight, toolbarBounds.bottom)
            val suggestionSlotBounds = listOf(slot0, slot1, slot2)

            // Dividers: 24dp tall hairline divider matching HeliBoard suggestions_strip_divider.xml
            val divHalfH = 12f * density
            val divCenterY = (toolbarBounds.top + toolbarBounds.bottom) / 2f
            val divider1X = middleLeft + sideSlotWidth
            val divider2X = middleLeft + sideSlotWidth + centerSlotWidth
            val dividerTopY = divCenterY - divHalfH
            val dividerBottomY = divCenterY + divHalfH

            val centerDotRadius = 1.2f * density
            val centerDotSpacing = 3.5f * density
            val centerDotsY = divCenterY + (7f * density)

            // Expanded Scrollable Tools Tray Geometry: uniform 36dp buttons with 6dp spacing
            val toolbarScrollBounds = rectF(middleLeft, toolbarBounds.top, middleRight, toolbarBounds.bottom)
            val toolsToRender = expandedTools.ifEmpty {
                ToolbarTool.values().filter { it.isDefaultExpanded }
            }
            val toolBtnWidth = controlSizePx
            val totalContentWidth = if (toolsToRender.isNotEmpty()) {
                (toolBtnWidth * toolsToRender.size) + (spacingPx * (toolsToRender.size - 1))
            } else 0f
            val maxToolbarScrollOffset = (totalContentWidth - middleAreaWidth).coerceAtLeast(0f)

            val expandedToolBounds = ArrayList<RectF>(toolsToRender.size)
            val expandedIconBounds = ArrayList<Rect>(toolsToRender.size)
            for (idx in toolsToRender.indices) {
                val itemLeft = middleLeft + (idx * (toolBtnWidth + spacingPx))
                val rect = rectF(itemLeft, anchorTop, itemLeft + toolBtnWidth, anchorBottom)
                expandedToolBounds.add(rect)
                val rectCenterX = (rect.left + rect.right) / 2f
                val rectCenterY = (rect.top + rect.bottom) / 2f
                val icLeft = (rectCenterX - (toolIconSizePx / 2f)).toInt()
                val icTop = (rectCenterY - (toolIconSizePx / 2f)).toInt()
                expandedIconBounds.add(
                    rect(icLeft, icTop, (icLeft + toolIconSizePx).toInt(), (icTop + toolIconSizePx).toInt())
                )
            }

            // --- 2. Keyboard Grid Geometry ---
            val horizontalGapPx = theme.horizontalGapDp * density
            val verticalGapPx = theme.verticalGapDp * density
            val keyboardStartY = toolbarBounds.bottom + verticalGapPx
            val availableKeyboardHeight = (height - keyboardStartY - paddingVPx - bottomInsetPx).coerceAtLeast(0f)
            val keyboardBounds = rectF(paddingHPx, keyboardStartY, paddingHPx + availableWidth, keyboardStartY + availableKeyboardHeight)

            val rowCount = rowDefinitions.size.coerceAtLeast(1)
            val totalVerticalGaps = (rowCount - 1) * verticalGapPx
            val keyHeight = ((availableKeyboardHeight - totalVerticalGaps) / rowCount).coerceAtLeast(0f)

            val rowBounds = ArrayList<RectF>(rowCount)
            val keyBoundsList = ArrayList<RectF>()

            var currentY = keyboardStartY

            if (mode == KeyboardMode.CHARACTERS && rowCount == 5) {
                val colWidth = (availableWidth - 9f * horizontalGapPx) / 10f
                for (rowIndex in rowDefinitions.indices) {
                    val row = rowDefinitions[rowIndex]
                    val rBounds = rectF(paddingHPx, currentY, paddingHPx + availableWidth, currentY + keyHeight)
                    rowBounds.add(rBounds)

                    var currentX = paddingHPx
                    when (rowIndex) {
                        0, 1 -> {
                            for (key in row) {
                                val b = rectF(currentX, currentY, currentX + colWidth, currentY + keyHeight)
                                keyBoundsList.add(b)
                                currentX += colWidth + horizontalGapPx
                            }
                        }
                        2 -> {
                            currentX += 0.5f * (colWidth + horizontalGapPx)
                            for (key in row) {
                                val b = rectF(currentX, currentY, currentX + colWidth, currentY + keyHeight)
                                keyBoundsList.add(b)
                                currentX += colWidth + horizontalGapPx
                            }
                        }
                        3 -> {
                            val functionalWidth = 1.5f * colWidth + 0.5f * horizontalGapPx
                            for (keyIndex in row.indices) {
                                val kWidth = if (keyIndex == 0 || keyIndex == row.lastIndex) functionalWidth else colWidth
                                val b = rectF(currentX, currentY, currentX + kWidth, currentY + keyHeight)
                                keyBoundsList.add(b)
                                currentX += kWidth + horizontalGapPx
                            }
                        }
                        4 -> {
                            val functionalWidth = 1.5f * colWidth + 0.5f * horizontalGapPx
                            val spaceWidth = 5f * colWidth + 4f * horizontalGapPx
                            for (key in row) {
                                val kWidth = when (key.type) {
                                    KeyType.SYMBOLS_TOGGLE, KeyType.ENTER -> functionalWidth
                                    KeyType.SPACE -> spaceWidth
                                    else -> colWidth
                                }
                                val b = rectF(currentX, currentY, currentX + kWidth, currentY + keyHeight)
                                keyBoundsList.add(b)
                                currentX += kWidth + horizontalGapPx
                            }
                        }
                    }
                    currentY += keyHeight + verticalGapPx
                }
            } else {
                for (row in rowDefinitions) {
                    val rBounds = rectF(paddingHPx, currentY, paddingHPx + availableWidth, currentY + keyHeight)
                    rowBounds.add(rBounds)

                    val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
                    val totalGaps = (row.size - 1) * horizontalGapPx
                    val widthForKeys = (availableWidth - totalGaps).coerceAtLeast(0f)

                    var currentX = paddingHPx
                    for (key in row) {
                        val keyWidth = (key.weight / totalWeight) * widthForKeys
                        val b = rectF(currentX, currentY, currentX + keyWidth, currentY + keyHeight)
                        keyBoundsList.add(b)
                        currentX += keyWidth + horizontalGapPx
                    }
                    currentY += keyHeight + verticalGapPx
                }
            }

            return KeyboardGeometry(
                width = width,
                height = height,
                density = density,
                bottomInsetPx = bottomInsetPx,
                mode = mode,
                keyHeightDp = theme.keyHeightDp,
                horizontalGapDp = theme.horizontalGapDp,
                verticalGapDp = theme.verticalGapDp,
                pinnedToolsCount = pinnedTools.size,
                expandedToolsCount = expandedTools.size,
                toolbarBounds = toolbarBounds,
                anchorKeyBounds = anchorKeyBounds,
                anchorIconBounds = anchorIconBounds,
                suggestionSlotBounds = suggestionSlotBounds,
                divider1X = divider1X,
                divider2X = divider2X,
                dividerTopY = dividerTopY,
                dividerBottomY = dividerBottomY,
                centerDotsY = centerDotsY,
                centerDotRadius = centerDotRadius,
                centerDotSpacing = centerDotSpacing,
                toolbarScrollBounds = toolbarScrollBounds,
                expandedToolBounds = expandedToolBounds,
                expandedIconBounds = expandedIconBounds,
                maxToolbarScrollOffset = maxToolbarScrollOffset,
                pinnedToolBounds = pinnedToolBounds,
                pinnedIconBounds = pinnedIconBounds,
                keyboardBounds = keyboardBounds,
                rowBounds = rowBounds,
                keyBoundsList = keyBoundsList
            )
        }
    }
}
