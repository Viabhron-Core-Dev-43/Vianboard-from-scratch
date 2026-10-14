package org.futo.voiceinput

import com.example.logger.LogKeeper

/**
 * Direct FUTO Voice Input binary compatibility class.
 * Mirrors the native interface and package structure of futo-org/voice-input's WhisperGGML.
 */
class WhisperGGML {
    companion object {
        private const val TAG = "FUTO_WhisperGGML"

        init {
            try {
                System.loadLibrary("whisper")
                LogKeeper.logEvent(TAG, "Native whisper library loaded for org.futo.voiceinput.WhisperGGML")
            } catch (e: UnsatisfiedLinkError) {
                LogKeeper.logWarning(TAG, "libwhisper.so not loaded: ${e.message}")
            } catch (t: Throwable) {
                LogKeeper.logError(TAG, "Error loading libwhisper.so", t.message ?: "")
            }
        }
    }

    external fun initContext(modelPath: String): Long

    external fun freeContext(contextPtr: Long)

    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        audioData: FloatArray
    ): String?

    external fun fullTranscribeWithParams(
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
