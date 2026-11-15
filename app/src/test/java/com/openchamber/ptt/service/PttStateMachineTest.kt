package com.openchamber.ptt.service

import com.openchamber.ptt.audio.Earcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttStateMachineTest {
    @Test
    fun `arm and disarm transitions update state and emit expected earcons and lock release`() {
        val sm = PttStateMachine()
        assertEquals(PttState.DISARMED, sm.currentState)

        // MediaButtonTap is ignored while disarmed
        val ignoredTap = sm.dispatch(PttEvent.MediaButtonTap)
        assertFalse(ignoredTap.changed)
        assertEquals(PttState.DISARMED, sm.currentState)

        // Arm from DISARMED -> ARMED_IDLE
        val armResult = sm.dispatch(PttEvent.Arm)
        assertTrue(armResult.changed)
        assertEquals(PttState.ARMED_IDLE, armResult.current)
        assertEquals(listOf(PttEffect.PlayEarcon(Earcon.ARMED)), armResult.effects)

        // Repeated Arm is a no-op
        val reArmResult = sm.dispatch(PttEvent.Arm)
        assertFalse(reArmResult.changed)
        assertTrue(reArmResult.effects.isEmpty())

        // Disarm from ARMED_IDLE -> DISARMED
        val disarmResult = sm.dispatch(PttEvent.Disarm)
        assertTrue(disarmResult.changed)
        assertEquals(PttState.DISARMED, disarmResult.current)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayEarcon(Earcon.DISARMED),
            ),
            disarmResult.effects,
        )
    }

    @Test
    fun `media button toggles recording on and off through full voice turn`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm)

        // First tap: ARMED_IDLE -> RECORDING
        val startRec = sm.dispatch(PttEvent.MediaButtonTap)
        assertEquals(PttState.RECORDING, startRec.current)
        assertEquals(
            listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayEarcon(Earcon.REC_START),
                PttEffect.StartRecording,
            ),
            startRec.effects,
        )

        // Second tap: RECORDING -> TRANSCRIBING
        val stopRec = sm.dispatch(PttEvent.MediaButtonTap)
        assertEquals(PttState.TRANSCRIBING, stopRec.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.PlayEarcon(Earcon.REC_SENT),
            ),
            stopRec.effects,
        )

        // TranscriptReady -> WAITING_AGENT
        val transcript = sm.dispatch(PttEvent.TranscriptReady("run unit tests"))
        assertEquals(PttState.WAITING_AGENT, transcript.current)

        // AgentCompleted -> SPEAKING_TTS
        val completed = sm.dispatch(PttEvent.AgentCompleted(hasTts = true))
        assertEquals(PttState.SPEAKING_TTS, completed.current)

        // TtsFinished -> ARMED_IDLE
        val finished = sm.dispatch(PttEvent.TtsFinished)
        assertEquals(PttState.ARMED_IDLE, finished.current)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
            ),
            finished.effects,
        )
    }

    @Test
    fun `blank transcript returns from TRANSCRIBING to ARMED_IDLE and releases locks`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm)
        sm.dispatch(PttEvent.MediaButtonTap)
        sm.dispatch(PttEvent.MediaButtonTap)
        assertEquals(PttState.TRANSCRIBING, sm.currentState)

        val blank = sm.dispatch(PttEvent.TranscriptReady("   "))
        assertEquals(PttState.ARMED_IDLE, blank.current)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
            ),
            blank.effects,
        )
    }

    @Test
    fun `media button tap during SPEAKING_TTS triggers barge-in to ARMED_IDLE`() {
        val sm = PttStateMachine(initialState = PttState.SPEAKING_TTS)

        val bargeIn = sm.dispatch(PttEvent.MediaButtonTap)
        assertTrue(bargeIn.changed)
        assertEquals(PttState.ARMED_IDLE, bargeIn.current)
        assertEquals(
            listOf(
                PttEffect.StopTts,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
            ),
            bargeIn.effects,
        )

        // Subsequent stale TtsFinished is ignored
        val staleTts = sm.dispatch(PttEvent.TtsFinished)
        assertFalse(staleTts.changed)
        assertEquals(PttState.ARMED_IDLE, staleTts.current)

        // Subsequent tap starts recording cleanly
        val nextRecord = sm.dispatch(PttEvent.MediaButtonTap)
        assertEquals(PttState.RECORDING, nextRecord.current)
    }

    @Test
    fun `PERMISSION_BLOCKED pauses turn and recovers via AgentCompleted, Arm, or Disarm`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm)
        sm.dispatch(PttEvent.MediaButtonTap)
        sm.dispatch(PttEvent.MediaButtonTap)
        sm.dispatch(PttEvent.TranscriptReady("edit config"))
        assertEquals(PttState.WAITING_AGENT, sm.currentState)

        // Agent asks for permission -> PERMISSION_BLOCKED
        val blocked = sm.dispatch(PttEvent.PermissionAsked("Allow bash execution?"))
        assertEquals(PttState.PERMISSION_BLOCKED, blocked.current)
        assertEquals("Allow bash execution?", sm.permissionPrompt.value)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayEarcon(Earcon.PERMISSION_NEEDED),
            ),
            blocked.effects,
        )

        // MediaButtonTap is ignored while permission-blocked
        val ignoredTap = sm.dispatch(PttEvent.MediaButtonTap)
        assertFalse(ignoredTap.changed)
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)

        // Recovery path 1: permission approved and agent completes with TTS
        val resumed = sm.dispatch(PttEvent.AgentCompleted(hasTts = true))
        assertEquals(PttState.SPEAKING_TTS, resumed.current)
        assertNull(sm.permissionPrompt.value)

        // Enter PERMISSION_BLOCKED again and recover via Arm
        sm.dispatch(PttEvent.PermissionAsked("Allow write?"))
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)
        val reArmed = sm.dispatch(PttEvent.Arm)
        assertEquals(PttState.ARMED_IDLE, reArmed.current)
        assertNull(sm.permissionPrompt.value)
        assertEquals(listOf(PttEffect.PlayEarcon(Earcon.ARMED)), reArmed.effects)

        // Enter PERMISSION_BLOCKED and recover via AgentCompleted(hasTts = false)
        sm.dispatch(PttEvent.PermissionAsked("Allow network?"))
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)
        val silentComplete = sm.dispatch(PttEvent.AgentCompleted(hasTts = false))
        assertEquals(PttState.ARMED_IDLE, silentComplete.current)
        assertNull(sm.permissionPrompt.value)

        // Enter PERMISSION_BLOCKED and recover via Disarm
        sm.dispatch(PttEvent.PermissionAsked("Allow delete?"))
        val disarmed = sm.dispatch(PttEvent.Disarm)
        assertEquals(PttState.DISARMED, disarmed.current)
        assertNull(sm.permissionPrompt.value)
    }

    @Test
    fun `error transitions from active states release locks and recover via Arm`() {
        val sm = PttStateMachine()

        // Error while disarmed does not enter ERROR
        val disarmedError = sm.dispatch(PttEvent.Error("ignored"))
        assertEquals(PttState.DISARMED, disarmedError.current)

        sm.dispatch(PttEvent.Arm)
        sm.dispatch(PttEvent.MediaButtonTap)
        assertEquals(PttState.RECORDING, sm.currentState)

        val recError = sm.dispatch(PttEvent.Error("AudioRecord failed"))
        assertEquals(PttState.ERROR, recError.current)
        assertEquals("AudioRecord failed", sm.lastError.value)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayEarcon(Earcon.ERROR),
            ),
            recError.effects,
        )

        val recovered = sm.dispatch(PttEvent.Arm)
        assertEquals(PttState.ARMED_IDLE, recovered.current)
        assertNull(sm.lastError.value)
    }

    @Test
    fun `disarm from RECORDING stops recording and releases locks`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm)
        sm.dispatch(PttEvent.MediaButtonTap)
        assertEquals(PttState.RECORDING, sm.currentState)

        val disarm = sm.dispatch(PttEvent.Disarm)
        assertEquals(PttState.DISARMED, disarm.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayEarcon(Earcon.DISARMED),
            ),
            disarm.effects,
        )
    }

    @Test
    fun `permission resolved transitions PERMISSION_BLOCKED back to WAITING_AGENT`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm)
        sm.dispatch(PttEvent.MediaButtonTap)
        sm.dispatch(PttEvent.MediaButtonTap)
        sm.dispatch(PttEvent.TranscriptReady("run ls"))
        sm.dispatch(PttEvent.PermissionAsked("bash"))
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)
        assertEquals("bash", sm.permissionPrompt.value)

        val resolved = sm.dispatch(PttEvent.PermissionResolved)
        assertEquals(PttState.WAITING_AGENT, resolved.current)
        assertEquals(listOf(PttEffect.AcquireWakeAndWifiLocks), resolved.effects)
        assertNull(sm.permissionPrompt.value)
    }

    @Test
    fun `PlayLastReply works from DISARMED and ARMED_IDLE and returns to prior state`() {
        val sm = PttStateMachine()
        val fromDisarmed = sm.dispatch(PttEvent.PlayLastReply)
        assertEquals(PttState.SPEAKING_TTS, fromDisarmed.current)
        assertEquals(PttState.DISARMED, sm.dispatch(PttEvent.TtsFinished).current)

        sm.dispatch(PttEvent.Arm)
        assertEquals(PttState.ARMED_IDLE, sm.currentState)
        assertEquals(PttState.SPEAKING_TTS, sm.dispatch(PttEvent.PlayLastReply).current)
        assertEquals(PttState.ARMED_IDLE, sm.dispatch(PttEvent.MediaButtonTap).current)
    }
}
