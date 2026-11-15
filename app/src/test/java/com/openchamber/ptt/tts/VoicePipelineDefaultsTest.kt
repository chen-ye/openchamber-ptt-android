package com.openchamber.ptt.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePipelineDefaultsTest {
    @Test
    fun `resolveActiveVoiceChipLabel follows top enabled engine`() {
        val geminiFirst = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = true),
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
        )
        assertEquals(
            "Gemini · Sulafat",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = geminiFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = "star02",
                openAiVoice = "onyx",
                systemVoiceLabel = "en-us-local",
            ),
        )

        val mlKitFirst = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false),
            TtsEngineOption(TtsEngine.MLKIT_GENAI, enabled = true),
        )
        assertEquals(
            "ML Kit · star02",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = mlKitFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = "star02",
                openAiVoice = "onyx",
                systemVoiceLabel = null,
            ),
        )

        val openAiFirst = listOf(
            TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false),
            TtsEngineOption(TtsEngine.OPENAI_CLOUD, enabled = true),
        )
        assertEquals(
            "OpenAI · onyx",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = openAiFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "onyx",
                systemVoiceLabel = null,
            ),
        )

        val kokoroFirst = listOf(
            TtsEngineOption(TtsEngine.LOCAL_KOKORO, enabled = true),
        )
        assertEquals(
            "Kokoro · Host",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = kokoroFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "alloy",
                systemVoiceLabel = null,
            ),
        )

        val systemFirst = listOf(
            TtsEngineOption(TtsEngine.ANDROID_SYSTEM, enabled = true),
        )
        assertEquals(
            "System · Default",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = systemFirst,
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "alloy",
                systemVoiceLabel = null,
            ),
        )

        assertEquals(
            "TTS disabled",
            VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                pipeline = listOf(TtsEngineOption(TtsEngine.GEMINI_BRIDGE, enabled = false)),
                geminiVoice = "Sulafat",
                mlKitVoice = null,
                openAiVoice = "alloy",
                systemVoiceLabel = null,
            ),
        )
    }

    @Test
    fun `describeMlKitVoice maps documented voice profiles`() {
        assertEquals(
            "Female · medium pitch · en-US",
            VoicePipelineDefaults.describeMlKitVoice("star02", "Female", "en-US"),
        )
        assertEquals(
            "Male · low pitch · en-US",
            VoicePipelineDefaults.describeMlKitVoice("star05", "Male", "en-US"),
        )
    }
}
