package com.example.ime.keyboard

import android.graphics.RectF

enum class KeyType {
    CHARACTER,
    SHIFT,
    DELETE,
    SYMBOLS_TOGGLE,
    SYMBOLS_MORE_TOGGLE,
    NUMPAD_TOGGLE,
    SPACE,
    ENTER,
    COMMA,
    PERIOD,
    ACTION_EXPAND,
    ACTION_SELECTION,
    ACTION_CLIPBOARD,
    SUGGESTION,
    TOOLBAR_TOOL
}

data class KeyData(
    val code: Int,
    var label: String,
    val hintLabel: String? = null,
    val moreKeys: List<String> = emptyList(),
    val type: KeyType = KeyType.CHARACTER,
    val weight: Float = 1.0f,
    val bounds: RectF = RectF(),
    var isPressed: Boolean = false,
    var tool: com.example.ime.toolbar.ToolbarTool? = null,
    val topCapBounds: RectF = RectF(),
    val iconBounds: android.graphics.Rect = android.graphics.Rect(),
    var cornerRadius: Float = 0f,
    var isSpecialKey: Boolean = false,
    var labelX: Float = 0f,
    var labelY: Float = 0f,
    var hintX: Float = 0f,
    var hintY: Float = 0f
)
