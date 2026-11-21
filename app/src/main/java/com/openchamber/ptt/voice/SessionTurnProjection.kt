package com.openchamber.ptt.voice

import com.openchamber.ptt.storage.SessionReplyCursor

enum class TurnTriggerKind {
    USER_PROMPT,
    ASYNC_SESSION_RESULT,
    SUBAGENT_REPORT,
}

data class AssistantResponseItem(
    val messageId: String,
    val turnId: String,
    val triggerKind: TurnTriggerKind,
    val triggerLabel: String?,
    val triggerText: String?,
    val assistantText: String,
    val createdAtMs: Long,
    val isFinalStep: Boolean,
    val isCompleted: Boolean,
)

data class SessionTurn(
    val turnId: String,
    val triggerKind: TurnTriggerKind,
    val triggerLabel: String?,
    val triggerText: String?,
    val responses: List<AssistantResponseItem>,
    val isIdle: Boolean,
    val outcome: String?,
    val latestMessageId: String? = null,
)

data class SpokenSummaryState(
    val messageId: String,
    val text: String,
)

data class DirectVoiceAnswer(
    val question: String,
    val spokenAnswer: String,
    val targetMessageId: String?,
)

data class ResponseFeedState(
    val turns: List<SessionTurn> = emptyList(),
    val unreadQueue: List<AssistantResponseItem> = emptyList(),
    val focusedMessageId: String? = null,
    val speakingMessageId: String? = null,
    val spokenSummary: SpokenSummaryState? = null,
    val directAnswer: DirectVoiceAnswer? = null,
    val autoSpeakPaused: Boolean = false,
) {
    val allResponses: List<AssistantResponseItem>
        get() = turns.flatMap { it.responses }

    val inspectableResponses: List<AssistantResponseItem>
        get() = SessionTurnProjection.buildInspectableResponses(turns, unreadQueue)

    val focusedResponse: AssistantResponseItem?
        get() {
            val inspectable = inspectableResponses
            if (inspectable.isEmpty()) return unreadQueue.firstOrNull()
            return focusedMessageId?.let { id -> inspectable.firstOrNull { it.messageId == id } }
                ?: unreadQueue.firstOrNull()?.let { queued ->
                    inspectable.firstOrNull { it.messageId == queued.messageId } ?: queued
                }
                ?: inspectable.lastOrNull()
        }

    val activeTurn: SessionTurn?
        get() {
            val focusedTurnId = focusedResponse?.turnId
            if (focusedTurnId != null) {
                turns.firstOrNull { it.turnId == focusedTurnId }?.let { return it }
            }
            return turns.lastOrNull { it.responses.isNotEmpty() } ?: turns.lastOrNull()
        }

    val focusedSpokenSummary: String?
        get() {
            val focusedId = focusedResponse?.messageId ?: return null
            return spokenSummary?.takeIf { it.messageId == focusedId }?.text
        }
}

sealed interface ParsedSessionRecord {
    val id: String
    val createdAtMs: Long

    data class UserRecord(
        override val id: String,
        override val createdAtMs: Long,
        val cleanText: String,
    ) : ParsedSessionRecord

    data class SyntheticRecord(
        override val id: String,
        override val createdAtMs: Long,
        val triggerKind: TurnTriggerKind?,
        val triggerLabel: String?,
        val cleanText: String,
    ) : ParsedSessionRecord

    data class AssistantRecord(
        override val id: String,
        override val createdAtMs: Long,
        val completedAtMs: Long?,
        val finish: String?,
        val hasTimeObject: Boolean,
        val text: String?,
    ) : ParsedSessionRecord

    data class IdleRecord(
        override val id: String,
        override val createdAtMs: Long,
        val outcome: String,
    ) : ParsedSessionRecord

    data class OtherRecord(
        override val id: String,
        override val createdAtMs: Long,
    ) : ParsedSessionRecord
}

object SessionTurnProjection {
    const val SESSION_START_BASELINE = "__session_start__"

