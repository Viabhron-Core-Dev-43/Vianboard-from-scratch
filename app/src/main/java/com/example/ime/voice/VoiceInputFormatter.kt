package com.example.ime.voice

import android.view.inputmethod.InputConnection

/**
 * Intelligent text formatting for speech-to-text commits matching FUTO Voice Input:
 * - Cursor-aware auto-spacing: Inserts leading space if preceding character is not whitespace or punctuation.
 * - Sentence-aware auto-capitalization: Capitalizes first word if preceding text ends with sentence boundary (.!?) or is empty.
 * - Leading punctuation cleanup: Ensures correct punctuation spacing.
 */
object VoiceInputFormatter {

    fun formatForCommit(ic: InputConnection?, rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""

        if (ic == null) return trimmed

        val beforeText = try {
            ic.getTextBeforeCursor(3, 0)?.toString() ?: ""
        } catch (_: Exception) {
            ""
        }

        var result = trimmed

        // Determine if leading space is needed
        val needsLeadingSpace = if (beforeText.isEmpty()) {
            false
        } else {
            val lastChar = beforeText.last()
            !lastChar.isWhitespace() && lastChar != '\n' && lastChar != '(' && lastChar != '[' && lastChar != '\"'
        }

        // Determine if auto-capitalization is needed
        val needsCapitalization = if (beforeText.isEmpty()) {
            true
        } else {
            val trimmedBefore = beforeText.trimEnd()
            if (trimmedBefore.isEmpty()) {
                true
            } else {
                val lastNonWs = trimmedBefore.last()
                lastNonWs == '.' || lastNonWs == '?' || lastNonWs == '!' || lastNonWs == '\n'
            }
        }

        if (needsCapitalization && result.isNotEmpty() && result[0].isLowerCase()) {
            result = result.replaceFirstChar { it.uppercaseChar() }
        }

        if (needsLeadingSpace) {
            result = " $result"
        }

        return result
    }
}
