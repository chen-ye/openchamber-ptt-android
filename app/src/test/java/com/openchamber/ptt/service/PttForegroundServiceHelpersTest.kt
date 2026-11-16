package com.openchamber.ptt.service

import com.openchamber.ptt.storage.PinnedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttForegroundServiceHelpersTest {
    @Test
    fun `audio capture constants match 16kHz mono PCM16LE 200ms chunks and 5m safety cap`() {
        assertEquals(16_000, PttForegroundService.SAMPLE_RATE_HZ)
        assertEquals(200, PttForegroundService.CHUNK_DURATION_MS)
        assertEquals(6_400, PttForegroundService.CHUNK_SIZE_BYTES)
        assertEquals(300_000L, PttForegroundService.MAX_UTTERANCE_MS)
        assertEquals(285_000L, PttForegroundService.MAX_UTTERANCE_WARNING_MS)
        assertEquals(2_500L, PttForegroundService.SEND_GRACE_MS)
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

    @Test
    fun `formatSpeechSessionLabel prefers title and falls back to directory basename`() {
        assertEquals(
            "Payments refactor",
            PttForegroundService.formatSpeechSessionLabel(
                PinnedSession(
                    sessionId = "s1",
                    directory = "/home/user/payments",
                    title = "  Payments refactor  ",
                ),
            ),
        )
        assertEquals(
            "openchamber-ptt-android",
            PttForegroundService.formatSpeechSessionLabel(
                PinnedSession(
                    sessionId = "s2",
                    directory = "/home/dev/Projects/openchamber-ptt-android/",
                    title = "   ",
                ),
            ),
        )
        assertNull(
            PttForegroundService.formatSpeechSessionLabel(
                PinnedSession(sessionId = "s3", directory = "  ", title = ""),
            ),
        )
    }

    @Test
    fun `shouldCancelTurnOnIdleReturn cancels abandoned turns for both ARMED_IDLE and STAGED_DRAFT`() {
        val armToStaged = PttTransition(
            previous = PttState.PERMISSION_BLOCKED,
            current = PttState.STAGED_DRAFT,
            event = PttEvent.Arm(hasPersistedDraft = true),
            effects = emptyList(),
        )
        val bargeInToStaged = PttTransition(
            previous = PttState.SPEAKING_TTS,
            current = PttState.STAGED_DRAFT,
            event = PttEvent.SingleTap,
            effects = emptyList(),
        )
        val armToIdle = PttTransition(
            previous = PttState.PERMISSION_BLOCKED,
            current = PttState.ARMED_IDLE,
            event = PttEvent.Arm(hasPersistedDraft = false),
            effects = emptyList(),
        )
        val normalPullBack = PttTransition(
            previous = PttState.SEND_GRACE,
            current = PttState.STAGED_DRAFT,
            event = PttEvent.SingleTap,
            effects = emptyList(),
        )

        assertTrue(PttForegroundService.shouldCancelTurnOnIdleReturn(armToStaged))
        assertTrue(PttForegroundService.shouldCancelTurnOnIdleReturn(bargeInToStaged))
        assertTrue(PttForegroundService.shouldCancelTurnOnIdleReturn(armToIdle))
        assertFalse(PttForegroundService.shouldCancelTurnOnIdleReturn(normalPullBack))
    }
}