    fun buildSessionTurns(chronologicalRecords: List<ParsedSessionRecord>): List<SessionTurn> {
        if (chronologicalRecords.isEmpty()) return emptyList()

        val hasLaterBoundary = computeHasLaterSiblingOrIdle(chronologicalRecords)
        val turns = ArrayList<SessionTurn>()
        var currentTurn: MutableTurn? = null

        for (i in chronologicalRecords.indices) {
            when (val record = chronologicalRecords[i]) {
                is ParsedSessionRecord.UserRecord -> {
                    currentTurn = handleOpener(
                        turns = turns,
                        currentTurn = currentTurn,
                        id = record.id,
                        triggerKind = TurnTriggerKind.USER_PROMPT,
                        triggerLabel = null,
                        triggerText = record.cleanText.trim().takeIf { it.isNotEmpty() },
                    )
                }

                is ParsedSessionRecord.SyntheticRecord -> {
                    if (record.triggerKind != null) {
                        currentTurn = handleOpener(
                            turns = turns,
                            currentTurn = currentTurn,
                            id = record.id,
                            triggerKind = record.triggerKind,
                            triggerLabel = record.triggerLabel?.trim()?.takeIf { it.isNotEmpty() },
                            triggerText = record.cleanText.trim().takeIf { it.isNotEmpty() },
                        )
                    } else {
                        currentTurn?.lastRecordId = record.id
                    }
                }

                is ParsedSessionRecord.AssistantRecord -> {
                    val turn = currentTurn ?: MutableTurn(
                        turnId = record.id,
                        triggerKind = TurnTriggerKind.USER_PROMPT,
                        triggerLabel = null,
                        triggerText = null,
                        lastRecordId = record.id,
                    ).also { currentTurn = it }
                    turn.lastRecordId = record.id

                    val isCompleted = isAssistantCompleted(record, hasLaterBoundary[i])
                    if (!isCompleted) {
                        turn.hasIncompleteAssistant = true
                    }

                    val cleanText = record.text?.trim()?.takeIf { it.isNotEmpty() }
                    if (cleanText != null) {
                        turn.responses.add(
                            AssistantResponseItem(
                                messageId = record.id,
                                turnId = turn.turnId,
                                triggerKind = turn.triggerKind,
                                triggerLabel = turn.triggerLabel,
                                triggerText = turn.triggerText,
                                assistantText = cleanText,
                                createdAtMs = record.createdAtMs,
                                isFinalStep = false,
                                isCompleted = isCompleted,
                            ),
                        )
                    }
                }

                is ParsedSessionRecord.IdleRecord -> {
                    if (currentTurn != null) {
                        turns.add(
                            currentTurn.buildClosed(
                                isIdle = true,
                                outcome = record.outcome.trim().takeIf { it.isNotEmpty() },
                                latestMessageId = record.id,
                            ),
                        )
                        currentTurn = null
                    }
                }

                is ParsedSessionRecord.OtherRecord -> {
                    currentTurn?.lastRecordId = record.id
                }
            }
        }

        if (currentTurn != null) {
            turns.add(
                currentTurn.buildClosed(
                    isIdle = false,
                    outcome = null,
                    latestMessageId = currentTurn.lastRecordId,
                ),
            )
        }

        return turns
    }

    fun computeLatestSettledMessageId(
        chronologicalRecords: List<ParsedSessionRecord>,
        turns: List<SessionTurn>,
    ): String? {
        if (chronologicalRecords.isEmpty()) return null
        val lastTurn = turns.lastOrNull() ?: return chronologicalRecords.last().id
        if (lastTurn.isIdle) {
            return chronologicalRecords.last().id
        }

        val previousSettledTurn = if (turns.size >= 2) turns[turns.lastIndex - 1] else null
        val trailingRecordsStartIdx = resolveTrailingRecordsStartIdx(
            chronologicalRecords = chronologicalRecords,
            previousSettledTurn = previousSettledTurn,
            lastTurn = lastTurn,
        )

        val hasLaterBoundary = computeHasLaterSiblingOrIdle(chronologicalRecords)
        val hasIncompleteStreamingAssistant = lastTurn.responses.any { !it.isCompleted } ||
            (trailingRecordsStartIdx until chronologicalRecords.size).any { idx ->
                val rec = chronologicalRecords[idx]
                rec is ParsedSessionRecord.AssistantRecord && !isAssistantCompleted(rec, hasLaterBoundary[idx])
            }

        if (!hasIncompleteStreamingAssistant) {
            return chronologicalRecords.last().id
        }

        if (previousSettledTurn?.latestMessageId != null) {
            return previousSettledTurn.latestMessageId
        }
        return if (trailingRecordsStartIdx > 0) {
            chronologicalRecords[trailingRecordsStartIdx - 1].id
        } else {
            null
        }
    }

