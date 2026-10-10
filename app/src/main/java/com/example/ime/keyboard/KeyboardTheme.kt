package com.example.ime.keyboard

import android.content.Context
import android.content.SharedPreferences

data class KeyboardTheme(
    val backgroundColor: Int = 0xFFE8ECEF.toInt(),      // HeliBoard keyboard_background_lxx_light_border (#E8ECEF)
    val keyBackgroundColor: Int = 0xFFFFFFFF.toInt(),   // Pure white letter/number/space keycaps
    val actionKeyColor: Int = 0xFFD6DBDF.toInt(),       // HeliBoard key_background_functional_lxx_light_border (#D6DBDF)
    val keyBottomBevelColor: Int = 0xFFB0BEC5.toInt(),  // HeliBoard key_bottom_bevel_lxx_base (#B0BEC5)
    val actionKeyBevelColor: Int = 0xFF90A4AE.toInt(),  // HeliBoard functional key bevel (#90A4AE)
    val enterKeyColor: Int = 0xFF546E7A.toInt(),        // HeliBoard enter key (#546E7A)
    val enterKeyBevelColor: Int = 0xFF37474F.toInt(),   // HeliBoard enter key bottom bevel (#37474F)
    val accentColor: Int = 0xFF1A73E8.toInt(),          // HeliBoard highlight_color_lxx_light (#1A73E8)
    val textColor: Int = 0xFF263238.toInt(),            // HeliBoard key_text_color_lxx_light (#263238)
    val enterTextColor: Int = 0xFFFFFFFF.toInt(),       // White icon/text on Enter
    val hintColor: Int = 0x9937474F.toInt(),            // HeliBoard key_hint_letter_color_lxx_light (60% opacity)
    val borderColor: Int = 0x24000000,                  // Subtle key border matching HeliBoard
    val pressedKeyColor: Int = 0xFFCBD5E1.toInt(),      // Pressed state
    val popupBackgroundColor: Int = 0xFFCBD5E1.toInt(),  // Popup bubble
    val popupTextColor: Int = 0xFF37474F.toInt(),
    
    // Geometry
    val keyHeightDp: Float = 54f,
    val toolbarHeightDp: Float = 40f,
    val keyCornerRadiusDp: Float = 6f,
    val borderWidthDp: Float = 0.8f,
    val horizontalGapDp: Float = 3.5f,
    val verticalGapDp: Float = 4f,
    val actionKeyGrayProgress: Int = 40,   // HeliBoard #D6DBDF
    val enterKeyColorProgress: Int = 50,   // HeliBoard #546E7A
    val showPopups: Boolean = true,
    val showHints: Boolean = true
) {
    companion object {
        const val PREFS_NAME = "vian_appearance_prefs"
        const val KEY_HEIGHT = "key_height"
        const val KEY_CORNER_RADIUS = "key_corner_radius"
        const val KEY_BORDER_WIDTH = "key_border_width"
        const val KEY_HORIZONTAL_GAP = "key_horizontal_gap"
        const val KEY_VERTICAL_GAP = "key_vertical_gap"
        const val KEY_ACTION_GRAY = "key_action_gray"
        const val KEY_ENTER_COLOR = "key_enter_color"
        const val KEY_SHOW_POPUPS = "key_show_popups"
        const val KEY_SHOW_HINTS = "key_show_hints"

        fun calculateActionKeyColor(grayProgress: Int): Int {
            // 0 = #F1F3F4, 40 = #D6DBDF (HeliBoard functional key), 100 = #78909C (darker grey-slate)
            if (grayProgress == 40) return 0xFFD6DBDF.toInt()
            val factor = grayProgress.coerceIn(0, 100) / 100f
            val startR = 241; val startG = 243; val startB = 244
            val endR = 120; val endG = 144; val endB = 156
            val r = (startR + (endR - startR) * factor).toInt()
            val g = (startG + (endG - startG) * factor).toInt()
            val b = (startB + (endB - startB) * factor).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun calculateEnterKeyColor(progress: Int): Int {
            if (progress == 50) return 0xFF546E7A.toInt()
            val factor = progress.coerceIn(0, 100)
            return if (factor <= 50) {
                val f = factor / 50f
                val r = (100 + (84 - 100) * f).toInt()
                val g = (116 + (110 - 116) * f).toInt()
                val b = (139 + (122 - 139) * f).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                val f = (factor - 50) / 50f
                val r = (84 + (15 - 84) * f).toInt()
                val g = (110 + (23 - 110) * f).toInt()
                val b = (122 + (42 - 122) * f).toInt()
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        fun loadFromPrefs(context: Context): KeyboardTheme {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val keyHeight = prefs.getFloat(KEY_HEIGHT, 54f)
            val cornerRadius = prefs.getFloat(KEY_CORNER_RADIUS, 6f)
            val borderWidth = prefs.getFloat(KEY_BORDER_WIDTH, 0.8f)
            val hGap = prefs.getFloat(KEY_HORIZONTAL_GAP, 3.5f)
            val vGap = prefs.getFloat(KEY_VERTICAL_GAP, 4f)
            val actionGray = prefs.getInt(KEY_ACTION_GRAY, 40)
            val enterColorProgress = prefs.getInt(KEY_ENTER_COLOR, 50)
            val showPopups = prefs.getBoolean(KEY_SHOW_POPUPS, true)
            val showHints = prefs.getBoolean(KEY_SHOW_HINTS, true)

            return KeyboardTheme(
                keyHeightDp = keyHeight,
                keyCornerRadiusDp = cornerRadius,
                borderWidthDp = borderWidth,
                borderColor = if (borderWidth > 0f) 0x24000000 else 0x00000000,
                horizontalGapDp = hGap,
                verticalGapDp = vGap,
                actionKeyGrayProgress = actionGray,
                enterKeyColorProgress = enterColorProgress,
                actionKeyColor = calculateActionKeyColor(actionGray),
                enterKeyColor = calculateEnterKeyColor(enterColorProgress),
                showPopups = showPopups,
                showHints = showHints
            )
        }

        fun saveToPrefs(context: Context, theme: KeyboardTheme) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat(KEY_HEIGHT, theme.keyHeightDp)
                .putFloat(KEY_CORNER_RADIUS, theme.keyCornerRadiusDp)
                .putFloat(KEY_BORDER_WIDTH, theme.borderWidthDp)
                .putFloat(KEY_HORIZONTAL_GAP, theme.horizontalGapDp)
                .putFloat(KEY_VERTICAL_GAP, theme.verticalGapDp)
                .putInt(KEY_ACTION_GRAY, theme.actionKeyGrayProgress)
                .putInt(KEY_ENTER_COLOR, theme.enterKeyColorProgress)
                .putBoolean(KEY_SHOW_POPUPS, theme.showPopups)
                .putBoolean(KEY_SHOW_HINTS, theme.showHints)
                .apply()
        }
    }
}
