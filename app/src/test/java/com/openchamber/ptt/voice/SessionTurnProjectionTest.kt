package com.openchamber.ptt.voice

import com.openchamber.ptt.storage.SessionReplyCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTurnProjectionTest {

    @Test
    fun `buildSessionTurns groups multi-step assistant replies under opener and closes on idle`() {
        val records = listOf(
            ParsedSessionRecord.UserRecord("u1", 100L, "Run diagnostics"),
            ParsedSessionRecord.OtherRecord("o1", 150L),
            ParsedSessionRecord.AssistantRecord("a1", 200L, completedAtMs = 250L, finish = "tool-calls", hasTimeObject = true, text = "Checking logs."),
            ParsedSessionRecord.AssistantRecord("a2", 300L, completedAtMs = null, finish = null, hasTimeObject = true, text = null),
            ParsedSessionRecord.AssistantRecord("a3", 400L, completedAtMs = 450L, finish = "stop", hasTimeObject = true, text = "All checks passed."),
            ParsedSessionRecord.IdleRecord("i1", 500L, outcome = "succeeded"),
        )

        val turns = SessionTurnProjection.buildSessionTurns(records)
        assertEquals(1, turns.size)

        val turn = turns[0]
        assertEquals("u1", turn.turnId)
        assertEquals(TurnTriggerKind.USER_PROMPT, turn.triggerKind)
        assertNull(turn.triggerLabel)
        assertEquals("Run diagnostics", turn.triggerText)
        assertTrue(turn.isIdle)
        assertEquals("succeeded", turn.outcome)
        assertEquals("i1", turn.latestMessageId)
        assertEquals(listOf("a1", "a3"), turn.responses.map { it.messageId })

        assertFalse(turn.responses[0].isFinalStep)
        assertTrue(turn.responses[0].isCompleted)
        assertTrue(turn.responses[1].isFinalStep)
        assertTrue(turn.responses[1].isCompleted)
    }

    @Test
    fun `buildSessionTurns splits multi-turn records without idle markers and handles synthetic openers`() {
        val records = listOf(
            ParsedSessionRecord.UserRecord("u1", 100L, "First prompt"),
            ParsedSessionRecord.AssistantRecord("a1", 200L, completedAtMs = null, finish = null, hasTimeObject = false, text = "First answer"),
            ParsedSessionRecord.SyntheticRecord(
                id = "syn_ctx",
                createdAtMs = 250L,
                triggerKind = null,
                triggerLabel = null,
                cleanText = "Background context only",
            ),
            ParsedSessionRecord.SyntheticRecord(
                id = "syn_async",
                createdAtMs = 300L,
                triggerKind = TurnTriggerKind.ASYNC_SESSION_RESULT,
                triggerLabel = "Worker Session",
                cleanText = "Build completed on worker.",
            ),
            ParsedSessionRecord.AssistantRecord("a2", 400L, completedAtMs = 450L, finish = "stop", hasTimeObject = true, text = "Acknowledge worker."),
            ParsedSessionRecord.SyntheticRecord(
                id = "syn_sub",
                createdAtMs = 500L,
                triggerKind = TurnTriggerKind.SUBAGENT_REPORT,
                triggerLabel = "explore",
                cleanText = "Found 2 call sites.",
            ),
            ParsedSessionRecord.AssistantRecord("a3", 600L, completedAtMs = null, finish = null, hasTimeObject = true, text = "Streaming reply..."),
        )

        val turns = SessionTurnProjection.buildSessionTurns(records)
        assertEquals(3, turns.size)

        assertEquals("u1", turns[0].turnId)
        assertFalse(turns[0].isIdle)
        assertEquals("syn_ctx", turns[0].latestMessageId)
        assertTrue(turns[0].responses.single().isFinalStep)
        assertTrue(turns[0].responses.single().isCompleted)

        assertEquals("syn_async", turns[1].turnId)
        assertEquals(TurnTriggerKind.ASYNC_SESSION_RESULT, turns[1].triggerKind)
        assertEquals("Worker Session", turns[1].triggerLabel)
        assertEquals("Build completed on worker.", turns[1].triggerText)
        assertEquals("Acknowledge worker.", turns[1].responses.single().assistantText)
        assertTrue(turns[1].responses.single().isFinalStep)

        assertEquals("syn_sub", turns[2].turnId)
        assertEquals(TurnTriggerKind.SUBAGENT_REPORT, turns[2].triggerKind)
        assertEquals("explore", turns[2].triggerLabel)
        assertEquals("Found 2 call sites.", turns[2].triggerText)
        assertEquals("Streaming reply...", turns[2].responses.single().assistantText)
        assertFalse(turns[2].responses.single().isCompleted)
        assertFalse(turns[2].responses.single().isFinalStep)
    }

    @Test
    fun `buildSessionTurns replaces empty-response opener when steered and opens implicit turn for orphan assistant`() {
        val records = listOf(
            ParsedSessionRecord.AssistantRecord("a0", 50L, completedAtMs = null, finish = null, hasTimeObject = false, text = "Orphan reply"),
            ParsedSessionRecord.UserRecord("u1", 100L, "Initial prompt"),
            ParsedSessionRecord.UserRecord("u2", 120L, "Steered prompt"),
            ParsedSessionRecord.AssistantRecord("a1", 200L, completedAtMs = 250L, finish = "stop", hasTimeObject = true, text = "Steered reply"),
            ParsedSessionRecord.IdleRecord("i1", 300L, outcome = "succeeded"),
        )

        val turns = SessionTurnProjection.buildSessionTurns(records)
        assertEquals(2, turns.size)

        assertEquals("a0", turns[0].turnId)
        assertEquals(TurnTriggerKind.USER_PROMPT, turns[0].triggerKind)
        assertNull(turns[0].triggerText)

        assertEquals("u2", turns[1].turnId)
        assertEquals("Steered prompt", turns[1].triggerText)
        assertEquals("Steered reply", turns[1].responses.single().assistantText)
    }

    @Test
    fun `computeLatestSettledMessageId anchors to prior settled turn while trailing turn streams`() {
        assertNull(SessionTurnProjection.computeLatestSettledMessageId(emptyList(), emptyList()))

        val settledRecords = listOf(
            ParsedSessionRecord.UserRecord("u1", 100L, "One"),
            ParsedSessionRecord.AssistantRecord("a1", 200L, 250L, "stop", true, "Done"),
            ParsedSessionRecord.IdleRecord("i1", 300L, "succeeded"),
        )
        val settledTurns = SessionTurnProjection.buildSessionTurns(settledRecords)
        assertEquals("i1", SessionTurnProjection.computeLatestSettledMessageId(settledRecords, settledTurns))

        // Streaming non-blank reply in trailing turn rolls back to previous settled turn's i1
        val streamingWithText = settledRecords + listOf(
            ParsedSessionRecord.UserRecord("u2", 400L, "Two"),
            ParsedSessionRecord.AssistantRecord("a2", 500L, completedAtMs = null, finish = null, hasTimeObject = true, text = "Partial..."),
        )
        val streamingTurns = SessionTurnProjection.buildSessionTurns(streamingWithText)
        assertEquals("i1", SessionTurnProjection.computeLatestSettledMessageId(streamingWithText, streamingTurns))

        // Streaming tool-only step with null text in trailing turn still rolls back to i1
        val streamingBlank = settledRecords + listOf(
            ParsedSessionRecord.UserRecord("u2", 400L, "Two"),
            ParsedSessionRecord.AssistantRecord("a2_step1", 450L, completedAtMs = 470L, finish = "tool-calls", hasTimeObject = true, text = "Step 1"),
            ParsedSessionRecord.AssistantRecord("a2_step2", 500L, completedAtMs = null, finish = null, hasTimeObject = true, text = null),
        )
        val streamingBlankTurns = SessionTurnProjection.buildSessionTurns(streamingBlank)
        assertFalse(streamingBlankTurns.last().responses.single().isFinalStep)
        assertEquals("i1", SessionTurnProjection.computeLatestSettledMessageId(streamingBlank, streamingBlankTurns))

        // Single streaming turn with no previous settled turn returns null
        val singleStreaming = listOf(
            ParsedSessionRecord.UserRecord("u1", 100L, "One"),
            ParsedSessionRecord.AssistantRecord("a1", 200L, completedAtMs = null, finish = null, hasTimeObject = true, text = "Working"),
        )
        assertNull(
            SessionTurnProjection.computeLatestSettledMessageId(
                singleStreaming,
                SessionTurnProjection.buildSessionTurns(singleStreaming),
            ),
        )
    }

    @Test
    fun `projectResponseFeed respects cursor positions without alphabetical bias and ignores uninitialized cursors`() {
        // Deliberately choose non-alphabetical IDs where older ID > newer ID lexicographically
        val records = listOf(
            ParsedSessionRecord.UserRecord("z_u1", 100L, "Turn 1"),
            ParsedSessionRecord.AssistantRecord("z_a1", 200L, 250L, "stop", true, "Reply 1"),
            ParsedSessionRecord.IdleRecord("z_i1", 300L, "succeeded"),
            ParsedSessionRecord.UserRecord("m_u2", 400L, "Turn 2"),
            ParsedSessionRecord.AssistantRecord("m_a2", 500L, 550L, "stop", true, "Reply 2"),
            ParsedSessionRecord.IdleRecord("m_i2", 600L, "succeeded"),
            ParsedSessionRecord.UserRecord("a_u3", 700L, "Turn 3"),
            ParsedSessionRecord.AssistantRecord("a_a3", 800L, 850L, "stop", true, "Reply 3"),
            ParsedSessionRecord.AssistantRecord("a_a4_streaming", 900L, null, null, true, "Streaming..."),
        )
        val turns = SessionTurnProjection.buildSessionTurns(records)

        // Uninitialized cursor -> empty unreadQueue
        val uninitialized = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = null,
            previousState = ResponseFeedState(),
        )
        assertTrue(uninitialized.unreadQueue.isEmpty())
        assertEquals("a_a4_streaming", uninitialized.focusedMessageId)
        assertEquals(listOf("a_a3", "a_a4_streaming"), uninitialized.inspectableResponses.map { it.messageId })

        val blankCursorState = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(),
            previousState = ResponseFeedState(),
        )
        assertTrue(blankCursorState.unreadQueue.isEmpty())

        // Both heard and baseline in window -> maxOf(heardIdx, baselineIdx) cuts at m_a2 (index 4)
        val afterBaseline = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "z_a1",
                lastHeardCreatedAtMs = 200L,
                armedBaselineMessageId = "m_a2",
                armedBaselineCreatedAtMs = 500L,
            ),
            previousState = ResponseFeedState(),
        )
        // Streaming a_a4_streaming must NOT be in unreadQueue; only completed a_a3
        assertEquals(listOf("a_a3"), afterBaseline.unreadQueue.map { it.messageId })
        assertEquals("a_a3", afterBaseline.focusedMessageId)

        // Only heard in window -> strictly after z_i1 includes m_a2 and a_a3 (spans both turns in inspectableResponses)
        val afterHeardOnly = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "z_i1",
                lastHeardCreatedAtMs = 300L,
                armedBaselineMessageId = "evicted_old_id",
                armedBaselineCreatedAtMs = 50L,
            ),
            previousState = ResponseFeedState(),
        )
        assertEquals(listOf("m_a2", "a_a3"), afterHeardOnly.unreadQueue.map { it.messageId })
        assertEquals(listOf("m_a2", "a_a3", "a_a4_streaming"), afterHeardOnly.inspectableResponses.map { it.messageId })
        assertEquals("m_a2", afterHeardOnly.focusedMessageId)

        // Only baseline in window -> strictly after m_i2 includes a_a3
        val afterBaselineOnly = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "evicted_heard",
                lastHeardCreatedAtMs = 10L,
                armedBaselineMessageId = "m_i2",
                armedBaselineCreatedAtMs = 600L,
            ),
            previousState = ResponseFeedState(),
        )
        assertEquals(listOf("a_a3"), afterBaselineOnly.unreadQueue.map { it.messageId })
    }

    @Test
    fun `projectResponseFeed falls back to cursor timestamps outside window and preserves focus and speech state`() {
        val records = listOf(
            ParsedSessionRecord.UserRecord("u2", 400L, "Turn 2"),
            ParsedSessionRecord.AssistantRecord("a2", 500L, 550L, "stop", true, "Reply 2"),
            ParsedSessionRecord.UserRecord("u3", 700L, "Turn 3"),
            ParsedSessionRecord.AssistantRecord("a3", 800L, 850L, "stop", true, "Reply 3"),
        )
        val turns = SessionTurnProjection.buildSessionTurns(records)

        val previous = ResponseFeedState(
            focusedMessageId = "a2",
            speakingMessageId = "a2",
            spokenSummary = SpokenSummaryState("a2", "Short summary of 2"),
            directAnswer = DirectVoiceAnswer("What happened?", "Reply 2 ran tests.", "a2"),
            autoSpeakPaused = true,
        )

        val projected = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "missing_id",
                lastHeardCreatedAtMs = 450L,
                armedBaselineMessageId = "missing_baseline",
                armedBaselineCreatedAtMs = 450L,
            ),
            previousState = previous,
            forceFocusLatest = false,
        )

        assertEquals(listOf("a2", "a3"), projected.unreadQueue.map { it.messageId })
        assertEquals("a2", projected.focusedMessageId)
        assertEquals("a2", projected.focusedResponse?.messageId)
        assertEquals("u2", projected.activeTurn?.turnId)
        assertEquals("Short summary of 2", projected.focusedSpokenSummary)
        assertEquals("a2", projected.speakingMessageId)
        assertEquals(previous.directAnswer, projected.directAnswer)
        assertTrue(projected.autoSpeakPaused)

        // forceFocusLatest moves focus to first unread item when unreadQueue is non-empty
        val refocused = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "a2",
                lastHeardCreatedAtMs = 500L,
            ),
            previousState = projected,
            forceFocusLatest = true,
        )
        assertEquals(listOf("a3"), refocused.unreadQueue.map { it.messageId })
        assertEquals("a3", refocused.focusedMessageId)
        // a2 is no longer in inspectableResponses (since a2 is heard and latest turn is u3), so speaking/summary clear
        assertNull(refocused.speakingMessageId)
        assertNull(refocused.spokenSummary)
        assertNull(refocused.focusedSpokenSummary)
        assertEquals(previous.directAnswer, refocused.directAnswer)
    }

    @Test
    fun `projectResponseFeed returns empty unreadQueue when non-null cursor IDs are absent and timestamps are zero`() {
        val records = listOf(
            ParsedSessionRecord.UserRecord("u1", 100L, "Prompt"),
            ParsedSessionRecord.AssistantRecord("a1", 200L, 250L, "stop", true, "Reply"),
        )
        val turns = SessionTurnProjection.buildSessionTurns(records)

        val projected = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(lastHeardMessageId = "missing_only_id"),
            previousState = ResponseFeedState(),
        )

        assertTrue(projected.unreadQueue.isEmpty())
        assertEquals("a1", projected.focusedMessageId)
    }

    @Test
    fun `projectResponseFeed treats SESSION_START_BASELINE as cutoff before index zero when no heard record supersedes it`() {
        val records = listOf(
            ParsedSessionRecord.UserRecord("u1", 100L, "First prompt"),
            ParsedSessionRecord.AssistantRecord("a1", 200L, 250L, "stop", true, "First reply"),
            ParsedSessionRecord.UserRecord("u2", 300L, "Second prompt"),
            ParsedSessionRecord.AssistantRecord("a2", 400L, null, null, true, "Streaming second"),
        )
        val turns = SessionTurnProjection.buildSessionTurns(records)

        val initial = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE),
            previousState = ResponseFeedState(),
        )
        assertEquals(listOf("a1"), initial.unreadQueue.map { it.messageId })
        assertEquals("a1", initial.focusedMessageId)

        // Once a1 is heard in window, heardIdx takes precedence over SESSION_START_BASELINE
        val afterHeard = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "a1",
                lastHeardCreatedAtMs = 200L,
                armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE,
            ),
            previousState = initial,
        )
        assertTrue(afterHeard.unreadQueue.isEmpty())

        // If heard ID was evicted from window with createdAtMs > 0, timestamp fallback still prevents replaying <= 200L
        val afterEvictedHeard = SessionTurnProjection.projectResponseFeed(
            chronologicalRecords = records,
            turns = turns,
            cursor = SessionReplyCursor(
                lastHeardMessageId = "evicted_after_a1",
                lastHeardCreatedAtMs = 250L,
                armedBaselineMessageId = SessionTurnProjection.SESSION_START_BASELINE,
            ),
            previousState = initial,
        )
        assertTrue(afterEvictedHeard.unreadQueue.isEmpty())
    }
}
