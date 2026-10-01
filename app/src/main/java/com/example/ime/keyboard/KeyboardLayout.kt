package com.example.ime.keyboard

import android.graphics.RectF

enum class KeyboardMode {
    CHARACTERS,
    SYMBOLS_1,
    SYMBOLS_2,
    NUMPAD
}

enum class ShiftState {
    OFF,
    ON,
    CAPS_LOCK
}

class KeyboardLayout {

    var mode: KeyboardMode = KeyboardMode.CHARACTERS
    var shiftState: ShiftState = ShiftState.OFF

    val keys = mutableListOf<KeyData>()
    val toolbarKeys = mutableListOf<KeyData>()

    var suggestions: List<String> = emptyList()
    var spaceLabel: String = "EN"

    var isToolbarExpanded: Boolean = false
    var isIncognitoActive: Boolean = false
    var pinnedTools: List<com.example.ime.toolbar.ToolbarTool> = listOf(
        com.example.ime.toolbar.ToolbarTool.SELECT_WORD,
        com.example.ime.toolbar.ToolbarTool.COPY,
        com.example.ime.toolbar.ToolbarTool.PASTE
    )
    var expandedTools: List<com.example.ime.toolbar.ToolbarTool> = listOf(
        com.example.ime.toolbar.ToolbarTool.INCOGNITO,
        com.example.ime.toolbar.ToolbarTool.UNDO,
        com.example.ime.toolbar.ToolbarTool.REDO,
        com.example.ime.toolbar.ToolbarTool.SELECT_WORD,
        com.example.ime.toolbar.ToolbarTool.SELECT_ALL,
        com.example.ime.toolbar.ToolbarTool.COPY,
        com.example.ime.toolbar.ToolbarTool.PASTE,
        com.example.ime.toolbar.ToolbarTool.UP,
        com.example.ime.toolbar.ToolbarTool.DOWN,
        com.example.ime.toolbar.ToolbarTool.VOICE,
        com.example.ime.toolbar.ToolbarTool.PROMPT_LIST,
        com.example.ime.toolbar.ToolbarTool.SECURITY_VAULT,
        com.example.ime.toolbar.ToolbarTool.DESKTOP_SHORTCUTS,
        com.example.ime.toolbar.ToolbarTool.SETTINGS
    )
    var hidePinnedWhenExpanded: Boolean = false
    var toolbarScrollOffset: Float = 0f
    var maxToolbarScrollOffset: Float = 0f
    val toolbarScrollBounds = RectF()

    private fun buildMoreKeys(hint: String?, baseMore: List<String>): List<String> {
        if (hint.isNullOrEmpty() || hint == "…") return baseMore
        val result = mutableListOf<String>()
        result.add(hint)
        for (item in baseMore) {
            if (item != hint) {
                result.add(item)
            }
        }
        return result
    }

    var cachedGeometry: KeyboardGeometry? = null
        private set

    // Pre-allocated, reusable toolbar KeyData items
    private val anchorKey = KeyData(
        code = -101,
        label = "›",
        type = KeyType.ACTION_EXPAND,
        weight = 1f
    )
    private val suggestionKey0 = KeyData(
        code = -200,
        label = "",
        type = KeyType.SUGGESTION,
        weight = 1f
    )
    private val suggestionKey1 = KeyData(
        code = -201,
        label = "",
        type = KeyType.SUGGESTION,
        weight = 1f
    )
    private val suggestionKey2 = KeyData(
        code = -202,
        label = "",
        type = KeyType.SUGGESTION,
        weight = 1f
    )
    private val pinnedToolKeys = mutableListOf<KeyData>()
    private val expandedToolKeys = mutableListOf<KeyData>()

