package com.openchamber.ptt.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PttForegroundServiceHelpersTest {
    @Test
    fun `audio capture constants match 16kHz mono PCM16LE 200ms chunks and 90s cap`() {
        assertEquals(16_000, PttForegroundService.SAMPLE_RATE_HZ)
        assertEquals(200, PttForegroundService.CHUNK_DURATION_MS)
        assertEquals(6_400, PttForegroundService.CHUNK_SIZE_BYTES)
        assertEquals(90_000L, PttForegroundService.MAX_UTTERANCE_MS)
        assertEquals(150L, PttForegroundService.BARGE_IN_COOLDOWN_MS)
    }

    @Test
    fun `formatPermissionAlertSpeech builds expected prompt`() {
        assertEquals(
            "Tool permission needed on screen for bash",
            PttForegroundService.formatPermissionAlertSpeech("bash"),
        )
        assertEquals(
            "Tool permission needed on screen for unknown",
            PttForegroundService.formatPermissionAlertSpeech("   "),
        )
    }

    @Test
    fun `remainingBargeInCooldownMs enforces 150ms window after TTS stop`() {
        assertEquals(0L, PttForegroundService.remainingBargeInCooldownMs(0L, 1_000L))
        assertEquals(150L, PttForegroundService.remainingBargeInCooldownMs(1_000L, 1_000L))
        assertEquals(75L, PttForegroundService.remainingBargeInCooldownMs(1_000L, 1_075L))
        assertEquals(0L, PttForegroundService.remainingBargeInCooldownMs(1_000L, 1_150L))
        assertEquals(0L, PttForegroundService.remainingBargeInCooldownMs(1_000L, 2_000L))
    }

    @Test
    fun `formatVoicePrompt prepends short voice tag and exposes 4s permission delay`() {
        assertEquals(4_000L, PttForegroundService.PERMISSION_ALERT_DELAY_MS)
        assertEquals("[voice]", PttForegroundService.VOICE_PROMPT_PREFIX)
        assertEquals(
            "[voice]\ncheck git status",
            PttForegroundService.formatVoicePrompt("  check git status  "),
        )
        assertEquals("", PttForegroundService.formatVoicePrompt("   "))
    }
}