    fun buildInspectableResponses(
        turns: List<SessionTurn>,
        unreadQueue: List<AssistantResponseItem>,
    ): List<AssistantResponseItem> {
        if (turns.isEmpty()) return emptyList()
        val unreadIds = unreadQueue.asSequence().map { it.messageId }.toHashSet()
        val latestTurnWithResponsesId = turns.lastOrNull { it.responses.isNotEmpty() }?.turnId
        val seenMessageIds = HashSet<String>()
        val result = ArrayList<AssistantResponseItem>()

        for (turn in turns) {
            if (turn.responses.isEmpty()) continue
            val includeTurn = turn.turnId == latestTurnWithResponsesId ||
                turn.responses.any { it.messageId in unreadIds }
            if (!includeTurn) continue
            for (item in turn.responses) {
                if (seenMessageIds.add(item.messageId)) {
                    result.add(item)
                }
            }
        }
        return result
    }

    fun projectResponseFeed(
        chronologicalRecords: List<ParsedSessionRecord>,
        turns: List<SessionTurn>,
        cursor: SessionReplyCursor?,
        previousState: ResponseFeedState,
        forceFocusLatest: Boolean = false,
    ): ResponseFeedState {
        val heardId = cursor?.lastHeardMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val baselineId = cursor?.armedBaselineMessageId?.trim()?.takeIf { it.isNotEmpty() }

        val unreadQueue: List<AssistantResponseItem> = if (cursor == null || (heardId == null && baselineId == null)) {
            emptyList()
        } else {
            val recordIndexById = HashMap<String, Int>(chronologicalRecords.size)
            chronologicalRecords.forEachIndexed { index, record ->
                recordIndexById[record.id] = index
            }
            val heardIdx = heardId?.let { recordIndexById[it] } ?: -1
            val baselineIdx = baselineId?.let { recordIndexById[it] } ?: -1
            val cutoffIdx: Int? = when {
                heardIdx >= 0 && baselineIdx >= 0 -> maxOf(heardIdx, baselineIdx)
                heardIdx >= 0 -> heardIdx
                baselineIdx >= 0 -> baselineIdx
                baselineId == SESSION_START_BASELINE &&
                    (heardId == null || cursor.lastHeardCreatedAtMs <= 0L) -> -1
                else -> null
            }

            val allCompleted = turns.asSequence()
                .flatMap { it.responses.asSequence() }
                .filter { it.isCompleted && it.assistantText.isNotBlank() }

            if (cutoffIdx != null) {
                allCompleted
                    .filter { item ->
                        val itemIdx = recordIndexById[item.messageId] ?: -1
                        itemIdx > cutoffIdx
                    }
                    .toList()
            } else {
                val maxTimestamp = maxOf(cursor.lastHeardCreatedAtMs, cursor.armedBaselineCreatedAtMs)
                if (maxTimestamp > 0L) {
                    allCompleted
                        .filter { item -> item.createdAtMs > maxTimestamp }
                        .toList()
                } else {
                    emptyList()
                }
            }
        }

        val inspectable = buildInspectableResponses(turns, unreadQueue)
        val inspectableIds = inspectable.asSequence().map { it.messageId }.toHashSet()

        val prevFocusedId = previousState.focusedMessageId
        val nextFocusedId = if (!forceFocusLatest && prevFocusedId != null && prevFocusedId in inspectableIds) {
            prevFocusedId
        } else if (unreadQueue.isNotEmpty()) {
            unreadQueue.first().messageId
        } else {
            inspectable.lastOrNull()?.messageId
        }

        val nextSpeakingId = previousState.speakingMessageId?.takeIf { it in inspectableIds }
        val nextSpokenSummary = previousState.spokenSummary?.takeIf { it.messageId in inspectableIds }

        return ResponseFeedState(
            turns = turns,
            unreadQueue = unreadQueue,
            focusedMessageId = nextFocusedId,
            speakingMessageId = nextSpeakingId,
            spokenSummary = nextSpokenSummary,
            directAnswer = previousState.directAnswer,
            autoSpeakPaused = previousState.autoSpeakPaused,
        )
    }