    /**
     * Ensures layout and cached geometry are up-to-date.
     * Only recalculates geometry when dimensions, insets, mode, or configuration actually change.
     * Returns true if full geometry was recalculated.
     */
    fun ensureLayout(
        width: Float,
        height: Float,
        theme: KeyboardTheme,
        density: Float,
        bottomInsetPx: Float = 0f,
        forceRebuild: Boolean = false
    ): Boolean {
        if (width <= 0f || height <= 0f) return false

        val currentGeo = cachedGeometry
        if (!forceRebuild && currentGeo != null && currentGeo.isValid(
                width,
                height,
                density,
                bottomInsetPx,
                mode,
                theme,
                pinnedTools.size,
                expandedTools.size
            )
        ) {
            // Geometry is already up-to-date and retained
            return false
        }

        // Recompute geometry once
        val rowDefinitions = getRowDefinitions(mode, shiftState)
        val newGeo = KeyboardGeometry.calculate(
            width = width,
            height = height,
            density = density,
            theme = theme,
            mode = mode,
            pinnedTools = pinnedTools,
            expandedTools = expandedTools,
            bottomInsetPx = bottomInsetPx,
            rowDefinitions = rowDefinitions
        )
        cachedGeometry = newGeo

        // 1. Update pre-allocated anchor key bounds
        anchorKey.bounds.left = newGeo.anchorKeyBounds.left
        anchorKey.bounds.top = newGeo.anchorKeyBounds.top
        anchorKey.bounds.right = newGeo.anchorKeyBounds.right
        anchorKey.bounds.bottom = newGeo.anchorKeyBounds.bottom

        anchorKey.iconBounds.left = newGeo.anchorIconBounds.left
        anchorKey.iconBounds.top = newGeo.anchorIconBounds.top
        anchorKey.iconBounds.right = newGeo.anchorIconBounds.right
        anchorKey.iconBounds.bottom = newGeo.anchorIconBounds.bottom

        // 2. Update pre-allocated suggestion keys bounds
        suggestionKey0.bounds.left = newGeo.suggestionSlotBounds[0].left
        suggestionKey0.bounds.top = newGeo.suggestionSlotBounds[0].top
        suggestionKey0.bounds.right = newGeo.suggestionSlotBounds[0].right
        suggestionKey0.bounds.bottom = newGeo.suggestionSlotBounds[0].bottom

        suggestionKey1.bounds.left = newGeo.suggestionSlotBounds[1].left
        suggestionKey1.bounds.top = newGeo.suggestionSlotBounds[1].top
        suggestionKey1.bounds.right = newGeo.suggestionSlotBounds[1].right
        suggestionKey1.bounds.bottom = newGeo.suggestionSlotBounds[1].bottom

        suggestionKey2.bounds.left = newGeo.suggestionSlotBounds[2].left
        suggestionKey2.bounds.top = newGeo.suggestionSlotBounds[2].top
        suggestionKey2.bounds.right = newGeo.suggestionSlotBounds[2].right
        suggestionKey2.bounds.bottom = newGeo.suggestionSlotBounds[2].bottom

        // 3. Pre-create/update expanded tool keys
        val toolsToRender = expandedTools.ifEmpty {
            com.example.ime.toolbar.ToolbarTool.values().filter { it.isDefaultExpanded }
        }
        expandedToolKeys.clear()
        for ((idx, tool) in toolsToRender.withIndex()) {
            if (idx < newGeo.expandedToolBounds.size) {
                val tk = KeyData(
                    code = -300 - idx,
                    label = "",
                    type = KeyType.TOOLBAR_TOOL,
                    weight = 1f
                ).apply {
                    this.tool = tool
                    val src = newGeo.expandedToolBounds[idx]
                    this.bounds.left = src.left
                    this.bounds.top = src.top
                    this.bounds.right = src.right
                    this.bounds.bottom = src.bottom
                    if (idx < newGeo.expandedIconBounds.size) {
                        val isrc = newGeo.expandedIconBounds[idx]
                        this.iconBounds.left = isrc.left
                        this.iconBounds.top = isrc.top
                        this.iconBounds.right = isrc.right
                        this.iconBounds.bottom = isrc.bottom
                    }
                }
                expandedToolKeys.add(tk)
            }
        }

        // 4. Pre-create/update pinned tool keys
        pinnedToolKeys.clear()
        for ((idx, tool) in pinnedTools.withIndex()) {
            if (idx < newGeo.pinnedToolBounds.size) {
                val tk = KeyData(
                    code = -400 - idx,
                    label = "",
                    type = KeyType.TOOLBAR_TOOL,
                    weight = 1f
                ).apply {
                    this.tool = tool
                    val src = newGeo.pinnedToolBounds[idx]
                    this.bounds.left = src.left
                    this.bounds.top = src.top
                    this.bounds.right = src.right
                    this.bounds.bottom = src.bottom
                    if (idx < newGeo.pinnedIconBounds.size) {
                        val isrc = newGeo.pinnedIconBounds[idx]
                        this.iconBounds.left = isrc.left
                        this.iconBounds.top = isrc.top
                        this.iconBounds.right = isrc.right
                        this.iconBounds.bottom = isrc.bottom
                    }
                }
                pinnedToolKeys.add(tk)
            }
        }

        // 5. Populate keys list with cached bounds and cached drawing metrics
        keys.clear()
        var keyIndex = 0
        val bevelInsetBottomPx = 1.0f * density
        val icSizePx = 22f * density
        for (row in rowDefinitions) {
            for (key in row) {
                if (keyIndex < newGeo.keyBoundsList.size) {
                    val srcBounds = newGeo.keyBoundsList[keyIndex]
                    key.bounds.left = srcBounds.left
                    key.bounds.top = srcBounds.top
                    key.bounds.right = srcBounds.right
                    key.bounds.bottom = srcBounds.bottom

                    key.isSpecialKey = key.type == KeyType.SHIFT ||
                                       key.type == KeyType.SYMBOLS_TOGGLE ||
                                       key.type == KeyType.SYMBOLS_MORE_TOGGLE ||
                                       key.type == KeyType.NUMPAD_TOGGLE ||
                                       key.type == KeyType.DELETE ||
                                       key.type == KeyType.ENTER
                    key.cornerRadius = if (key.isSpecialKey) (key.bounds.bottom - key.bounds.top) / 2f else theme.keyCornerRadiusDp * density
                    key.topCapBounds.left = key.bounds.left
                    key.topCapBounds.top = key.bounds.top
                    key.topCapBounds.right = key.bounds.right
                    key.topCapBounds.bottom = key.bounds.bottom - bevelInsetBottomPx

                    val keyCenterX = (key.bounds.left + key.bounds.right) / 2f
                    val keyCenterY = (key.bounds.top + key.bounds.bottom) / 2f
                    val icLeft = (keyCenterX - (icSizePx / 2f)).toInt()
                    val icTop = (keyCenterY - (icSizePx / 2f)).toInt()
                    key.iconBounds.left = icLeft
                    key.iconBounds.top = icTop
                    key.iconBounds.right = (icLeft + icSizePx).toInt()
                    key.iconBounds.bottom = (icTop + icSizePx).toInt()
                    key.labelX = keyCenterX
                    key.labelY = keyCenterY

                    if (key.type == KeyType.COMMA || key.type == KeyType.PERIOD) {
                        key.hintX = keyCenterX + (3.5f * density)
                        key.hintY = key.bounds.bottom - (4.5f * density)
                    } else {
                        key.hintX = key.bounds.right - (4f * density)
                        key.hintY = key.bounds.top + (11f * density)
                    }

                    keys.add(key)
                    keyIndex++
                }
            }
        }

        // Update toolbar scroll bounds
        toolbarScrollBounds.left = newGeo.toolbarScrollBounds.left
        toolbarScrollBounds.top = newGeo.toolbarScrollBounds.top
        toolbarScrollBounds.right = newGeo.toolbarScrollBounds.right
        toolbarScrollBounds.bottom = newGeo.toolbarScrollBounds.bottom
        maxToolbarScrollOffset = newGeo.maxToolbarScrollOffset
        toolbarScrollOffset = toolbarScrollOffset.coerceIn(0f, maxToolbarScrollOffset)

        // Populate toolbar keys using cached geometry
        syncToolbarKeys()
        return true
    }

