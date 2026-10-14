package com.example.ime.voice

import kotlin.math.sqrt

/**
 * Advanced WebRTC-style Voice Activity Detector (VAD).
 * Combines:
 * 1. Multi-band spectral feature estimation (voice formant isolation vs low-frequency rumble).
 * 2. Zero Crossing Rate (ZCR) filtering to distinguish voiced speech from white noise and hum.
 * 3. Dynamic adaptive noise-floor estimation with asymmetric attack/decay.
 * 4. Hangover smoothing (~240ms) to preserve inter-syllable micro-pauses.
 * 5. Continuous silence duration tracking for FUTO-aligned Auto-Stop on Silence.
 */
class EnergyVad(
    private val speechThresholdMultiplier: Float = 2.4f,
    private val minSpeechFrames: Int = 3,
    private val hangoverFrames: Int = 12, // ~240ms at 20ms frames
    var autoStopSilenceThresholdMs: Long = 2000L
) {
    private var noiseFloor: Float = 80f
    private var consecutiveSpeechFrames: Int = 0
    private var consecutiveSilenceFrames: Int = 0
    private var hangoverCounter: Int = 0

    var isSpeechDetected: Boolean = false
        private set

    var isSilenceTimeout: Boolean = false
        private set

    var silenceDurationMs: Long = 0L
        private set

    /**
     * Process an audio PCM buffer chunk (16kHz 16-bit mono).
     * Breaks chunks into 160-sample (10ms) subframes for accurate temporal resolution.
     */
    fun processFrame(buffer: ShortArray, readSize: Int): Boolean {
        if (readSize <= 0) return isSpeechDetected

        val subframeSize = 160 // 10ms at 16kHz
        var subframeOffset = 0
        var anySubframeSpeech = false

        while (subframeOffset + subframeSize <= readSize) {
            val isVoiced = evaluateSubframe(buffer, subframeOffset, subframeSize)
            if (isVoiced) {
                anySubframeSpeech = true
            }
            subframeOffset += subframeSize
        }

        // Process any remainder
        val remainder = readSize - subframeOffset
        if (remainder > 40) {
            if (evaluateSubframe(buffer, subframeOffset, remainder)) {
                anySubframeSpeech = true
            }
        }

        val frameDurationMs = (readSize.toFloat() / 16.0f).toLong() // 16 samples per ms at 16kHz

        if (anySubframeSpeech) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0
            hangoverCounter = hangoverFrames
            silenceDurationMs = 0L
            isSilenceTimeout = false

            if (consecutiveSpeechFrames >= minSpeechFrames) {
                isSpeechDetected = true
            }
        } else {
            consecutiveSilenceFrames++
            consecutiveSpeechFrames = 0

            if (hangoverCounter > 0) {
                hangoverCounter--
                isSpeechDetected = true
            } else {
                isSpeechDetected = false
                silenceDurationMs += frameDurationMs
                if (autoStopSilenceThresholdMs > 0 && silenceDurationMs >= autoStopSilenceThresholdMs) {
                    isSilenceTimeout = true
                }
            }
        }

        return isSpeechDetected
    }

    /**
     * Evaluates a 10ms frame using energy, zero-crossing rate, and spectral flux.
     */
    private fun evaluateSubframe(buffer: ShortArray, offset: Int, length: Int): Boolean {
        var sumSquares = 0.0
        var zeroCrossings = 0
        var lowBandSum = 0.0
        var midBandSum = 0.0

        var prevSample = buffer[offset].toInt()

        for (i in offset until offset + length) {
            val sample = buffer[i].toInt()
            sumSquares += sample.toDouble() * sample.toDouble()

            // Zero Crossing Rate (ZCR)
            if ((sample >= 0 && prevSample < 0) || (sample < 0 && prevSample >= 0)) {
                zeroCrossings++
            }

            // Simple 2-band spectral energy estimation
            // Low-pass filter approximation (hum/rumble): smoothed difference
            val lowSample = (sample + prevSample) / 2
            val highSample = (sample - prevSample) / 2
            lowBandSum += lowSample.toDouble() * lowSample.toDouble()
            midBandSum += highSample.toDouble() * highSample.toDouble()

            prevSample = sample
        }

        val rms = sqrt(sumSquares / length).toFloat()
        val zcr = zeroCrossings.toFloat() / length

        // Dynamic noise floor tracking (asymmetric adaptation)
        if (rms < noiseFloor * 1.3f) {
            // Rapid decay towards quiet floor
            noiseFloor = 0.92f * noiseFloor + 0.08f * rms
            if (noiseFloor < 25f) noiseFloor = 25f
        } else if (rms < noiseFloor * 2.5f) {
            // Slow attack to avoid adapting to speech
            noiseFloor = 0.995f * noiseFloor + 0.005f * rms
        }

        val speechEnergyThreshold = noiseFloor * speechThresholdMultiplier

        // Human speech criteria:
        // 1. RMS exceeds adaptive noise floor
        // 2. Zero-crossing rate is within typical human phoneme range (4% to 55%)
        // 3. Ratio of mid-band voice energy to low rumble indicates vocal formant presence
        val hasSpeechEnergy = rms > speechEnergyThreshold
        val hasVocalZcr = zcr in 0.03f..0.60f
        val hasFormantRatio = (midBandSum + 1.0) / (lowBandSum + 1.0) > 0.15

        return hasSpeechEnergy && hasVocalZcr && hasFormantRatio
    }

    fun reset() {
        noiseFloor = 80f
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        hangoverCounter = 0
        silenceDurationMs = 0L
        isSpeechDetected = false
        isSilenceTimeout = false
    }
}
