package com.example.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.example.logger.LogKeeper
import kotlin.math.sqrt

/**
 * High-performance, low-latency audio capture pipeline for speech recognition.
 * Captures 16kHz 16-bit mono PCM, applies 3-stage digital gain with anti-clipping limiter,
 * calculates smoothed RMS levels, handles AudioFocus/Bluetooth SCO, and detects speech activity via EnergyVad.
 */
class AudioRecordPipeline(
    private val context: Context? = null,
    private val listener: AudioPipelineListener
) {
    constructor(listener: AudioPipelineListener) : this(null, listener)

    companion object {
        private const val TAG = "AudioRecordPipeline"
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RMS_DISPATCH_INTERVAL_MS = 40L
    }

    interface AudioPipelineListener {
        fun onRmsChanged(rms: Float)
        fun onSpeechActivityChanged(isSpeaking: Boolean)
        fun onAudioChunkAvailable(buffer: ShortArray, readSize: Int)
        fun onSilenceTimeout()
        fun onError(message: String)
    }

    @Volatile
    private var isRunning: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    var gainMultiplier: Int = 1
        set(value) {
            field = if (value in listOf(1, 2, 4)) value else 1
        }

    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val energyVad = EnergyVad()
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: Any? = null

    var autoStopSilenceThresholdMs: Long
        get() = energyVad.autoStopSilenceThresholdMs
        set(value) {
            energyVad.autoStopSilenceThresholdMs = value
        }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (minBufferSize <= 0) {
            listener.onError("Audio hardware does not support 16kHz mono recording")
            return false
        }

        val bufferSize = maxOf(minBufferSize * 2, SAMPLE_RATE_HZ / 10 * 2) // ~100ms buffer

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                // Fallback to default MIC source
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    audioRecord = null
                    listener.onError("Failed to initialize AudioRecord")
                    return false
                }
            }

            requestAudioFocus()
            startBluetoothScoIfAvailable()

            audioRecord?.startRecording()
            isRunning = true
            isPaused = false
            energyVad.reset()

            workerThread = Thread({ recordLoop(bufferSize) }, "VianAudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            LogKeeper.logEvent(TAG, "Audio recording started (gain=${gainMultiplier}x)")
            return true
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception starting AudioRecord", e.message ?: "")
            release()
            listener.onError(e.message ?: "AudioRecord start failed")
            return false
        }
    }

    fun pause() {
        isPaused = true
        LogKeeper.logEvent(TAG, "Audio recording paused")
    }

    fun resume() {
        isPaused = false
        energyVad.reset()
        LogKeeper.logEvent(TAG, "Audio recording resumed")
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        isPaused = false

        try {
            workerThread?.interrupt()
            workerThread?.join(500)
        } catch (ignored: InterruptedException) {
        } finally {
            workerThread = null
        }

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            LogKeeper.logError(TAG, "Exception stopping AudioRecord", e.message ?: "")
        } finally {
            audioRecord = null
            stopBluetoothSco()
            abandonAudioFocus()
        }

        LogKeeper.logEvent(TAG, "Audio recording stopped")
    }

    fun release() {
        stop()
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* transient capture */ }
                    .build()
                audioFocusRequest = focusReq
                am.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
            LogKeeper.logEvent(TAG, "Audio focus acquired for speech recognition")
        } catch (e: Exception) {
            LogKeeper.logWarning(TAG, "Could not acquire audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (audioFocusRequest as? AudioFocusRequest)?.let {
                    am.abandonAudioFocusRequest(it)
                }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            LogKeeper.logEvent(TAG, "Audio focus released")
        } catch (e: Exception) {
            LogKeeper.logWarning(TAG, "Could not release audio focus: ${e.message}")
        }
    }

    private fun startBluetoothScoIfAvailable() {
        val am = audioManager ?: return
        try {
            if (am.isBluetoothScoAvailableOffCall) {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                LogKeeper.logEvent(TAG, "Bluetooth SCO audio link started")
            }
        } catch (e: Exception) {
            LogKeeper.logWarning(TAG, "Failed to activate Bluetooth SCO: ${e.message}")
        }
    }

    private fun stopBluetoothSco() {
        val am = audioManager ?: return
        try {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                LogKeeper.logEvent(TAG, "Bluetooth SCO audio link stopped")
            }
        } catch (e: Exception) {
            LogKeeper.logWarning(TAG, "Failed to stop Bluetooth SCO: ${e.message}")
        }
    }

    private fun recordLoop(bufferSize: Int) {
        val readChunkSize = SAMPLE_RATE_HZ / 20 // 50ms chunk = 800 shorts
        val buffer = ShortArray(readChunkSize)
        var lastRmsDispatchTime = 0L
        var smoothedRms = 0f

        while (isRunning) {
            if (Thread.currentThread().isInterrupted) break

            if (isPaused) {
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            val recorder = audioRecord ?: break
            val readCount = recorder.read(buffer, 0, buffer.size)

            if (readCount <= 0) {
                if (readCount == AudioRecord.ERROR_INVALID_OPERATION ||
                    readCount == AudioRecord.ERROR_BAD_VALUE
                ) {
                    listener.onError("AudioRecord read error: $readCount")
                    break
                }
                continue
            }

            // Apply digital gain with peak limiter
            val gain = gainMultiplier
            if (gain > 1) {
                for (i in 0 until readCount) {
                    val amplified = buffer[i] * gain
                    buffer[i] = amplified.coerceIn(
                        Short.MIN_VALUE.toInt(),
                        Short.MAX_VALUE.toInt()
                    ).toShort()
                }
            }

            // Calculate RMS
            var sumSquares = 0.0
            for (i in 0 until readCount) {
                val sample = buffer[i].toDouble()
                sumSquares += sample * sample
            }
            val instantRms = (sqrt(sumSquares / readCount) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
            smoothedRms = smoothedRms * 0.6f + instantRms * 0.4f

            val now = System.currentTimeMillis()
            if (now - lastRmsDispatchTime >= RMS_DISPATCH_INTERVAL_MS) {
                lastRmsDispatchTime = now
                listener.onRmsChanged(smoothedRms)
            }

            // Voice Activity Detection
            val wasSpeaking = energyVad.isSpeechDetected
            val wasSilenceTimeout = energyVad.isSilenceTimeout
            val isSpeakingNow = energyVad.processFrame(buffer, readCount)
            val isSilenceTimeoutNow = energyVad.isSilenceTimeout

            if (wasSpeaking != isSpeakingNow) {
                listener.onSpeechActivityChanged(isSpeakingNow)
            }

            if (!wasSilenceTimeout && isSilenceTimeoutNow) {
                listener.onSilenceTimeout()
            }

            // Pass chunk to listener for buffering/inference
            listener.onAudioChunkAvailable(buffer, readCount)
        }
    }
}