    fun buildLayout(
        width: Float,
        height: Float,
        theme: KeyboardTheme,
        density: Float,
        bottomInsetPx: Float = 0f,
        forceRebuild: Boolean = false
    ) {
        ensureLayout(width, height, theme, density, bottomInsetPx, forceRebuild = forceRebuild)
    }

    /**
     * Updates key labels for Shift without recalculating geometry.
     */
    fun updateShiftLabels() {
        if (mode != KeyboardMode.CHARACTERS) return
        val isCaps = shiftState != ShiftState.OFF
        val shiftLabel = when (shiftState) {
            ShiftState.CAPS_LOCK -> "⇪"
            ShiftState.ON -> "▲"
            ShiftState.OFF -> "⇧"
        }

        for (key in keys) {
            when (key.type) {
                KeyType.SHIFT -> {
                    key.label = shiftLabel
                }
                KeyType.CHARACTER -> {
                    val firstChar = key.label.firstOrNull() ?: continue
                    if (firstChar.isLetter()) {
                        val newChar = if (isCaps) firstChar.uppercaseChar() else firstChar.lowercaseChar()
                        key.label = newChar.toString()
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Updates only suggestion keys using precalculated geometry slots without rebuilding the keyboard grid.
     */
    fun syncSuggestionKeys() {
        if (isToolbarExpanded) return

        toolbarKeys.removeAll { it.type == KeyType.SUGGESTION }

        if (suggestions.isNotEmpty()) {
            when (suggestions.size) {
                1 -> {
                    suggestionKey1.label = suggestions[0]
                    toolbarKeys.add(suggestionKey1)
                }
                2 -> {
                    suggestionKey0.label = suggestions[0]
                    suggestionKey1.label = suggestions[1]
                    toolbarKeys.add(suggestionKey0)
                    toolbarKeys.add(suggestionKey1)
                }
                else -> {
                    suggestionKey0.label = suggestions[0]
                    suggestionKey1.label = suggestions[1]
                    suggestionKey2.label = suggestions[2]
                    toolbarKeys.add(suggestionKey0)
                    toolbarKeys.add(suggestionKey1)
                    toolbarKeys.add(suggestionKey2)
                }
            }
        }
    }

    /**
     * Synchronizes toolbar keys between collapsed (suggestions) and expanded (tools) states.
     */
    fun syncToolbarKeys() {
        toolbarKeys.clear()

        // 1. Anchor button (Chevron / Incognito)
        anchorKey.label = if (isIncognitoActive) "🕶️" else (if (isToolbarExpanded) "‹" else "›")
        toolbarKeys.add(anchorKey)

        if (isToolbarExpanded) {
            toolbarKeys.addAll(expandedToolKeys)
        } else {
            syncSuggestionKeys()
        }

        // Pinned tools docked on right edge
        val showPinned = pinnedToolKeys.isNotEmpty() && (!isToolbarExpanded || !hidePinnedWhenExpanded)
        if (showPinned) {
            toolbarKeys.addAll(pinnedToolKeys)
        }
    }

    fun getRowCountForMode(mode: KeyboardMode): Int {
        return when (mode) {
            KeyboardMode.NUMPAD -> 4
            KeyboardMode.CHARACTERS,
            KeyboardMode.SYMBOLS_1,
            KeyboardMode.SYMBOLS_2 -> 5
        }
    }

    private fun getRowDefinitions(mode: KeyboardMode, shiftState: ShiftState): List<List<KeyData>> {
        return when (mode) {
            KeyboardMode.CHARACTERS -> getQwerty5Rows(shiftState)
            KeyboardMode.SYMBOLS_1 -> getSymbols1Rows()
            KeyboardMode.SYMBOLS_2 -> getSymbols2Rows()
            KeyboardMode.NUMPAD -> getNumpadRows()
        }
    }

    private fun getQwerty5Rows(shiftState: ShiftState): List<List<KeyData>> {
        val isCaps = shiftState != ShiftState.OFF

        // Row 0: Numbers with superscripts & fractions
        val numChars = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val numHints = listOf("¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹", "⁰")
        val numMore = listOf(
            listOf("1", "¹", "½", "⅓", "¼", "⅛"),
            listOf("2", "²", "⅔", "⅖"),
            listOf("3", "³", "¾", "⅜"),
            listOf("4", "⁴", "⅘"),
            listOf("5", "⁵", "⅝"),
            listOf("6", "⁶"),
            listOf("7", "⁷", "⅞"),
            listOf("8", "⁸"),
            listOf("9", "⁹"),
            listOf("0", "⁰", "ⁿ", "∅")
        )

        val row0 = numChars.mapIndexed { idx, ch ->
            KeyData(
                code = ch[0].code,
                label = ch,
                hintLabel = numHints[idx],
                moreKeys = buildMoreKeys(numHints[idx], numMore[idx]),
                type = KeyType.CHARACTER,
                weight = 1.0f
            )
        }

        // Row 1: Q-P with corner hint symbols matching Screenshot 1
        val r1Chars = if (isCaps) listOf("Q","W","E","R","T","Y","U","I","O","P") else listOf("q","w","e","r","t","y","u","i","o","p")
        val r1Hints = listOf("%", "/", "|", "=", "[", "]", "*", "!", "-", ";")
        val r1More = if (isCaps) {
            listOf(
                listOf("Q"),
                listOf("W"),
                listOf("E", "É", "È", "Ê", "Ë", "Ē", "Ę"),
                listOf("R"),
                listOf("T", "Þ"),
                listOf("Y", "Ý", "Ÿ"),
                listOf("U", "Ú", "Ù", "Û", "Ü", "Ū", "Ů"),
                listOf("I", "Í", "Ì", "Î", "Ï", "Ī", "Į"),
                listOf("O", "Ó", "Ò", "Ô", "Ö", "Õ", "Ō", "Œ", "Ø"),
                listOf("P")
            )
        } else {
            listOf(
                listOf("q"),
                listOf("w"),
                listOf("e", "é", "è", "ê", "ë", "ē", "ę"),
                listOf("r"),
                listOf("t", "þ"),
                listOf("y", "ý", "ÿ"),
                listOf("u", "ú", "ù", "û", "ü", "ū", "ů"),
                listOf("i", "í", "ì", "î", "ï", "ī", "į"),
                listOf("o", "ó", "ò", "ô", "ö", "õ", "ō", "œ", "ø"),
                listOf("p")
            )
        }

        val row1 = r1Chars.mapIndexed { idx, ch ->
            KeyData(
                code = ch[0].code,
                label = ch,
                hintLabel = r1Hints[idx],
                moreKeys = buildMoreKeys(r1Hints[idx], r1More[idx]),
                type = KeyType.CHARACTER,
                weight = 1.0f
            )
        }

        // Row 2: A-L with corner hint symbols
        val r2Chars = if (isCaps) listOf("A","S","D","F","G","H","J","K","L") else listOf("a","s","d","f","g","h","j","k","l")
        val r2Hints = listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")")
        val r2More = if (isCaps) {
            listOf(
                listOf("A", "Á", "À", "Â", "Ä", "Ã", "Å", "Ā", "Æ"),
                listOf("S", "ß", "Ś", "Š", "$"),
                listOf("D", "Ð", "Đ"),
                listOf("F"),
                listOf("G"),
                listOf("H"),
                listOf("J"),
                listOf("K"),
                listOf("L", "Ł")
            )
        } else {
            listOf(
                listOf("a", "á", "à", "â", "ä", "ã", "å", "ā", "æ"),
                listOf("s", "ß", "ś", "š", "$"),
                listOf("d", "ð", "đ"),
                listOf("f"),
                listOf("g"),
                listOf("h"),
                listOf("j"),
                listOf("k"),
                listOf("l", "ł")
            )
        }

        val row2 = r2Chars.mapIndexed { idx, ch ->
            KeyData(
                code = ch[0].code,
                label = ch,
                hintLabel = r2Hints[idx],
                moreKeys = buildMoreKeys(r2Hints[idx], r2More[idx]),
                type = KeyType.CHARACTER,
                weight = 1.0f
            )
        }

        // Row 3: Shift, Z-M, Delete
        val shiftLabel = when (shiftState) {
            ShiftState.CAPS_LOCK -> "⇪"
            ShiftState.ON -> "▲"
            ShiftState.OFF -> "⇧"
        }

        val r3Chars = if (isCaps) listOf("Z","X","C","V","B","N","M") else listOf("z","x","c","v","b","n","m")
        val r3Hints = listOf("*", "\"", "'", ":", ";", "!", "?")
        val r3More = if (isCaps) {
            listOf(
                listOf("Z", "Ź", "Ż", "Ž"),
                listOf("X"),
                listOf("C", "Ç", "Ć", "Č"),
                listOf("V"),
                listOf("B"),
                listOf("N", "Ñ", "Ń"),
                listOf("M")
            )
        } else {
            listOf(
                listOf("z", "ź", "ż", "ž"),
                listOf("x"),
                listOf("c", "ç", "ć", "č"),
                listOf("v"),
                listOf("b"),
                listOf("n", "ñ", "ń"),
                listOf("m")
            )
        }

        val row3 = mutableListOf<KeyData>()
        row3.add(KeyData(code = -1, label = shiftLabel, type = KeyType.SHIFT, weight = 1.4f))
        for (i in r3Chars.indices) {
            row3.add(
                KeyData(
                    code = r3Chars[i][0].code,
                    label = r3Chars[i],
                    hintLabel = r3Hints[i],
                    moreKeys = buildMoreKeys(r3Hints[i], r3More[i]),
                    type = KeyType.CHARACTER,
                    weight = 1.0f
                )
            )
        }
        row3.add(KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.4f))

        // Row 4: ?123, Comma, Space, Period, Enter
        val row4 = listOf(
            KeyData(code = -3, label = "?123", type = KeyType.SYMBOLS_TOGGLE, weight = 1.4f),
            KeyData(
                code = ','.code,
                label = ",",
                hintLabel = "…",
                moreKeys = listOf(",", ";", ":", "…"),
                type = KeyType.COMMA,
                weight = 1.0f
            ),
            KeyData(code = 32, label = spaceLabel, hintLabel = null, type = KeyType.SPACE, weight = 4.6f),
            KeyData(
                code = '.'.code,
                label = ".",
                hintLabel = "…",
                moreKeys = listOf(".", "…", "!", "?", "-", "_"),
                type = KeyType.PERIOD,
                weight = 1.0f
            ),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.6f)
        )

        return listOf(row0, row1, row2, row3, row4)
    }

    // Symbols Page 1 (Matching Screenshot 4)
    private fun getSymbols1Rows(): List<List<KeyData>> {
        // Row 0: 1 2 3 4 5 6 7 8 9 0
        val r0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 1: % / | = [ ] * ! - ; " (11 keys)
        val r1 = listOf("%", "/", "|", "=", "[", "]", "*", "!", "-", ";", "\"").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 2: @ # ₹ _ & - + ( ) { } (11 keys)
        val r2 = listOf("@", "#", "₹", "_", "&", "-", "+", "(", ")", "{", "}").map {
            val more = if (it == "₹") listOf("₹", "$", "€", "£", "¥", "¢") else listOf(it)
            KeyData(code = it[0].code, label = it, moreKeys = more, type = KeyType.CHARACTER, weight = 1.0f)
        }

        // Row 3: =\< * " ' : ; ! ? : ⌫
        val row3 = mutableListOf<KeyData>()
        row3.add(KeyData(code = -5, label = "=\\<", type = KeyType.SYMBOLS_MORE_TOGGLE, weight = 1.4f))
        val r3 = listOf("*", "\"", "'", ":", ";", "!", "?", ":")
        for (ch in r3) {
            row3.add(KeyData(code = ch[0].code, label = ch, type = KeyType.CHARACTER, weight = 1.0f))
        }
        row3.add(KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.4f))

        // Row 4: ABC , 12/34 Space . ↵
        val row4 = listOf(
            KeyData(code = -3, label = "ABC", type = KeyType.SYMBOLS_TOGGLE, weight = 1.3f),
            KeyData(code = ','.code, label = ",", hintLabel = "…", type = KeyType.COMMA, weight = 0.9f),
            KeyData(code = -7, label = "12\n34", type = KeyType.NUMPAD_TOGGLE, weight = 0.9f),
            KeyData(code = 32, label = "", hintLabel = null, type = KeyType.SPACE, weight = 4.0f),
            KeyData(code = '.'.code, label = ".", type = KeyType.PERIOD, weight = 0.9f),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.5f)
        )

        return listOf(r0, r1, r2, row3, row4)
    }

