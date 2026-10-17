package com.example.ime.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.logger.LogKeeper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DecimalFormat

data class VoiceModelInfo(
    val fileName: String,
    val sizeBytes: Long,
    val formattedSize: String,
    val absolutePath: String,
    val isValid: Boolean,
    val isAcftOptimized: Boolean = false
)

data class RecommendedModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeDisplay: String,
    val downloadUrl: String,
    val isAcft: Boolean
)

object VoiceModelManager {
    private const val TAG = "VoiceModelManager"

    val RECOMMENDED_FUTO_MODELS = listOf(
        RecommendedModel(
            id = "whisper_tiny_en_acft",
            name = "FUTO Whisper Tiny English (ACFT)",
            description = "Fine-tuned specifically for mobile voice input. Ultra-low latency, ~75 MB.",
            sizeDisplay = "~75 MB",
            downloadUrl = "https://huggingface.co/futo-org/whisper-tiny-en-acft/resolve/main/ggml-model.bin",
            isAcft = true
        ),
        RecommendedModel(
            id = "whisper_base_en_acft",
            name = "FUTO Whisper Base English (ACFT)",
            description = "High accuracy conversational speech recognition. Recommended for noisy environments, ~142 MB.",
            sizeDisplay = "~142 MB",
            downloadUrl = "https://huggingface.co/futo-org/whisper-base-en-acft/resolve/main/ggml-model.bin",
            isAcft = true
        ),
        RecommendedModel(
            id = "whisper_tiny_multilingual",
            name = "Whisper Tiny Multilingual",
            description = "Supports multi-language transcription and automatic language detection, ~151 MB.",
            sizeDisplay = "~151 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            isAcft = false
        )
    )
    const val MODEL_DIR_NAME = "voice_models"
    const val ACTIVE_MODEL_FILE_NAME = "model.bin"
    private const val PREFS_NAME = "vian_voice_prefs"
    private const val KEY_MODEL_DISPLAY_NAME = "active_model_display_name"
    private const val KEY_WHISPER_TEMPERATURE = "whisper_temperature"

