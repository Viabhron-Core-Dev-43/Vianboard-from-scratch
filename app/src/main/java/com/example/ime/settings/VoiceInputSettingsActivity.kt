package com.example.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.ime.voice.VoiceModelManager
import com.example.ime.voice.VoiceSettingsPreferences
import com.example.ime.voice.WordReplacement
import com.example.ime.voice.WordReplacementStore
import com.example.R
import java.util.Locale

/**
 * Dedicated Voice Input settings configuration screen.
 * Provides:
 * 1. Model management: SAF document picker for offline GGML models with magic header validation.
 * 2. Inference temperature control (0.0 to 0.6).
 * 3. Word Improvement: Dynamic CRUD manager for phonetic & vocabulary replacements.
 */
class VoiceInputSettingsActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_PICK_MODEL = 2001
    }

    private lateinit var tvModelStatusTitle: TextView
    private lateinit var tvModelStatusDetail: TextView
    private lateinit var btnImportModel: Button
    private lateinit var btnDeleteModel: Button

    private lateinit var sbTemperature: SeekBar
    private lateinit var tvTemperatureValue: TextView

    private lateinit var switchSuppressAnnotations: android.widget.Switch
    private lateinit var switchVerboseMode: android.widget.Switch
    private lateinit var switchBeamSearch: android.widget.Switch
    private lateinit var switchUndertrainedLanguages: android.widget.Switch
    private lateinit var rowOpenImeSettings: View
    private lateinit var switchLegacy30sLimit: android.widget.Switch
    private lateinit var switchHapticFeedback: android.widget.Switch
    private lateinit var switchAutoStopOnSilence: android.widget.Switch

    private lateinit var llDownloadProgressCard: LinearLayout
    private lateinit var tvDownloadProgressTitle: TextView
    private lateinit var pbModelDownload: android.widget.ProgressBar
    private lateinit var tvDownloadProgressDetail: TextView
    private lateinit var containerRecommendedModels: LinearLayout

    private lateinit var btnAddReplacement: Button
    private lateinit var containerReplacements: LinearLayout
    private lateinit var tvEmptyReplacements: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_input_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        initModelViews()
        initRecommendedModelsViews()
        initTemperatureViews()
        initAdvancedSettingsViews()
        initWordReplacementViews()

        handleIncomingModelIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingModelIntent(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshModelCard()
        refreshReplacementsList()
    }

    private fun initModelViews() {
        tvModelStatusTitle = findViewById(R.id.tvModelStatusTitle)
        tvModelStatusDetail = findViewById(R.id.tvModelStatusDetail)
        btnImportModel = findViewById(R.id.btnImportModel)
        btnDeleteModel = findViewById(R.id.btnDeleteModel)

        btnImportModel.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_MODEL)
        }

        btnDeleteModel.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Voice Model")
                .setMessage("Are you sure you want to delete the installed voice model?")
                .setPositiveButton("Delete") { _, _ ->
                    VoiceModelManager.deleteActiveModel(this)
                    refreshModelCard()
                    Toast.makeText(this, "Voice model deleted", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshModelCard() {
        val modelInfo = VoiceModelManager.getModelInfo(this)
        if (modelInfo != null && modelInfo.isValid) {
            tvModelStatusTitle.text = modelInfo.fileName
            val modelType = if (modelInfo.isAcftOptimized) "Valid ACFT-optimized GGML model" else "Valid offline GGML model"
            tvModelStatusDetail.text = "Installed (${modelInfo.formattedSize}) • $modelType"
            btnDeleteModel.visibility = View.VISIBLE
            btnImportModel.text = "Replace Model (.bin)"
        } else {
            tvModelStatusTitle.text = "No model installed"
            tvModelStatusDetail.text = "Import a tiny or base GGML model file to enable offline voice typing."
            btnDeleteModel.visibility = View.GONE
            btnImportModel.text = "Import Model (.bin)"
        }
    }

    private fun initTemperatureViews() {
        sbTemperature = findViewById(R.id.sbTemperature)
        tvTemperatureValue = findViewById(R.id.tvTemperatureValue)

        val currentTemp = VoiceModelManager.getTemperature(this)
        val progress = (currentTemp * 10f).toInt().coerceIn(0, 6)
        sbTemperature.progress = progress
        tvTemperatureValue.text = String.format(Locale.US, "%.1f", progress / 10f)

        sbTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 10f
                tvTemperatureValue.text = String.format(Locale.US, "%.1f", temp)
                if (fromUser) {
                    VoiceModelManager.setTemperature(this@VoiceInputSettingsActivity, temp)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun initAdvancedSettingsViews() {
        switchSuppressAnnotations = findViewById(R.id.switchSuppressAnnotations)
        switchVerboseMode = findViewById(R.id.switchVerboseMode)
        switchBeamSearch = findViewById(R.id.switchBeamSearch)
        switchUndertrainedLanguages = findViewById(R.id.switchUndertrainedLanguages)
        rowOpenImeSettings = findViewById(R.id.rowOpenImeSettings)
        switchLegacy30sLimit = findViewById(R.id.switchLegacy30sLimit)
        switchHapticFeedback = findViewById(R.id.switchHapticFeedback)

        switchSuppressAnnotations.isChecked = VoiceSettingsPreferences.isSuppressNonSpeechEnabled(this)
        switchSuppressAnnotations.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setSuppressNonSpeechEnabled(this, isChecked)
        }

        switchVerboseMode.isChecked = VoiceSettingsPreferences.isVerboseModeEnabled(this)
        switchVerboseMode.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setVerboseModeEnabled(this, isChecked)
        }

        switchBeamSearch.isChecked = VoiceSettingsPreferences.isUseBeamSearchEnabled(this)
        switchBeamSearch.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setUseBeamSearchEnabled(this, isChecked)
        }

        switchUndertrainedLanguages.isChecked = VoiceSettingsPreferences.isAllowUndertrainedLanguagesEnabled(this)
        switchUndertrainedLanguages.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setAllowUndertrainedLanguagesEnabled(this, isChecked)
        }

        rowOpenImeSettings.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open system IME settings", Toast.LENGTH_SHORT).show()
            }
        }

        switchLegacy30sLimit.isChecked = VoiceSettingsPreferences.isLegacy30sLimitEnabled(this)
        switchLegacy30sLimit.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setLegacy30sLimitEnabled(this, isChecked)
        }

        switchHapticFeedback.isChecked = VoiceSettingsPreferences.isHapticFeedbackEnabled(this)
        switchHapticFeedback.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setHapticFeedbackEnabled(this, isChecked)
        }

        switchAutoStopOnSilence = findViewById(R.id.switchAutoStopOnSilence)
        switchAutoStopOnSilence.isChecked = VoiceSettingsPreferences.isAutoStopOnSilenceEnabled(this)
        switchAutoStopOnSilence.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettingsPreferences.setAutoStopOnSilenceEnabled(this, isChecked)
        }
    }

    private fun initWordReplacementViews() {
        btnAddReplacement = findViewById(R.id.btnAddReplacement)
        containerReplacements = findViewById(R.id.containerReplacements)
        tvEmptyReplacements = findViewById(R.id.tvEmptyReplacements)

        btnAddReplacement.setOnClickListener {
            showAddReplacementDialog()
        }
    }

    private fun refreshReplacementsList() {
        containerReplacements.removeAllViews()
        val replacements = WordReplacementStore.getAll(this)

        if (replacements.isEmpty()) {
            tvEmptyReplacements.visibility = View.VISIBLE
            containerReplacements.visibility = View.GONE
        } else {
            tvEmptyReplacements.visibility = View.GONE
            containerReplacements.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)
            for (item in replacements) {
                val itemView = inflater.inflate(R.layout.item_word_replacement, containerReplacements, false)
                val tvTarget = itemView.findViewById<TextView>(R.id.tvTarget)
                val tvReplacement = itemView.findViewById<TextView>(R.id.tvReplacement)
                val btnDelete = itemView.findViewById<Button>(R.id.btnDelete)

                tvTarget.text = "\"${item.target}\""
                tvReplacement.text = "➔ \"${item.replacement}\""

                btnDelete.setOnClickListener {
                    WordReplacementStore.deleteReplacement(this, item.id)
                    refreshReplacementsList()
                }

                containerReplacements.addView(itemView)
            }
        }
    }

    private fun showAddReplacementDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etTarget = EditText(this).apply {
            hint = "Spoken word or phrase (e.g. hear)"
            setSingleLine(true)
        }

        val etReplacement = EditText(this).apply {
            hint = "Replacement text (e.g. here)"
            setSingleLine(true)
        }

        dialogView.addView(etTarget)
        dialogView.addView(etReplacement)

        AlertDialog.Builder(this)
            .setTitle("Add Word Replacement")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val target = etTarget.text.toString().trim()
                val replacement = etReplacement.text.toString().trim()
                if (target.isNotBlank()) {
                    WordReplacementStore.addReplacement(this, target, replacement)
                    refreshReplacementsList()
                    Toast.makeText(this, "Replacement added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_MODEL && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Toast.makeText(this, "Validating & importing model...", Toast.LENGTH_SHORT).show()

            Thread {
                val result = VoiceModelManager.importModelFromUri(this, uri)
                runOnUiThread {
                    if (result.isSuccess) {
                        Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_SHORT).show()
                        refreshModelCard()
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown import error"
                        AlertDialog.Builder(this)
                            .setTitle("Model Import Failed")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }.start()
        }
    }

    private fun handleIncomingModelIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data ?: return
            Toast.makeText(this, "Importing model from file...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = VoiceModelManager.importModelFromUri(this, uri)
                runOnUiThread {
                    if (result.isSuccess) {
                        Toast.makeText(this, "Model imported successfully!", Toast.LENGTH_LONG).show()
                        refreshModelCard()
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown import error"
                        AlertDialog.Builder(this)
                            .setTitle("Model Import Failed")
                            .setMessage(error)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }.start()
        }
    }

    private fun initRecommendedModelsViews() {
        containerRecommendedModels = findViewById(R.id.containerRecommendedModels)
        llDownloadProgressCard = findViewById(R.id.llDownloadProgressCard)
        tvDownloadProgressTitle = findViewById(R.id.tvDownloadProgressTitle)
        pbModelDownload = findViewById(R.id.pbModelDownload)
        tvDownloadProgressDetail = findViewById(R.id.tvDownloadProgressDetail)

        containerRecommendedModels.removeAllViews()
        val models = VoiceModelManager.RECOMMENDED_FUTO_MODELS

        for (model in models) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.btn_settings_pill_subtle)
                setPadding(32, 24, 32, 24)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 24
                }
                layoutParams = params
            }

            val tvTitle = TextView(this).apply {
                text = "${model.name} (${model.sizeDisplay})"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF0F172A.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val tvDesc = TextView(this).apply {
                text = model.description
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFF64748B.toInt())
                setPadding(0, 8, 0, 16)
            }

            val btnDownload = Button(this).apply {
                text = "Download & Install"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF2563EB.toInt())
                setOnClickListener {
                    startModelDownload(model)
                }
            }

            card.addView(tvTitle)
            card.addView(tvDesc)
            card.addView(btnDownload)
            containerRecommendedModels.addView(card)
        }
    }

    private fun startModelDownload(model: com.example.ime.voice.RecommendedModel) {
        llDownloadProgressCard.visibility = View.VISIBLE
        tvDownloadProgressTitle.text = "Downloading ${model.name}..."
        tvDownloadProgressDetail.text = "Starting download..."
        pbModelDownload.isIndeterminate = false
        pbModelDownload.progress = 0

        Thread {
            try {
                var currentUrl = model.downloadUrl
                var connection: java.net.HttpURLConnection
                var redirects = 0
                while (true) {
                    val url = java.net.URL(currentUrl)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 30000
                    val responseCode = connection.responseCode
                    if (responseCode in 300..399) {
                        val newUrl = connection.getHeaderField("Location")
                        if (newUrl.isNullOrEmpty() || redirects >= 5) {
                            throw Exception("Too many redirects or missing Location header")
                        }
                        currentUrl = newUrl
                        redirects++
                        connection.disconnect()
                    } else if (responseCode == 200) {
                        break
                    } else {
                        throw Exception("HTTP Error: $responseCode ${connection.responseMessage}")
                    }
                }

                val totalLength = connection.contentLength
                val tempFile = java.io.File(cacheDir, "download_${System.currentTimeMillis()}.bin")

                connection.inputStream.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalLength > 0) {
                                val progress = ((totalRead * 100) / totalLength).toInt()
                                val progressMb = totalRead / (1024 * 1024)
                                val totalMb = totalLength / (1024 * 1024)
                                runOnUiThread {
                                    pbModelDownload.progress = progress
                                    tvDownloadProgressDetail.text = "$progressMb MB / $totalMb MB ($progress%)"
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    tvDownloadProgressDetail.text = "Validating and installing model..."
                }

                val installResult = VoiceModelManager.installDownloadedFile(this, tempFile, model.name)

                runOnUiThread {
                    llDownloadProgressCard.visibility = View.GONE
                    if (installResult.isSuccess) {
                        Toast.makeText(this, "${model.name} installed successfully!", Toast.LENGTH_LONG).show()
                        refreshModelCard()
                    } else {
                        val err = installResult.exceptionOrNull()?.message ?: "Installation failed"
                        AlertDialog.Builder(this)
                            .setTitle("Install Failed")
                            .setMessage(err)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    llDownloadProgressCard.visibility = View.GONE
                    AlertDialog.Builder(this)
                        .setTitle("Download Failed")
                        .setMessage(e.message ?: "Network error downloading model")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }
}
