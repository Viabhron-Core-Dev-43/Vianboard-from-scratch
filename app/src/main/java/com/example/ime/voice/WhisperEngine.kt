package com.example.ime.voice

import com.example.logger.LogKeeper
import java.io.File

/**
 * JNI wrapper and lifecycle manager for on-device Whisper inference.
 * Bridges raw audio float samples to native C/C++ libwhisper runtime.
 */
class WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngine"

        @Volatile
        private var isLibraryLoaded: Boolean = false

        init {
            try {
                System.loadLibrary("whisper")
                isLibraryLoaded = true
                LogKeeper.logEvent(TAG, "Native whisper library (libwhisper.so) loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isLibraryLoaded = false
                LogKeeper.logWarning(TAG, "libwhisper.so not loaded (requires CI CMake build): ${e.message}")
            } catch (t: Throwable) {
                isLibraryLoaded = false
                LogKeeper.logError(TAG, "Unexpected error loading libwhisper.so", t.message ?: "")
            }
        }

        fun isNativeAvailable(): Boolean = isLibraryLoaded

        /**
         * Strips common Whisper hallucination artifacts and control tokens.
         * If suppressNonSpeech is enabled, removes [cough], [music], etc.
         * Collapses repetitive token loops and strips phantom silence phrases.
         */
        fun cleanWhisperOutput(raw: String, suppressNonSpeech: Boolean = true): String {
            var text = raw.replace(Regex("<\\|.*?\\|>"), "")
            if (suppressNonSpeech) {
                text = text
                    .replace(Regex("\\[.*?\\]"), "")
                    .replace(Regex("\\(.*?\\)"), "")
                    .replace(Regex("\\*.*?\\*"), "")
            } else {
                text = text.replace("[BLANK_AUDIO]", "")
            }
            text = text.replace(Regex("\\s+"), " ").trim()

            // Filter out repetitive hallucination loops
            text = filterRepetitionLoops(text)

            // Filter out common Whisper phantom silence hallucinations
            text = filterPhantomPhrases(text)

            return text.replace(Regex("\\s+"), " ").trim()
        }

        /**
         * Detects consecutive repeating n-grams (1-word, 2-word, 3-word loops)
         * that Whisper generates on silence or noisy background, and collapses them.
         */
        fun filterRepetitionLoops(text: String): String {
            if (text.isBlank()) return text
            val words = text.split(" ").filter { it.isNotBlank() }
            if (words.size < 4) return text

            val cleaned = ArrayList<String>()
            var i = 0
            while (i < words.size) {
                var foundLoop = false
                // Check n-gram lengths from 4 down to 1
                val maxNgram = minOf(4, (words.size - i) / 2)
                for (ngramSize in maxNgram downTo 1) {
                    val pattern = words.subList(i, i + ngramSize)
                    var repeatCount = 1
                    var nextIdx = i + ngramSize

                    while (nextIdx + ngramSize <= words.size) {
                        val candidate = words.subList(nextIdx, nextIdx + ngramSize)
                        val isMatch = pattern.indices.all { idx ->
                            pattern[idx].equals(candidate[idx], ignoreCase = true)
                        }
                        if (isMatch) {
                            repeatCount++
                            nextIdx += ngramSize
                        } else {
                            break
                        }
                    }

                    if (repeatCount >= 3) {
                        // Collapse consecutive 3+ repetitions down to a single instance
                        cleaned.addAll(pattern)
                        i = nextIdx
                        foundLoop = true
                        break
                    }
                }
                if (!foundLoop) {
                    cleaned.add(words[i])
                    i++
                }
            }
            return cleaned.joinToString(" ")
        }

        private fun filterPhantomPhrases(text: String): String {
            val lower = text.lowercase().trim().trim('.', '!', '?', ',', ' ')
            val phantomPhrases = setOf(
                "thank you",
                "thank you for watching",
                "thank you very much",
                "thanks for watching",
                "please subscribe",
                "subscribe to my channel",
                "subtitles by",
                "transcribed by",
                "amara.org",
                "mbc",
                "you",
                "bye",
                "goodbye"
            )
            // If the whole utterance is just a known phantom hallucination on silence, discard it
            if (lower in phantomPhrases) {
                return ""
            }
            return text
        }
    }

    private var contextPtr: Long = 0L

    val isModelLoaded: Boolean
        get() = contextPtr != 0L

    @Synchronized
    fun loadModel(modelFile: File): Boolean {
        if (!isLibraryLoaded) {
            LogKeeper.logWarning(TAG, "Cannot load model: native libwhisper.so is not available")
            return false
        }
        if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
            LogKeeper.logError(TAG, "Model file does not exist or is invalid (<1MB)", modelFile.absolutePath)
            return false
        }

        // Release prior model if active
        if (contextPtr != 0L) {
            release()
        }

        return try {
            val startTime = System.currentTimeMillis()
            LogKeeper.logEvent(TAG, "Initializing Whisper context from file (size: ${modelFile.length()} bytes)")
            val ptr = initContext(modelFile.absolutePath)
            val elapsed = System.currentTimeMillis() - startTime

            if (ptr != 0L) {
                contextPtr = ptr
                LogKeeper.logEvent(TAG, "Whisper context initialized successfully in ${elapsed}ms (ptr=$ptr)")
                true
            } else {
                LogKeeper.logError(TAG, "Native initContext returned null pointer for model", modelFile.name)
                false
            }
        } catch (t: Throwable) {
            LogKeeper.logError(TAG, "Exception during native model initialization", t.message ?: "")
            false
        }
    }

    @Synchronized
    fun transcribe(
        audioSamples: FloatArray,
        numThreads: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
        suppressNonSpeech: Boolean = true,
        verbose: Boolean = false,
        temperature: Float = 0.0f,
        useBeamSearch: Boolean = false,
        language: String = "auto",
        initialPrompt: String? = null
    ): String? {
        if (contextPtr == 0L) {
            LogKeeper.logWarning(TAG, "transcribe called without active model context")
            return null
        }
        if (audioSamples.isEmpty()) {
            return null
        }

        return try {
            val startTime = System.currentTimeMillis()
            val rawResult = try {
                fullTranscribeWithParams(
                    contextPtr,
                    numThreads,
                    temperature,
                    useBeamSearch,
                    suppressNonSpeech,
                    language,
                    initialPrompt,
                    audioSamples
                )
            } catch (_: UnsatisfiedLinkError) {
                // Fallback to basic fullTranscribe if older .so is loaded
                fullTranscribe(contextPtr, numThreads, audioSamples)
            }
            val elapsed = System.currentTimeMillis() - startTime

            if (verbose) {
                val durationSec = audioSamples.size.toFloat() / 16000f
                LogKeeper.logEvent(TAG, "Verbose Inference: audio=${String.format(java.util.Locale.US, "%.2f", durationSec)}s, samples=${audioSamples.size}, latency=${elapsed}ms, threads=$numThreads, temp=$temperature, beam=$useBeamSearch, prompt=${initialPrompt?.take(20) ?: "none"}")
            } else {
                LogKeeper.logEvent(TAG, "Inference completed in ${elapsed}ms")
            }

            if (rawResult != null) {
                cleanWhisperOutput(rawResult, suppressNonSpeech)
            } else {
                null
            }
        } catch (t: Throwable) {
            LogKeeper.logError(TAG, "Exception during native inference", t.message ?: "")
            null
        }
    }

    @Synchronized
    fun release() {
        if (contextPtr != 0L) {
            try {
                LogKeeper.logEvent(TAG, "Releasing Whisper context (ptr=$contextPtr)")
                freeContext(contextPtr)
            } catch (t: Throwable) {
                LogKeeper.logError(TAG, "Exception releasing Whisper context", t.message ?: "")
            } finally {
                contextPtr = 0L
            }
        }
    }

    // --- Native JNI Method Declarations ---
    // Note: mapped to existing JNI signature in jni_whisper.cpp
    private external fun initContext(modelPath: String): Long
    private external fun freeContext(contextPtr: Long)
    private external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray): String?
    private external fun fullTranscribeWithParams(
        contextPtr: Long,
        numThreads: Int,
        temperature: Float,
        useBeamSearch: Boolean,
        suppressNonSpeech: Boolean,
        language: String,
        initialPrompt: String?,
        audioData: FloatArray
    ): String?
}
