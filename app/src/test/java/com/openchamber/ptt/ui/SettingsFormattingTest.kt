package com.openchamber.ptt.ui

import com.openchamber.ptt.audio.CuePackWarmState
import com.openchamber.ptt.tts.TtsCacheStats
import com.openchamber.ptt.tts.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFormattingTest {
    @Test
    fun `formatCueWarmStatus covers warming, cached, and idle states`() {
        assertEquals(
            "Warming vocal cues: 5/18…",
            formatCueWarmStatus(
                CuePackWarmState(
                    isWarming = true,
                    warmedCount = 5,
                    totalCount = 18,
                    engine = TtsEngine.GEMINI_BRIDGE,
                    voiceId = "Zephyr",
                ),
            ),
        )
        assertEquals(
            "Vocal cues cached: 18/18 (Gemini · Zephyr)",
            formatCueWarmStatus(
                CuePackWarmState(
                    isWarming = false,
                    warmedCount = 18,
                    totalCount = 18,
                    engine = TtsEngine.GEMINI_BRIDGE,
                    voiceId = "Zephyr",
                ),
            ),
        )
        assertEquals(
            "Vocal cues warm automatically when armed",
            formatCueWarmStatus(
                CuePackWarmState(
                    isWarming = false,
                    warmedCount = 0,
                    totalCount = 18,
                    engine = TtsEngine.GEMINI_BRIDGE,
                    voiceId = "Zephyr",
                ),
            ),
        )
    }

    @Test
    fun `formatTtsCacheStats covers empty, singular, and plural stats`() {
        assertEquals(
            "Empty · summaries and synthesized audio are cached for instant offline re-reads",
            formatTtsCacheStats(TtsCacheStats(0, 0L, 0, 0L)),
        )
        assertEquals(
            "1.5 MB · 1 summary · 1 audio clip",
            formatTtsCacheStats(
                TtsCacheStats(
                    textEntries = 1,
                    textBytes = 1024L,
                    audioEntries = 1,
                    audioBytes = (1.5 * 1024 * 1024).toLong() - 1024L,
                ),
            ),
        )
        assertEquals(
            "12.3 MB · 4 summaries · 22 audio clips",
            formatTtsCacheStats(
                TtsCacheStats(
                    textEntries = 4,
                    textBytes = 4_096L,
                    audioEntries = 22,
                    audioBytes = (12.3 * 1024 * 1024).toLong() - 4_096L,
                ),
            ),
        )
    }
}
