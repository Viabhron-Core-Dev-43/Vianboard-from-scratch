package com.example.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class VoiceEngineTest {

    @Test
    fun testWhisperOutputCleaning_removesSpecialTokensAndHallucinations() {
        val raw = "<|startoftranscript|><|en|><|transcribe|>[cough] Hello world (music) *applause* <|endoftranscript|>"
        val cleaned = WhisperEngine.cleanWhisperOutput(raw, suppressNonSpeech = true)
        assertEquals("Hello world", cleaned)
    }

    @Test
    fun testWhisperOutputCleaning_collapsesRepetitionLoops() {
        val looped = "Hello there how are you how are you how are you how are you today"
        val cleaned = WhisperEngine.cleanWhisperOutput(looped, suppressNonSpeech = true)
        assertEquals("Hello there how are you today", cleaned)
    }

    @Test
    fun testWhisperOutputCleaning_filtersSilencePhantomPhrases() {
        val phantom1 = "Thank you for watching."
        val cleaned1 = WhisperEngine.cleanWhisperOutput(phantom1, suppressNonSpeech = true)
        assertEquals("", cleaned1)

        val phantom2 = "Please subscribe!"
        val cleaned2 = WhisperEngine.cleanWhisperOutput(phantom2, suppressNonSpeech = true)
        assertEquals("", cleaned2)

        val validSpeech = "Thank you for your help with the project."
        val cleaned3 = WhisperEngine.cleanWhisperOutput(validSpeech, suppressNonSpeech = true)
        assertEquals("Thank you for your help with the project.", cleaned3)
    }

    @Test
    fun testModelHeaderValidation_validatesMagicAndFallbackSize() {
        val tempDir = File(System.getProperty("java.io.tmpdir") ?: ".", "voice_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        try {
            // 1. Valid GGML little-endian file (lmgg)
            val ggmlFile = File(tempDir, "ggml.bin")
            FileOutputStream(ggmlFile).use { it.write(byteArrayOf(0x6c, 0x6d, 0x67, 0x67, 0x01, 0x02)) }
            assertTrue(VoiceModelManager.isValidModelHeader(ggmlFile))

            // 2. Valid GGUF file
            val ggufFile = File(tempDir, "gguf.bin")
            FileOutputStream(ggufFile).use { it.write(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x01, 0x02)) }
            assertTrue(VoiceModelManager.isValidModelHeader(ggufFile))

            // 3. Invalid header small file (< 1MB)
            val invalidSmall = File(tempDir, "invalid.bin")
            FileOutputStream(invalidSmall).use { it.write(byteArrayOf(0x00, 0x01, 0x02, 0x03)) }
            assertFalse(VoiceModelManager.isValidModelHeader(invalidSmall))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testFormatFileSize_formatsAccurately() {
        assertEquals("0 B", VoiceModelManager.formatFileSize(0))
        assertEquals("1 KB", VoiceModelManager.formatFileSize(1024))
        assertEquals("1 MB", VoiceModelManager.formatFileSize(1024 * 1024))
        assertEquals("75 MB", VoiceModelManager.formatFileSize(75L * 1024 * 1024))
    }
}