    // Symbols Page 2 (Matching Screenshot 3)
    private fun getSymbols2Rows(): List<List<KeyData>> {
        // Row 0: 1 2 3 4 5 6 7 8 9 0
        val r0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 1: ~ ` | • √ π ÷ × ¶ Δ
        val r1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }
        // Row 2: £ € $ ¢ ^ ° = { }
        val r2 = listOf("£", "€", "$", "¢", "^", "°", "=", "{", "}").map {
            KeyData(code = it[0].code, label = it, type = KeyType.CHARACTER, weight = 1.0f)
        }

        // Row 3: ?123 \ © ® ™ % [ ] ⌫
        val row3 = mutableListOf<KeyData>()
        row3.add(KeyData(code = -6, label = "?123", type = KeyType.SYMBOLS_MORE_TOGGLE, weight = 1.4f))
        val r3 = listOf("\\", "©", "®", "™", "%", "[", "]")
        for (ch in r3) {
            row3.add(KeyData(code = ch[0].code, label = ch, type = KeyType.CHARACTER, weight = 1.0f))
        }
        row3.add(KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.4f))

        // Row 4: ABC < Space > ↵
        val row4 = listOf(
            KeyData(code = -3, label = "ABC", type = KeyType.SYMBOLS_TOGGLE, weight = 1.4f),
            KeyData(code = '<'.code, label = "<", type = KeyType.CHARACTER, weight = 1.0f),
            KeyData(code = 32, label = "", hintLabel = null, type = KeyType.SPACE, weight = 4.6f),
            KeyData(code = '>'.code, label = ">", type = KeyType.CHARACTER, weight = 1.0f),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.6f)
        )

        return listOf(r0, r1, r2, row3, row4)
    }

    // Dedicated 3x4 Numpad / Calculator Grid (Matching Screenshot 2)
    private fun getNumpadRows(): List<List<KeyData>> {
        // Row 0: +( , 1 , 2 , 3 , %₹
        val row0 = listOf(
            KeyData(code = '+'.code, label = "+", hintLabel = "(", moreKeys = listOf("+", "("), type = KeyType.CHARACTER, weight = 1.2f),
            KeyData(code = '1'.code, label = "1", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '2'.code, label = "2", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '3'.code, label = "3", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '%'.code, label = "%", hintLabel = "₹", moreKeys = listOf("%", "₹", "$"), type = KeyType.CHARACTER, weight = 1.2f)
        )

        // Row 1: -) , 4 , 5 , 6 , _
        val row1 = listOf(
            KeyData(code = '-'.code, label = "-", hintLabel = ")", moreKeys = listOf("-", ")"), type = KeyType.CHARACTER, weight = 1.2f),
            KeyData(code = '4'.code, label = "4", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '5'.code, label = "5", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '6'.code, label = "6", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '_'.code, label = "_", type = KeyType.CHARACTER, weight = 1.2f)
        )

        // Row 2: */ , 7 , 8 , 9 , ⌫
        val row2 = listOf(
            KeyData(code = '*'.code, label = "*", hintLabel = "/", moreKeys = listOf("*", "/"), type = KeyType.CHARACTER, weight = 1.2f),
            KeyData(code = '7'.code, label = "7", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '8'.code, label = "8", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '9'.code, label = "9", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = -2, label = "⌫", type = KeyType.DELETE, weight = 1.2f)
        )

        // Row 3: ABC , , , 0 , ?123 , =# , :: , ↵
        val row3 = listOf(
            KeyData(code = -3, label = "ABC", type = KeyType.SYMBOLS_TOGGLE, weight = 1.2f),
            KeyData(code = ','.code, label = ",", hintLabel = "…", type = KeyType.COMMA, weight = 1.0f),
            KeyData(code = -3, label = "?123", type = KeyType.SYMBOLS_TOGGLE, weight = 1.0f),
            KeyData(code = '0'.code, label = "0", type = KeyType.CHARACTER, weight = 1.4f),
            KeyData(code = '='.code, label = "=", hintLabel = "#", moreKeys = listOf("=", "#"), type = KeyType.CHARACTER, weight = 1.0f),
            KeyData(code = ':'.code, label = ":", hintLabel = ":", type = KeyType.CHARACTER, weight = 0.8f),
            KeyData(code = -4, label = "↵", hintLabel = null, type = KeyType.ENTER, weight = 1.4f)
        )

        return listOf(row0, row1, row2, row3)
    }

    fun findKeyAt(x: Float, y: Float): KeyData? {
        val keyHit = keys.firstOrNull { it.bounds.contains(x, y) }
        if (keyHit != null) return keyHit

        // Toolbar hit testing
        val anchorHit = toolbarKeys.firstOrNull { it.type == KeyType.ACTION_EXPAND && it.bounds.contains(x, y) }
        if (anchorHit != null) return anchorHit

        if (isToolbarExpanded && toolbarScrollBounds.contains(x, y)) {
            val adjustedX = x + toolbarScrollOffset
            return toolbarKeys.firstOrNull { it.code in -300 downTo -399 && it.bounds.contains(adjustedX, y) }
        }

        return toolbarKeys.firstOrNull { it.bounds.contains(x, y) }
    }
}