    private fun handleOpener(
        turns: MutableList<SessionTurn>,
        currentTurn: MutableTurn?,
        id: String,
        triggerKind: TurnTriggerKind,
        triggerLabel: String?,
        triggerText: String?,
    ): MutableTurn {
        if (currentTurn == null) {
            return MutableTurn(
                turnId = id,
                triggerKind = triggerKind,
                triggerLabel = triggerLabel,
                triggerText = triggerText,
                lastRecordId = id,
            )
        }
        if (currentTurn.responses.isNotEmpty()) {
            turns.add(
                currentTurn.buildClosed(
                    isIdle = false,
                    outcome = null,
                    latestMessageId = currentTurn.lastRecordId,
                ),
            )
            return MutableTurn(
                turnId = id,
                triggerKind = triggerKind,
                triggerLabel = triggerLabel,
                triggerText = triggerText,
                lastRecordId = id,
            )
        }
        val shouldReplaceOpener = triggerText != null ||
            triggerLabel != null ||
            (currentTurn.triggerText.isNullOrBlank() && currentTurn.triggerLabel.isNullOrBlank())
        if (shouldReplaceOpener) {
            currentTurn.turnId = id
            currentTurn.triggerKind = triggerKind
            currentTurn.triggerLabel = triggerLabel
            currentTurn.triggerText = triggerText ?: currentTurn.triggerText
            currentTurn.hasIncompleteAssistant = false
        }
        currentTurn.lastRecordId = id
        return currentTurn
    }

    private fun resolveTrailingRecordsStartIdx(
        chronologicalRecords: List<ParsedSessionRecord>,
        previousSettledTurn: SessionTurn?,
        lastTurn: SessionTurn,
    ): Int {
        val prevLatestId = previousSettledTurn?.latestMessageId
        if (prevLatestId != null) {
            val prevIdx = chronologicalRecords.indexOfFirst { it.id == prevLatestId }
            if (prevIdx >= 0) {
                return prevIdx + 1
            }
        }
        val turnOpenerIdx = chronologicalRecords.indexOfFirst { it.id == lastTurn.turnId }
        if (turnOpenerIdx >= 0) {
            return turnOpenerIdx
        }
        val lastIdleIdx = chronologicalRecords.indexOfLast { it is ParsedSessionRecord.IdleRecord }
        return if (lastIdleIdx >= 0) lastIdleIdx + 1 else 0
    }

    private fun computeHasLaterSiblingOrIdle(
        chronologicalRecords: List<ParsedSessionRecord>,
    ): BooleanArray {
        val result = BooleanArray(chronologicalRecords.size)
        var seenBoundaryAfter = false
        for (i in chronologicalRecords.lastIndex downTo 0) {
            result[i] = seenBoundaryAfter
            if (isStepBoundary(chronologicalRecords[i])) {
                seenBoundaryAfter = true
            }
        }
        return result
    }

    private fun isStepBoundary(record: ParsedSessionRecord): Boolean = when (record) {
        is ParsedSessionRecord.AssistantRecord,
        is ParsedSessionRecord.UserRecord,
        is ParsedSessionRecord.IdleRecord,
        -> true
        is ParsedSessionRecord.SyntheticRecord -> record.triggerKind != null
        is ParsedSessionRecord.OtherRecord -> false
    }

    private fun isAssistantCompleted(
        record: ParsedSessionRecord.AssistantRecord,
        hasLaterSiblingOrIdle: Boolean,
    ): Boolean = !record.hasTimeObject ||
        record.completedAtMs != null ||
        record.finish != null ||
        hasLaterSiblingOrIdle

    private class MutableTurn(
        var turnId: String,
        var triggerKind: TurnTriggerKind,
        var triggerLabel: String?,
        var triggerText: String?,
        var lastRecordId: String,
        var hasIncompleteAssistant: Boolean = false,
        val responses: MutableList<AssistantResponseItem> = mutableListOf(),
    ) {
        fun buildClosed(
            isIdle: Boolean,
            outcome: String?,
            latestMessageId: String?,
        ): SessionTurn {
            val finalizedResponses = if (responses.isEmpty()) {
                emptyList()
            } else {
                responses.mapIndexed { idx, item ->
                    val isLast = idx == responses.lastIndex
                    val shouldMarkFinal = isLast && (isIdle || (item.isCompleted && !hasIncompleteAssistant))
                    if (shouldMarkFinal && !item.isFinalStep) {
                        item.copy(isFinalStep = true)
                    } else {
                        item
                    }
                }
            }
            return SessionTurn(
                turnId = turnId,
                triggerKind = triggerKind,
                triggerLabel = triggerLabel,
                triggerText = triggerText,
                responses = finalizedResponses,
                isIdle = isIdle,
                outcome = outcome,
                latestMessageId = latestMessageId,
            )
        }
    }
}