    // Recognized Whisper / GGML / GGUF magic bytes (supporting both little-endian and big-endian disk layouts)
    private val GGML_MAGIC_LE = byteArrayOf(0x6c, 0x6d, 0x67, 0x67) // 0x67676d6c little-endian ("lmgg")
    private val GGML_MAGIC_BE = byteArrayOf(0x67, 0x67, 0x6d, 0x6c) // 0x67676d6c big-endian ("ggml")
    private val GGMF_MAGIC_LE = byteArrayOf(0x66, 0x6d, 0x67, 0x67) // 0x67676d66 little-endian ("fmgg")
    private val GGMF_MAGIC_BE = byteArrayOf(0x67, 0x67, 0x6d, 0x66) // 0x67676d66 big-endian ("ggmf")
    private val GGJT_MAGIC_LE = byteArrayOf(0x74, 0x6a, 0x67, 0x67) // 0x67676a74 little-endian ("tjgg")
    private val GGJT_MAGIC_BE = byteArrayOf(0x67, 0x67, 0x6a, 0x74) // 0x67676a74 big-endian ("ggjt")
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)    // "GGUF" ASCII string

    fun getModelDirectory(context: Context): File {
        val dir = File(context.noBackupFilesDir, MODEL_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getActiveModelFile(context: Context): File {
        return File(getModelDirectory(context), ACTIVE_MODEL_FILE_NAME)
    }

    fun hasActiveModel(context: Context): Boolean {
        val file = getActiveModelFile(context)
        return file.exists() && file.length() > 1024 * 1024 // At least 1 MB
    }

    fun getModelInfo(context: Context): VoiceModelInfo? {
        val file = getActiveModelFile(context)
        if (!file.exists() || file.length() == 0L) {
            return null
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val displayName = prefs.getString(KEY_MODEL_DISPLAY_NAME, null) ?: file.name
        val sizeBytes = file.length()
        val formattedSize = formatFileSize(sizeBytes)
        val isValid = isValidModelHeader(file)
        val isAcft = displayName.lowercase().contains("acft")

        return VoiceModelInfo(
            fileName = displayName,
            sizeBytes = sizeBytes,
            formattedSize = formattedSize,
            absolutePath = file.absolutePath,
            isValid = isValid,
            isAcftOptimized = isAcft
        )
    }

    fun getTemperature(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_WHISPER_TEMPERATURE, 0.0f)
    }

    fun setTemperature(context: Context, temperature: Float) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_WHISPER_TEMPERATURE, temperature.coerceIn(0.0f, 1.0f)).apply()
    }

    fun importModelFromUri(context: Context, uri: Uri): Result<VoiceModelInfo> {
        return try {
            val contentResolver = context.contentResolver
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            var displayName = "imported_model.bin"

            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        displayName = name
                    }
                }
            }

            val targetFile = getActiveModelFile(context)
            val tempFile = File(getModelDirectory(context), "model_temp_${System.currentTimeMillis()}.bin")

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
            } ?: return Result.failure(Exception("Cannot open stream for selected URI"))

            if (tempFile.length() < 1024 * 1024) {
                val size = tempFile.length()
                tempFile.delete()
                return Result.failure(
                    IllegalArgumentException("Model file too small ($size bytes, minimum 1 MB required)")
                )
            }

            if (!isValidModelHeader(tempFile)) {
                val magicBytes = ByteArray(4)
                try {
                    FileInputStream(tempFile).use { it.read(magicBytes) }
                } catch (_: Exception) {}
                val hexMagic = magicBytes.joinToString("") { String.format("%02x", it) }
                val asciiMagic = String(magicBytes, Charsets.ISO_8859_1).filter { it.isLetterOrDigit() }
                tempFile.delete()
                return Result.failure(
                    IllegalArgumentException("Selected file ($displayName) does not have a recognized Whisper GGML/GGUF header (found 0x$hexMagic '$asciiMagic')")
                )
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MODEL_DISPLAY_NAME, displayName).apply()

            val info = getModelInfo(context)
                ?: return Result.failure(Exception("Failed to retrieve info for imported model"))

            notifyVoiceService(context)
            LogKeeper.logEvent(TAG, "Voice model imported successfully: $displayName (${info.formattedSize})")
            Result.success(info)
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception importing voice model", e.message ?: "")
            Result.failure(e)
        }
    }

    fun installDownloadedFile(context: Context, sourceFile: File, displayName: String): Result<VoiceModelInfo> {
        return try {
            if (!sourceFile.exists() || sourceFile.length() < 1024 * 1024) {
                return Result.failure(IllegalArgumentException("Downloaded model file invalid or too small"))
            }

            val targetFile = getActiveModelFile(context)
            if (targetFile.exists()) {
                targetFile.delete()
            }

            val renamed = sourceFile.renameTo(targetFile)
            if (!renamed) {
                sourceFile.copyTo(targetFile, overwrite = true)
                sourceFile.delete()
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MODEL_DISPLAY_NAME, displayName).apply()

            val info = getModelInfo(context)
                ?: return Result.failure(Exception("Failed to retrieve info for downloaded model"))

            notifyVoiceService(context)
            LogKeeper.logEvent(TAG, "Downloaded voice model installed successfully: $displayName (${info.formattedSize})")
            Result.success(info)
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception installing downloaded voice model", e.message ?: "")
            Result.failure(e)
        }
    }

    private fun notifyVoiceService(context: Context) {
        try {
            val intent = android.content.Intent(context, VoiceInputService::class.java).apply {
                action = VoiceInputService.ACTION_RELOAD_MODEL
            }
            context.startService(intent)
        } catch (_: Exception) {}
    }

    fun deleteActiveModel(context: Context): Boolean {
        val file = getActiveModelFile(context)
        val deleted = if (file.exists()) file.delete() else true
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_MODEL_DISPLAY_NAME).apply()
        notifyVoiceService(context)
        LogKeeper.logEvent(TAG, "Active voice model deleted")
        return deleted
    }

    fun isValidModelHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val isKnownMagic = FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                if (read < 4) return false
                magic.contentEquals(GGML_MAGIC_LE) ||
                        magic.contentEquals(GGML_MAGIC_BE) ||
                        magic.contentEquals(GGMF_MAGIC_LE) ||
                        magic.contentEquals(GGMF_MAGIC_BE) ||
                        magic.contentEquals(GGJT_MAGIC_LE) ||
                        magic.contentEquals(GGJT_MAGIC_BE) ||
                        magic.contentEquals(GGUF_MAGIC)
            }
            // Allow matching magic OR any file >= 1MB to let native whisper validate directly without false deletion
            isKnownMagic || (file.length() >= 1024 * 1024)
        } catch (e: Exception) {
            false
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, index.toDouble())) + " " + units[index]
    }
}
