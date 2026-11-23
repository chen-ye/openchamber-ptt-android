package com.openchamber.ptt.service

import com.openchamber.ptt.audio.CueEvent
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

        // SingleTap is ignored while disarmed
        val ignoredTap = sm.dispatch(PttEvent.SingleTap)
        assertFalse(ignoredTap.changed)
        assertEquals(PttState.DISARMED, sm.currentState)

        // Arm from DISARMED -> ARMED_IDLE
        val armResult = sm.dispatch(PttEvent.Arm())
        assertTrue(armResult.changed)
        assertEquals(PttState.ARMED_IDLE, armResult.current)
        assertEquals(listOf(PttEffect.PlayCue(CueEvent.ARMED)), armResult.effects)

        // Repeated Arm is a no-op
        val reArmResult = sm.dispatch(PttEvent.Arm())
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
                PttEffect.PlayCue(CueEvent.DISARMED),
            ),
            disarmResult.effects,
        )
    }

    @Test
    fun `arm with persisted draft restores STAGED_DRAFT with ARMED_WITH_DRAFT and disarm preserves it`() {
        val sm = PttStateMachine()
        val armed = sm.dispatch(PttEvent.Arm(hasPersistedDraft = true))
        assertEquals(PttState.STAGED_DRAFT, armed.current)
        assertEquals(listOf(PttEffect.PlayCue(CueEvent.ARMED_WITH_DRAFT)), armed.effects)

        val disarmed = sm.dispatch(PttEvent.Disarm)
        assertEquals(PttState.DISARMED, disarmed.current)
        assertFalse(disarmed.effects.contains(PttEffect.ClearDraft))
    }

    @Test
    fun `quick turn goes through RECORDING, TRANSCRIBING_SEND, SEND_GRACE, WAITING_AGENT, and SPEAKING_TTS`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())

        // 1st tap: ARMED_IDLE -> RECORDING
        val startRec = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, startRec.current)
        assertEquals(
            listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.REC_START),
                PttEffect.StartRecording,
            ),
            startRec.effects,
        )

        // 2nd single tap: RECORDING -> TRANSCRIBING_SEND (releases SCO immediately and emits QUICK_STOP_ACK)
        val stopRec = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.TRANSCRIBING_SEND, stopRec.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.QUICK_STOP_ACK),
            ),
            stopRec.effects,
        )

        // TranscriptReady -> SEND_GRACE (2.5s window)
        val transcript = sm.dispatch(PttEvent.TranscriptReady("run unit tests"))
        assertEquals(PttState.SEND_GRACE, transcript.current)
        assertEquals(
            listOf(
                PttEffect.PlayCue(CueEvent.SEND_GRACE),
                PttEffect.StartSendGraceTimer,
            ),
            transcript.effects,
        )

        // GraceTimerExpired -> WAITING_AGENT
        val committed = sm.dispatch(PttEvent.GraceTimerExpired)
        assertEquals(PttState.WAITING_AGENT, committed.current)
        assertEquals(
            listOf(
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = false),
            ),
            committed.effects,
        )

        // AgentCompleted -> SPEAKING_TTS with PinBluetoothAudio, WakeLock, and TURN_COMPLETE cue
        val completed = sm.dispatch(PttEvent.AgentCompleted(hasTts = true))
        assertEquals(PttState.SPEAKING_TTS, completed.current)
        assertEquals(
            listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.TURN_COMPLETE),
            ),
            completed.effects,
        )

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
    fun `re-tap or undo during SEND_GRACE pulls turn into quiet STAGED_DRAFT`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.TranscriptReady("refactor cache"))
        assertEquals(PttState.SEND_GRACE, sm.currentState)

        val undo = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.STAGED_DRAFT, undo.current)
        assertEquals(
            listOf(
                PttEffect.CancelSendGraceTimer,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.UNDO_TO_DRAFT),
            ),
            undo.effects,
        )
    }

    @Test
    fun `double tap while recording stages, organizes, speaks preview, and supports append and commit`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, sm.currentState)

        // DoubleTap stops, releases SCO immediately, and stages
        val stage = sm.dispatch(PttEvent.DoubleTap)
        assertEquals(PttState.TRANSCRIBING_STAGE, stage.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.STAGE_ORGANIZING),
            ),
            stage.effects,
        )

        // TranscriptReady triggers OrganizeAndSpeakDraft
        val ready = sm.dispatch(PttEvent.TranscriptReady("add sqlite cache and keep tests"))
        assertEquals(PttState.TRANSCRIBING_STAGE, ready.current)
        assertEquals(listOf(PttEffect.OrganizeAndSpeakDraft("add sqlite cache and keep tests")), ready.effects)

        // DraftPreviewReady moves to STAGED_DRAFT_SPEAKING
        val preview = sm.dispatch(PttEvent.DraftPreviewReady)
        assertEquals(PttState.STAGED_DRAFT_SPEAKING, preview.current)
        assertEquals(
            listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
            ),
            preview.effects,
        )

        // SingleTap interrupts preview into quiet STAGED_DRAFT
        val interruptPreview = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.STAGED_DRAFT, interruptPreview.current)
        assertEquals(
            listOf(
                PttEffect.StopTts,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
            ),
            interruptPreview.effects,
        )

        // SingleTap in quiet STAGED_DRAFT starts appending a new burst
        val append = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, append.current)

        // Discarding the appended burst preserves the prior draft by returning to STAGED_DRAFT
        val discardBurst = sm.dispatch(PttEvent.TripleTap)
        assertEquals(PttState.STAGED_DRAFT, discardBurst.current)
        assertFalse(discardBurst.effects.contains(PttEffect.ClearDraft))
        assertTrue(discardBurst.effects.contains(PttEffect.DiscardRecording))

        // DoubleTap in STAGED_DRAFT commits & sends
        val commit = sm.dispatch(PttEvent.DoubleTap)
        assertEquals(PttState.WAITING_AGENT, commit.current)
        assertTrue(commit.effects.contains(PttEffect.DispatchPrompt(fromStagedDraft = true)))
        assertTrue(commit.effects.contains(PttEffect.PlayCue(CueEvent.PROMPT_SENT)))
    }

    @Test
    fun `double tap during STAGED_DRAFT_SPEAKING commits immediately`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.DoubleTap)
        sm.dispatch(PttEvent.TranscriptReady("organized prompt"))
        sm.dispatch(PttEvent.DraftPreviewReady)
        assertEquals(PttState.STAGED_DRAFT_SPEAKING, sm.currentState)

        val commitEarly = sm.dispatch(PttEvent.DoubleTap)
        assertEquals(PttState.WAITING_AGENT, commitEarly.current)
        assertEquals(
            listOf(
                PttEffect.StopTts,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = true),
            ),
            commitEarly.effects,
        )
    }

    @Test
    fun `blank transcript returns from TRANSCRIBING to ARMED_IDLE or STAGED_DRAFT with EMPTY_SPEECH cue`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.TRANSCRIBING_SEND, sm.currentState)

        val blank = sm.dispatch(PttEvent.TranscriptReady("   "))
        assertEquals(PttState.ARMED_IDLE, blank.current)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.EMPTY_SPEECH),
            ),
            blank.effects,
        )

        // Blank transcript while appending to existing draft preserves STAGED_DRAFT
        sm.dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = true))
        assertEquals(PttState.STAGED_DRAFT, sm.currentState)
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.DoubleTap)
        val blankAppend = sm.dispatch(PttEvent.TranscriptReady(""))
        assertEquals(PttState.STAGED_DRAFT, blankAppend.current)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.EMPTY_SPEECH),
            ),
            blankAppend.effects,
        )
    }

    @Test
    fun `single tap during SPEAKING_TTS triggers barge-in to ARMED_IDLE`() {
        val sm = PttStateMachine(initialState = PttState.SPEAKING_TTS)

        val bargeIn = sm.dispatch(PttEvent.SingleTap)
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

        val staleTts = sm.dispatch(PttEvent.TtsFinished)
        assertFalse(staleTts.changed)
        assertEquals(PttState.ARMED_IDLE, staleTts.current)

        val nextRecord = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, nextRecord.current)
    }

    @Test
    fun `PERMISSION_BLOCKED pauses turn and recovers via AgentCompleted, Arm, or Disarm`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.TranscriptReady("edit config"))
        sm.dispatch(PttEvent.GraceTimerExpired)
        assertEquals(PttState.WAITING_AGENT, sm.currentState)

        val blocked = sm.dispatch(PttEvent.PermissionAsked("Allow bash execution?"))
        assertEquals(PttState.PERMISSION_BLOCKED, blocked.current)
        assertEquals("Allow bash execution?", sm.permissionPrompt.value)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.PERMISSION_NEEDED),
            ),
            blocked.effects,
        )

        val ignoredTap = sm.dispatch(PttEvent.SingleTap)
        assertFalse(ignoredTap.changed)
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)

        val resumed = sm.dispatch(PttEvent.AgentCompleted(hasTts = true))
        assertEquals(PttState.SPEAKING_TTS, resumed.current)
        assertNull(sm.permissionPrompt.value)

        sm.dispatch(PttEvent.PermissionAsked("Allow write?"))
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)
        val reArmed = sm.dispatch(PttEvent.Arm())
        assertEquals(PttState.ARMED_IDLE, reArmed.current)
        assertNull(sm.permissionPrompt.value)
        assertEquals(listOf(PttEffect.PlayCue(CueEvent.ARMED)), reArmed.effects)

        sm.dispatch(PttEvent.PermissionAsked("Allow network?"))
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)
        val silentComplete = sm.dispatch(PttEvent.AgentCompleted(hasTts = false))
        assertEquals(PttState.ARMED_IDLE, silentComplete.current)
        assertTrue(silentComplete.effects.contains(PttEffect.PlayCue(CueEvent.TURN_COMPLETE_NO_TTS)))
        assertNull(sm.permissionPrompt.value)

        sm.dispatch(PttEvent.PermissionAsked("Allow delete?"))
        val disarmed = sm.dispatch(PttEvent.Disarm)
        assertEquals(PttState.DISARMED, disarmed.current)
        assertNull(sm.permissionPrompt.value)
    }

    @Test
    fun `error transitions emit AUTH_EXPIRED for 401 and ERROR otherwise`() {
        val sm = PttStateMachine()

        val disarmedError = sm.dispatch(PttEvent.Error("ignored"))
        assertEquals(PttState.DISARMED, disarmedError.current)

        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, sm.currentState)

        val recError = sm.dispatch(PttEvent.Error("AudioRecord failed"))
        assertEquals(PttState.ERROR, recError.current)
        assertEquals("AudioRecord failed", sm.lastError.value)
        assertTrue(recError.effects.contains(PttEffect.PlayCue(CueEvent.ERROR)))

        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.SingleTap)
        val authErr = sm.dispatch(PttEvent.Error("Unauthorized", isAuthExpired = true))
        assertEquals(PttState.ERROR, authErr.current)
        assertTrue(authErr.effects.contains(PttEffect.PlayCue(CueEvent.AUTH_EXPIRED)))
    }

    @Test
    fun `agent interrupted emits TURN_INTERRUPTED cue and returns to ARMED_IDLE`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.TranscriptReady("run build"))
        sm.dispatch(PttEvent.GraceTimerExpired)
        assertEquals(PttState.WAITING_AGENT, sm.currentState)

        val interrupted = sm.dispatch(PttEvent.AgentInterrupted)
        assertEquals(PttState.ARMED_IDLE, interrupted.current)
        assertEquals(
            listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.TURN_INTERRUPTED),
            ),
            interrupted.effects,
        )
    }

    @Test
    fun `PlayLastReply and idle DoubleTap read latest reply and return to prior state`() {
        val sm = PttStateMachine()
        val fromDisarmed = sm.dispatch(PttEvent.PlayLastReply)
        assertEquals(PttState.SPEAKING_TTS, fromDisarmed.current)
        assertEquals(PttState.DISARMED, sm.dispatch(PttEvent.TtsFinished).current)

        sm.dispatch(PttEvent.Arm())
        val viaDoubleTap = sm.dispatch(PttEvent.DoubleTap)
        assertEquals(PttState.SPEAKING_TTS, viaDoubleTap.current)
        assertEquals(
            listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.ReadLastReply,
            ),
            viaDoubleTap.effects,
        )
        assertEquals(PttState.ARMED_IDLE, sm.dispatch(PttEvent.SingleTap).current)
    }

    @Test
    fun `single tap stop during append burst routes to TRANSCRIBING_STAGE with STAGE_APPENDING cue`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm(hasPersistedDraft = true))
        assertEquals(PttState.STAGED_DRAFT, sm.currentState)

        sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, sm.currentState)

        val stopAppend = sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.TRANSCRIBING_STAGE, stopAppend.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.STAGE_APPENDING),
            ),
            stopAppend.effects,
        )

        val staged = sm.dispatch(PttEvent.TranscriptReady("and add regression tests"))
        assertEquals(
            listOf(PttEffect.OrganizeAndSpeakDraft("and add regression tests")),
            staged.effects,
        )
        assertEquals(PttState.STAGED_DRAFT_SPEAKING, sm.dispatch(PttEvent.DraftPreviewReady).current)

        val clearedWhileSpeaking = sm.dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = false))
        assertEquals(PttState.ARMED_IDLE, clearedWhileSpeaking.current)
        assertTrue(clearedWhileSpeaking.effects.contains(PttEffect.StopTts))
    }

    @Test
    fun `silent AgentCompleted and AgentInterrupted preserve STAGED_DRAFT when a draft exists`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.SingleTap)
        sm.dispatch(PttEvent.TranscriptReady("quick question"))
        assertEquals(PttState.SEND_GRACE, sm.currentState)
        sm.dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = true))
        sm.dispatch(PttEvent.GraceTimerExpired)
        assertEquals(PttState.WAITING_AGENT, sm.currentState)

        sm.dispatch(PttEvent.PermissionAsked("Allow read?"))
        assertEquals(PttState.PERMISSION_BLOCKED, sm.currentState)

        val silentDone = sm.dispatch(PttEvent.AgentCompleted(hasTts = false))
        assertEquals(PttState.STAGED_DRAFT, silentDone.current)
        assertTrue(silentDone.effects.contains(PttEffect.PlayCue(CueEvent.TURN_COMPLETE_NO_TTS)))

        sm.dispatch(PttEvent.SingleTap) // Append -> RECORDING
        sm.dispatch(PttEvent.TripleTap) // Discard append -> STAGED_DRAFT
        assertEquals(PttState.STAGED_DRAFT, sm.currentState)

        val playInDraft = sm.dispatch(PttEvent.PlayLastReply)
        assertEquals(PttState.SPEAKING_TTS, playInDraft.current)
        assertEquals(
            listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.ReadLastReply,
            ),
            playInDraft.effects,
        )
        assertEquals(PttState.STAGED_DRAFT, sm.dispatch(PttEvent.TtsFinished).current)

        // AgentInterrupted with active draft returns to STAGED_DRAFT
        sm.dispatch(PttEvent.CommitDraft)
        sm.dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = true))
        assertEquals(PttState.STAGED_DRAFT, sm.dispatch(PttEvent.AgentInterrupted).current)
    }

    @Test
    fun `PlayQueuedReply speaks only from ARMED_IDLE or STAGED_DRAFT and is ignored in all other states`() {
        val expectedEffects = listOf(
            PttEffect.PinBluetoothAudio,
            PttEffect.AcquireWakeAndWifiLocks,
            PttEffect.PlayCue(CueEvent.TURN_COMPLETE),
            PttEffect.SpeakPendingReplies,
        )

        // From ARMED_IDLE -> SPEAKING_TTS, returns to ARMED_IDLE on TtsFinished
        val armedSm = PttStateMachine()
        armedSm.dispatch(PttEvent.Arm())
        val fromArmed = armedSm.dispatch(PttEvent.PlayQueuedReply)
        assertTrue(fromArmed.changed)
        assertEquals(PttState.SPEAKING_TTS, fromArmed.current)
        assertEquals(expectedEffects, fromArmed.effects)
        assertEquals(PttState.ARMED_IDLE, armedSm.dispatch(PttEvent.TtsFinished).current)

        // From STAGED_DRAFT -> SPEAKING_TTS, returns to STAGED_DRAFT on TtsFinished
        val draftSm = PttStateMachine()
        draftSm.dispatch(PttEvent.Arm(hasPersistedDraft = true))
        assertEquals(PttState.STAGED_DRAFT, draftSm.currentState)
        val fromDraft = draftSm.dispatch(PttEvent.PlayQueuedReply)
        assertTrue(fromDraft.changed)
        assertEquals(PttState.SPEAKING_TTS, fromDraft.current)
        assertEquals(expectedEffects, fromDraft.effects)
        assertEquals(PttState.STAGED_DRAFT, draftSm.dispatch(PttEvent.TtsFinished).current)

        // Strictly ignored (no transition, empty effects) in every other state
        val ignoredStates = listOf(
            PttState.DISARMED,
            PttState.RECORDING,
            PttState.TRANSCRIBING_SEND,
            PttState.TRANSCRIBING_STAGE,
            PttState.SEND_GRACE,
            PttState.STAGED_DRAFT_SPEAKING,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
        )
        assertEquals(PttState.entries.size - 2, ignoredStates.size)
        for (state in ignoredStates) {
            val sm = PttStateMachine(initialState = state)
            val result = sm.dispatch(PttEvent.PlayQueuedReply)
            assertFalse("Expected no state change in $state", result.changed)
            assertEquals(state, result.current)
            assertTrue("Expected empty effects in $state", result.effects.isEmpty())
        }
    }

    @Test
    fun `standalone PlayLastReply error returns to DISARMED instead of stranding in ERROR`() {
        val sm = PttStateMachine()
        assertEquals(PttState.SPEAKING_TTS, sm.dispatch(PttEvent.PlayLastReply).current)

        val err = sm.dispatch(PttEvent.Error("TTS failed"))
        assertTrue(err.changed)
        assertEquals(PttState.DISARMED, err.current)
        assertEquals("TTS failed", sm.lastError.value)
        assertEquals(
            listOf(
                PttEffect.StopTts,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.ERROR),
            ),
            err.effects,
        )

        // Armed SPEAKING_TTS error still transitions to ERROR for recovery
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.PlayLastReply)
        val armedErr = sm.dispatch(PttEvent.Error("Unauthorized", isAuthExpired = true))
        assertEquals(PttState.ERROR, armedErr.current)
        assertTrue(armedErr.effects.contains(PttEffect.PlayCue(CueEvent.AUTH_EXPIRED)))
    }

    @Test
    fun `StageCapture from RECORDING emits STAGE_ORGANIZING or STAGE_APPENDING based on draft presence`() {
        val sm = PttStateMachine()
        sm.dispatch(PttEvent.Arm())
        sm.dispatch(PttEvent.SingleTap)
        assertEquals(PttState.RECORDING, sm.currentState)

        val stageNew = sm.dispatch(PttEvent.StageCapture)
        assertEquals(PttState.TRANSCRIBING_STAGE, stageNew.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.STAGE_ORGANIZING),
            ),
            stageNew.effects,
        )

        val smDraft = PttStateMachine()
        smDraft.dispatch(PttEvent.Arm(hasPersistedDraft = true))
        smDraft.dispatch(PttEvent.SingleTap)
        val stageAppend = smDraft.dispatch(PttEvent.StageCapture)
        assertEquals(PttState.TRANSCRIBING_STAGE, stageAppend.current)
        assertEquals(
            listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseBluetoothAudio,
                PttEffect.PlayCue(CueEvent.STAGE_APPENDING),
            ),
            stageAppend.effects,
        )
    }
}
