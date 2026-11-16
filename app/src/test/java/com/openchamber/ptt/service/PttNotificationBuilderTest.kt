package com.openchamber.ptt.service

import com.openchamber.ptt.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttNotificationBuilderTest {
    @Test
    fun `statusTextResForState maps every PttState to expected string resource`() {
        val mapped = PttState.entries.associateWith { PttNotificationBuilder.statusTextResForState(it) }
        assertEquals(R.string.ptt_status_disarmed, mapped[PttState.DISARMED])
        assertEquals(R.string.ptt_status_armed_idle, mapped[PttState.ARMED_IDLE])
        assertEquals(R.string.ptt_status_recording, mapped[PttState.RECORDING])
        assertEquals(R.string.ptt_status_transcribing, mapped[PttState.TRANSCRIBING_SEND])
        assertEquals(R.string.ptt_status_organizing_draft, mapped[PttState.TRANSCRIBING_STAGE])
        assertEquals(R.string.ptt_status_send_grace, mapped[PttState.SEND_GRACE])
        assertEquals(R.string.ptt_status_staged_draft, mapped[PttState.STAGED_DRAFT])
        assertEquals(R.string.ptt_status_staged_draft_speaking, mapped[PttState.STAGED_DRAFT_SPEAKING])
        assertEquals(R.string.ptt_status_waiting_agent, mapped[PttState.WAITING_AGENT])
        assertEquals(R.string.ptt_status_speaking_tts, mapped[PttState.SPEAKING_TTS])
        assertEquals(R.string.ptt_status_permission_blocked, mapped[PttState.PERMISSION_BLOCKED])
        assertEquals(R.string.ptt_status_error, mapped[PttState.ERROR])
        assertEquals(PttState.entries.size, mapped.values.toSet().size)
    }

    @Test
    fun `actionsForState builds expected actions and omits disarm when promoted for standalone TTS`() {
        val disarmedActions = PttNotificationBuilder.actionsForState(PttState.DISARMED, promotedForStandaloneTts = false)
        assertEquals(listOf(PttForegroundService.ACTION_ARM), disarmedActions.map { it.action })
        assertEquals(listOf(PttNotificationBuilder.REQUEST_CODE_ARM), disarmedActions.map { it.requestCode })

        val recordingActions = PttNotificationBuilder.actionsForState(PttState.RECORDING, promotedForStandaloneTts = false)
        assertEquals(
            listOf(
                PttForegroundService.ACTION_TOGGLE_PTT,
                PttForegroundService.ACTION_STAGE_CAPTURE,
                PttForegroundService.ACTION_DISCARD_CAPTURE,
            ),
            recordingActions.map { it.action },
        )

        val graceActions = PttNotificationBuilder.actionsForState(PttState.SEND_GRACE, promotedForStandaloneTts = false)
        assertEquals(
            listOf(
                PttForegroundService.ACTION_PULL_BACK_DRAFT,
                PttForegroundService.ACTION_DISCARD_CAPTURE,
            ),
            graceActions.map { it.action },
        )

        val stagedQuiet = PttNotificationBuilder.actionsForState(PttState.STAGED_DRAFT, promotedForStandaloneTts = false)
        assertEquals(R.string.ptt_action_append, stagedQuiet.first().labelRes)
        assertEquals(
            listOf(
                PttForegroundService.ACTION_TOGGLE_PTT,
                PttForegroundService.ACTION_COMMIT_DRAFT,
                PttForegroundService.ACTION_DISCARD_CAPTURE,
            ),
            stagedQuiet.map { it.action },
        )

        val stagedSpeaking = PttNotificationBuilder.actionsForState(PttState.STAGED_DRAFT_SPEAKING, promotedForStandaloneTts = false)
        assertEquals("Stop", stagedSpeaking.first().labelText)

        val speakingNormal = PttNotificationBuilder.actionsForState(PttState.SPEAKING_TTS, promotedForStandaloneTts = false)
        assertEquals("Stop", speakingNormal.first().labelText)
        assertTrue(speakingNormal.any { it.action == PttForegroundService.ACTION_DISARM })

        val speakingStandalone = PttNotificationBuilder.actionsForState(PttState.SPEAKING_TTS, promotedForStandaloneTts = true)
        assertEquals(listOf(PttForegroundService.ACTION_TOGGLE_PTT), speakingStandalone.map { it.action })
        assertFalse(speakingStandalone.any { it.action == PttForegroundService.ACTION_DISARM })
    }
}
