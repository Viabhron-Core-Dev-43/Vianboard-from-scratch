package com.example.ime.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.example.R
import com.example.ime.keyboard.KeyData
import com.example.ime.keyboard.KeyType
import com.example.ime.keyboard.KeyboardTheme
import com.example.ime.keyboard.VianKeyboardView

open class AppearanceSettingsActivity : Activity() {

    private lateinit var livePreviewKeyboard: VianKeyboardView
    private lateinit var etTestInput: EditText
    private lateinit var btnClearInput: ImageView

    private lateinit var tvHeightLabel: TextView
    private lateinit var tvRadiusLabel: TextView
    private lateinit var tvHGapLabel: TextView
    private lateinit var tvVGapLabel: TextView
    private lateinit var tvActionGrayLabel: TextView
    private lateinit var tvEnterColorLabel: TextView

    private lateinit var seekKeyHeight: SeekBar
    private lateinit var seekCornerRadius: SeekBar
    private lateinit var seekHGap: SeekBar
    private lateinit var seekVGap: SeekBar
    private lateinit var seekActionGray: SeekBar
    private lateinit var seekEnterColor: SeekBar

    private var currentTheme = KeyboardTheme()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance_settings)

        // Ensure soft keyboard does not automatically pop up over the preview screen
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        livePreviewKeyboard = findViewById(R.id.livePreviewKeyboard)
        etTestInput = findViewById(R.id.etTestInput)
        btnClearInput = findViewById(R.id.btnClearInput)

        // Prevent system soft keyboard when focusing test input field, letting the user test with the live preview below
        etTestInput.showSoftInputOnFocus = false

        btnClearInput.setOnClickListener {
            etTestInput.setText("")
        }

        tvHeightLabel = findViewById(R.id.tvHeightLabel)
        tvRadiusLabel = findViewById(R.id.tvRadiusLabel)
        tvHGapLabel = findViewById(R.id.tvHGapLabel)
        tvVGapLabel = findViewById(R.id.tvVGapLabel)
        tvActionGrayLabel = findViewById(R.id.tvActionGrayLabel)
        tvEnterColorLabel = findViewById(R.id.tvEnterColorLabel)

        seekKeyHeight = findViewById(R.id.seekKeyHeight)
        seekCornerRadius = findViewById(R.id.seekCornerRadius)
        seekHGap = findViewById(R.id.seekHGap)
        seekVGap = findViewById(R.id.seekVGap)
        seekActionGray = findViewById(R.id.seekActionGray)
        seekEnterColor = findViewById(R.id.seekEnterColor)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetToDefaults() }

        loadCurrentSettings()
        setupListeners()
        setupKeyboardInteractivity()
    }

    private fun setupKeyboardInteractivity() {
        // Wire live key events to test input field
        livePreviewKeyboard.onKeyAction = { key: KeyData ->
            handleKeyboardKey(key)
        }

        livePreviewKeyboard.onTextCommit = { text: String ->
            insertText(text)
        }

        livePreviewKeyboard.onActionExpand = {
            livePreviewKeyboard.toggleToolbarExpand()
        }

        livePreviewKeyboard.onToolbarToolClick = { tool ->
            // Provide feedback when toolbar actions are tapped in live preview
            val title = getString(tool.titleResId)
            android.widget.Toast.makeText(this, "Tool: $title", android.widget.Toast.LENGTH_SHORT).show()
        }

        livePreviewKeyboard.onCommaPopupSelected = { item ->
            insertText("$item ")
        }
    }

    private fun handleKeyboardKey(key: KeyData) {
        when (key.type) {
            KeyType.CHARACTER -> {
                val textToInsert = if (key.label.isNotEmpty()) key.label else key.code.toChar().toString()
                insertText(textToInsert)
            }
            KeyType.SPACE -> {
                insertText(" ")
            }
            KeyType.DELETE -> {
                deleteLastChar()
            }
            KeyType.ENTER -> {
                insertText("\n")
            }
            else -> {
                // Other control keys (Shift, Symbols) are handled internally by VianKeyboardView
            }
        }
    }

    private fun insertText(text: String) {
        val start = Math.max(etTestInput.selectionStart, 0)
        val end = Math.max(etTestInput.selectionEnd, 0)
        etTestInput.text.replace(Math.min(start, end), Math.max(start, end), text, 0, text.length)
    }

    private fun deleteLastChar() {
        val start = etTestInput.selectionStart
        val end = etTestInput.selectionEnd
        if (start != end) {
            etTestInput.text.delete(Math.min(start, end), Math.max(start, end))
        } else if (start > 0) {
            etTestInput.text.delete(start - 1, start)
        }
    }

    private fun loadCurrentSettings() {
        currentTheme = KeyboardTheme.loadFromPrefs(this)
        livePreviewKeyboard.theme = currentTheme

        val heightProgress = (currentTheme.keyHeightDp - 35f).toInt().coerceIn(0, 35)
        seekKeyHeight.progress = heightProgress
        tvHeightLabel.text = "Key Height: ${currentTheme.keyHeightDp.toInt()} dp"

        val radiusProgress = currentTheme.keyCornerRadiusDp.toInt().coerceIn(0, 24)
        seekCornerRadius.progress = radiusProgress
        tvRadiusLabel.text = "Corner Radius: ${currentTheme.keyCornerRadiusDp.toInt()} dp"

        val hGapProgress = currentTheme.horizontalGapDp.toInt().coerceIn(0, 12)
        seekHGap.progress = hGapProgress
        tvHGapLabel.text = "Horizontal Gap: ${currentTheme.horizontalGapDp.toInt()} dp"

        val vGapProgress = currentTheme.verticalGapDp.toInt().coerceIn(0, 14)
        seekVGap.progress = vGapProgress
        tvVGapLabel.text = "Vertical Gap: ${currentTheme.verticalGapDp.toInt()} dp"

        seekActionGray.progress = currentTheme.actionKeyGrayProgress
        tvActionGrayLabel.text = "Special Keys Grey: ${currentTheme.actionKeyGrayProgress}%"

        seekEnterColor.progress = currentTheme.enterKeyColorProgress
        tvEnterColorLabel.text = "Enter Key Color: ${currentTheme.enterKeyColorProgress}%"
    }

    private fun setupListeners() {
        val changeListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyChanges()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        seekKeyHeight.setOnSeekBarChangeListener(changeListener)
        seekCornerRadius.setOnSeekBarChangeListener(changeListener)
        seekHGap.setOnSeekBarChangeListener(changeListener)
        seekVGap.setOnSeekBarChangeListener(changeListener)
        seekActionGray.setOnSeekBarChangeListener(changeListener)
        seekEnterColor.setOnSeekBarChangeListener(changeListener)
    }

    private fun applyChanges() {
        val heightVal = (seekKeyHeight.progress + 35).toFloat()
        val radiusVal = seekCornerRadius.progress.toFloat()
        val hGapVal = seekHGap.progress.toFloat()
        val vGapVal = seekVGap.progress.toFloat()
        val actionGrayVal = seekActionGray.progress
        val enterColorVal = seekEnterColor.progress

        tvHeightLabel.text = "Key Height: ${heightVal.toInt()} dp"
        tvRadiusLabel.text = "Corner Radius: ${radiusVal.toInt()} dp"
        tvHGapLabel.text = "Horizontal Gap: ${hGapVal.toInt()} dp"
        tvVGapLabel.text = "Vertical Gap: ${vGapVal.toInt()} dp"
        tvActionGrayLabel.text = "Special Keys Grey: $actionGrayVal%"
        tvEnterColorLabel.text = "Enter Key Color: $enterColorVal%"

        val updatedTheme = currentTheme.copy(
            keyHeightDp = heightVal,
            keyCornerRadiusDp = radiusVal,
            horizontalGapDp = hGapVal,
            verticalGapDp = vGapVal,
            actionKeyGrayProgress = actionGrayVal,
            enterKeyColorProgress = enterColorVal,
            actionKeyColor = KeyboardTheme.calculateActionKeyColor(actionGrayVal),
            enterKeyColor = KeyboardTheme.calculateEnterKeyColor(enterColorVal)
        )

        currentTheme = updatedTheme
        KeyboardTheme.saveToPrefs(this, updatedTheme)
        livePreviewKeyboard.theme = updatedTheme
    }

    private fun resetToDefaults() {
        val defaultTheme = KeyboardTheme()
        KeyboardTheme.saveToPrefs(this, defaultTheme)
        loadCurrentSettings()
    }
}
