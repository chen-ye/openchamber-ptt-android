package com.openchamber.ptt.service

import android.os.SystemClock
import com.openchamber.ptt.audio.CueEvent
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.TtsPlaybackResult
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.voice.AssistantResponseItem
import com.openchamber.ptt.voice.DirectVoiceAnswer
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.ParsedSessionRecord
import com.openchamber.ptt.voice.PromptOrganizer
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.TurnEvent
import com.openchamber.ptt.voice.TurnTriggerKind
import com.openchamber.ptt.voice.VoiceControlDecision
import com.openchamber.ptt.voice.VoiceControlPlane
import com.openchamber.ptt.voice.VoiceReplyContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

internal data class TtsSpeakRequest(
    val serverUrl: String,
    val text: String,
    val role: VoiceRole = VoiceRole.AGENT,
    val sessionLabel: String? = null,
    val inputModeOverride: TtsInputMode? = null,
    val kind: String = AgentTurnRunner.TTS_KIND_REPLY,
    val notifySpokenSummary: Boolean = true,
    val isManualRead: Boolean = false,
    val messageId: String? = null,
    val onAudibleStart: ((messageId: String?, estimatedDurationMs: Long?) -> Unit)? = null,
)

internal class AgentTurnRunner(
    private val scope: CoroutineScope,
    private val tokenStore: TokenStore,
    private val sessionClient: SessionClient,
    private val eventStreamClient: EventStreamClient,
    private val promptOrganizer: PromptOrganizer,
    private val voiceControlPlane: VoiceControlPlane,
    private val voiceUxLogger: VoiceUxLogger,
    private val speakTts: suspend (TtsSpeakRequest) -> TtsPlaybackResult,
    private val stopTts: () -> Unit = {},
    private val withStandalonePlaybackFocus: suspend (suspend () -> Unit) -> Unit = { it() },
    private val currentState: () -> PttState,
    private val activeSessionKey: () -> String?,
    private val currentStagedDraft: () -> StagedDraft?,
    private val updateStagedDraft: (StagedDraft?) -> Unit,
    private val currentPendingQuickTranscript: () -> String?,
    private val clearPendingQuickTranscript: () -> Unit,
    private val setPendingQuickTranscript: (String) -> Unit,
    private val currentLastReply: () -> String? = { null },
    private val updateLastReply: (String?) -> Unit = {},
    private val updateLastTranscript: (String?) -> Unit,
    private val updateLastSpokenSummary: (String?) -> Unit,
    private val updateStatusText: (String) -> Unit,
    private val setOrganizingDraft: (Boolean) -> Unit,
    private val syncActiveBubble: (statusSummary: String?) -> Unit,
    private val onTranscriptReady: (String?) -> Unit,
    private val onDraftPreviewReady: () -> PttTransition,
    private val onAgentCompleted: (hasTts: Boolean) -> Unit,
    private val onAgentInterrupted: () -> Unit,
    private val onPermissionAsked: (tool: String?) -> Unit,
    private val onPermissionResolved: () -> Unit,
    private val onTtsFinished: () -> Unit,
    private val onError: (message: String?, isAuthExpired: Boolean) -> Unit,
    private val onPostPermissionAlertNotification: (tool: String) -> Unit,
    private val onCancelPermissionAlertNotification: () -> Unit,
    private val observeTurnEvents: (serverUrl: String, sessionId: String, onReady: () -> Unit) -> Flow<TurnEvent> = { url, sid, ready ->
        eventStreamClient.observeTurnEvents(
            serverUrl = url,
            targetSessionId = sid,
            onReady = ready,
        )
    },
    private val buildVoiceReplyContext: () -> VoiceReplyContext = {
        VoiceReplyContext(focusedReply = currentLastReply())
    },
    private val currentReplyContextTargetMessageId: () -> String? = { null },
    private val onDirectAnswer: (DirectVoiceAnswer?) -> Unit = {},
    private val setActivePromptTurn: (Boolean) -> Unit = {},
    private val ensureAutoSpeakAnchorBeforeTurn: (baselineMessageId: String?, baselineCreatedAtMs: Long) -> Unit = { _, _ -> },
    private val ensureCursorSeededBeforeTurn: (baselineMessageId: String?, baselineCreatedAtMs: Long) -> Unit = { _, _ -> },
    private val syncForPromptTurnCompletion: (suspend (sentUserMessageId: String?, preSendLatestRecordId: String?) -> PromptTurnSettlement)? = null,
    private val onSyncForManualReadAloud: (suspend () -> Unit)? = null,
    private val hasCachedResponses: () -> Boolean = { !currentLastReply().isNullOrBlank() },
    private val nextAutoSpeakItem: () -> AssistantResponseItem? = { null },
    private val nextUnreadItem: () -> AssistantResponseItem? = { null },
    private val itemsForManualReadAloud: () -> List<AssistantResponseItem> = { emptyList() },
    private val markItemHeard: (AssistantResponseItem) -> Unit = {},
    private val setSpeakingMessage: (String?) -> Unit = {},
    private val onSpeechAudibleStart: (messageId: String?, estimatedDurationMs: Long?) -> Unit = { _, _ -> },
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val permissionAlertDelayMs: Long = PttForegroundService.PERMISSION_ALERT_DELAY_MS,
    private val eventStreamReadyTimeoutMs: Long = EVENT_STREAM_READY_TIMEOUT_MS,
    private val onPlayCue: (CueEvent) -> Unit = {},
    private val stillWorkingCueDelayMs: Long = DEFAULT_STILL_WORKING_DELAY_MS,
) {
    private val turnLock = Any()
    private var activeTurnJob: Job? = null
    private var organizeJob: Job? = null
    private var activeOrganizeToken: Any? = null
    private val speechCoordinator = SpeechCoordinator()

    val isPlaybackActive: Boolean
        get() = speechCoordinator.isPlaybackActive

    suspend fun finalizeQuickTranscript(
        serverId: String,
        serverUrl: String,
        sessionKey: String,
        pinnedSession: PinnedSession,
        rawTranscript: String,
    ) {
        val trimmedRaw = rawTranscript.trim()
        val finalQuick = if (tokenStore.getEffectiveQuickTranscriptCleanup(serverId)) {
            val cleanupResult = promptOrganizer.cleanQuickTranscript(
                serverUrl = serverUrl,
                rawTranscript = trimmedRaw,
                sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession),
            )
            voiceUxLogger.log(
                category = VoiceUxLogger.CAT_ORGANIZE,
                event = "quick_cleanup",
                sessionKey = sessionKey,
                metrics = mapOf(
                    "latencyMs" to cleanupResult.latencyMs,
                    "timedOut" to cleanupResult.timedOut,
                    "fallbackUsed" to cleanupResult.fallbackUsed,
                    "inputChars" to trimmedRaw.length,
                    "outputChars" to cleanupResult.text.length,
                ),
            )
            cleanupResult.text.ifBlank { trimmedRaw }
        } else {
            trimmedRaw
        }

        if (currentState() != PttState.TRANSCRIBING_SEND) {
            return
        }

        setPendingQuickTranscript(finalQuick)
        updateLastTranscript(finalQuick)
        syncActiveBubble(null)
        onTranscriptReady(finalQuick)
    }

    fun dispatchPrompt(fromStagedDraft: Boolean) {
        val activeServer = tokenStore.getActiveServer()
        val serverId = activeServer?.id
        val serverUrl = activeServer?.serverUrl
        val pinnedSession = activeServer?.lastPinnedSession
        if (serverId == null || serverUrl.isNullOrBlank() || pinnedSession == null) {
            onError("Server connection or pinned session is missing", false)
            return
        }
        val sessionKey = TokenStore.sessionKeyFor(serverId, pinnedSession)
        val promptText = if (fromStagedDraft) {
            (currentStagedDraft()?.organizedText ?: tokenStore.getStagedDraft(sessionKey)?.organizedText).orEmpty()
        } else {
            currentPendingQuickTranscript().orEmpty()
        }.trim()

        clearPendingQuickTranscript()
        if (promptText.isEmpty()) {
            onError("Cannot send empty prompt", false)
            return
        }
        updateLastTranscript(promptText)

        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                if (!fromStagedDraft) {
                    val customServerId = activeServer.takeIf { it.voiceOverride != null }?.id
                    val allowDirectAnswer = tokenStore.getEffectiveQuickTurnDirectAnswer(serverId)
                    val replyContext = if (allowDirectAnswer) buildVoiceReplyContext() else VoiceReplyContext()
                    val targetMessageId = if (allowDirectAnswer) currentReplyContextTargetMessageId() else null
                    val decision = voiceControlPlane.evaluateTurn(
                        serverUrl = serverUrl,
                        utterance = promptText,
                        replyContext = replyContext,
                        currentDetailLevel = tokenStore.getEffectiveSpokenDetailLevel(serverId),
                        currentInputMode = tokenStore.getEffectiveTtsInputMode(serverId),
                        allowDirectAnswer = allowDirectAnswer,
                        currentCueMode = tokenStore.getEffectiveAudioCueMode(serverId),
                    )
                    when (decision) {
                        is VoiceControlDecision.LiveTuning -> {
                            decision.detailLevel?.let { tokenStore.saveSpokenDetailLevel(it, customServerId) }
                            decision.inputMode?.let { tokenStore.saveTtsInputMode(it, customServerId) }
                            decision.cueMode?.let { tokenStore.saveAudioCueMode(it, customServerId) }
                            voiceUxLogger.log(
                                category = VoiceUxLogger.CAT_TURN,
                                event = "live_tuning",
                                sessionKey = sessionKey,
                                metrics = mapOf(
                                    "detailLevel" to decision.detailLevel?.name,
                                    "inputMode" to decision.inputMode?.name,
                                    "cueMode" to decision.cueMode?.name,
                                ),
                            )
                            updateStatusText(decision.confirmationSpeech)
                            syncActiveBubble(decision.confirmationSpeech)
                            onAgentCompleted(true)
                            if (currentState() == PttState.SPEAKING_TTS) {
                                speechCoordinator.enqueueOperatorSpeechAndAwait(
                                    serverId = serverId,
                                    serverUrl = serverUrl,
                                    pinnedSession = pinnedSession,
                                    text = decision.confirmationSpeech,
                                    speechKind = TTS_KIND_CONTROL,
                                )
                            }
                            return@launch
                        }

                        is VoiceControlDecision.AnswerDirectly -> {
                            voiceUxLogger.log(
                                category = VoiceUxLogger.CAT_TURN,
                                event = "answer_directly",
                                sessionKey = sessionKey,
                                metrics = mapOf("chars" to decision.spokenAnswer.length),
                            )
                            onDirectAnswer(
                                DirectVoiceAnswer(
                                    question = promptText,
                                    spokenAnswer = decision.spokenAnswer,
                                    targetMessageId = targetMessageId,
                                ),
                            )
                            updateStatusText(decision.spokenAnswer)
                            syncActiveBubble(decision.spokenAnswer)
                            onAgentCompleted(true)
                            if (currentState() == PttState.SPEAKING_TTS) {
                                speechCoordinator.enqueueOperatorSpeechAndAwait(
                                    serverId = serverId,
                                    serverUrl = serverUrl,
                                    pinnedSession = pinnedSession,
                                    text = decision.spokenAnswer,
                                    speechKind = TTS_KIND_CONTROL,
                                )
                            }
                            return@launch
                        }

                        VoiceControlDecision.PassThrough -> Unit
                    }
                }

                runAgentTurn(
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                    promptText = promptText,
                    fromStagedDraft = fromStagedDraft,
                )
            } finally {
                synchronized(turnLock) {
                    if (activeTurnJob === jobRef) {
                        activeTurnJob = null
                    }
                }
            }
        }
        jobRef = job

        val prev: Job?
        synchronized(turnLock) {
            prev = activeTurnJob
            activeTurnJob = job
        }
        prev?.cancel(CancellationException("Superseded by new prompt"))
        job.start()
    }

    internal fun enqueueOperatorSpeech(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        text: String,
        speechKind: String = TTS_KIND_CONTROL,
    ) {
        speechCoordinator.enqueueOperatorSpeech(
            serverId = serverId,
            serverUrl = serverUrl,
            pinnedSession = pinnedSession,
            text = text,
            speechKind = speechKind,
        )
    }

    internal suspend fun runAgentTurn(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        promptText: String,
        fromStagedDraft: Boolean,
    ) {
        val turnStartedMs = clockElapsedMs()
        val sessionKey = TokenStore.sessionKeyFor(serverId, pinnedSession)
        onDirectAnswer(null)
        try {
            val baselineWindow = sessionClient.fetchTurnsWindow(
                serverUrl = serverUrl,
                sessionId = pinnedSession.sessionId,
                directory = pinnedSession.directory,
                maxPages = 1,
                pageLimit = SessionClient.DEFAULT_PAGE_LIMIT,
            )
            val baselineSettledId = baselineWindow.latestSettledMessageId
            val baselineSettledCreatedAt = baselineSettledId?.let { id ->
                baselineWindow.records.firstOrNull { it.id == id }?.createdAtMs
            } ?: 0L
            val baselineAssistantId = baselineWindow.records
                .lastOrNull { it is ParsedSessionRecord.AssistantRecord }
                ?.id
            val preSendLatestRecordId = baselineWindow.records.lastOrNull()?.id

            val anchorId = baselineSettledId ?: baselineAssistantId
            val anchorCreatedAt = anchorId?.let { id ->
                baselineWindow.records.firstOrNull { it.id == id }?.createdAtMs
            } ?: 0L
            ensureAutoSpeakAnchorBeforeTurn(anchorId, anchorCreatedAt)
            ensureCursorSeededBeforeTurn(
                baselineSettledId,
                baselineSettledCreatedAt,
            )

            coroutineScope {
                val wsReady = CompletableDeferred<Unit>()
                val turnEvents = Channel<TurnEvent>(Channel.UNLIMITED)
                val acceptEvents = AtomicBoolean(false)
                val eventCollectorJob = launch(ioDispatcher) {
                    try {
                        observeTurnEvents(
                            serverUrl,
                            pinnedSession.sessionId,
                        ) {
                            wsReady.complete(Unit)
                        }.collect { event ->
                            if (acceptEvents.get()) {
                                turnEvents.trySend(event)
                            }
                        }
                    } finally {
                        turnEvents.close()
                    }
                }

                var permissionDelayJob: Job? = null
                var stillWorkingJob: Job? = null
                try {
                    withTimeoutOrNull(eventStreamReadyTimeoutMs) {
                        wsReady.await()
                    }
                    while (turnEvents.tryReceive().isSuccess) {
                        // Drain any stale buffered event before arming prompt acceptance.
                    }
                    setActivePromptTurn(true)
                    acceptEvents.set(true)

                    val sentUserMessageId = sessionClient.sendPrompt(
                        serverUrl = serverUrl,
                        sessionId = pinnedSession.sessionId,
                        directory = pinnedSession.directory,
                        text = PttForegroundService.formatVoicePrompt(promptText),
                    )

                    if (fromStagedDraft) {
                        tokenStore.clearStagedDraft(sessionKey)
                        updateStagedDraft(null)
                        voiceUxLogger.log(VoiceUxLogger.CAT_DRAFT, "committed", sessionKey)
                    }

                    stillWorkingJob = launch(ioDispatcher) {
                        delay(stillWorkingCueDelayMs)
                        if (currentState() == PttState.WAITING_AGENT) {
                            onPlayCue(CueEvent.STILL_WORKING)
                        }
                    }

                    for (turnEvent in turnEvents) {
                        when (turnEvent) {
                            is TurnEvent.PermissionAsked -> {
                                stillWorkingJob?.cancel()
                                stillWorkingJob = null
                                permissionDelayJob?.cancel()
                                permissionDelayJob = launch(ioDispatcher) {
                                    delay(permissionAlertDelayMs)
                                    onPermissionAsked(turnEvent.tool)
                                    onPostPermissionAlertNotification(turnEvent.tool)
                                    speechCoordinator.enqueuePermissionAlert(
                                        serverId = serverId,
                                        serverUrl = serverUrl,
                                        toolName = turnEvent.tool,
                                    )
                                }
                            }

                            TurnEvent.PermissionResolved -> {
                                permissionDelayJob?.cancel()
                                permissionDelayJob = null
                                speechCoordinator.cancelPermissionAlert()
                                onCancelPermissionAlertNotification()
                                if (currentState() == PttState.PERMISSION_BLOCKED) {
                                    onPermissionResolved()
                                }
                            }

                            is TurnEvent.SyntheticArrived -> Unit

                            TurnEvent.Completed -> {
                                if (syncForPromptTurnCompletion != null) {
                                    var settlement = syncForPromptTurnCompletion.invoke(
                                        sentUserMessageId,
                                        preSendLatestRecordId,
                                    )
                                    if (settlement == PromptTurnSettlement.FlushLagRetryNeeded) {
                                        delay(FLUSH_LAG_RETRY_1_MS)
                                        settlement = syncForPromptTurnCompletion.invoke(
                                            sentUserMessageId,
                                            preSendLatestRecordId,
                                        )
                                        if (settlement == PromptTurnSettlement.FlushLagRetryNeeded) {
                                            delay(FLUSH_LAG_RETRY_2_MS)
                                            settlement = syncForPromptTurnCompletion.invoke(
                                                sentUserMessageId,
                                                preSendLatestRecordId,
                                            )
                                        }
                                    }

                                    when (settlement) {
                                        PromptTurnSettlement.TurnStillRunning -> continue

                                        PromptTurnSettlement.ReadyToSpeak -> {
                                            stillWorkingJob?.cancel()
                                            stillWorkingJob = null
                                            permissionDelayJob?.cancel()
                                            permissionDelayJob = null
                                            speechCoordinator.cancelPermissionAlert()
                                            onCancelPermissionAlertNotification()
                                            voiceUxLogger.log(
                                                category = VoiceUxLogger.CAT_TURN,
                                                event = "completed",
                                                sessionKey = sessionKey,
                                                metrics = mapOf(
                                                    "turnMs" to (clockElapsedMs() - turnStartedMs),
                                                    "hasReply" to true,
                                                    "fromDraft" to fromStagedDraft,
                                                ),
                                            )
                                            onAgentCompleted(true)
                                            setActivePromptTurn(false)
                                            if (currentState() == PttState.SPEAKING_TTS) {
                                                speechCoordinator.enqueueDrainAutoRepliesAndAwait(
                                                    serverId = serverId,
                                                    serverUrl = serverUrl,
                                                    pinnedSession = pinnedSession,
                                                )
                                            }
                                            break
                                        }

                                        PromptTurnSettlement.SettledNoSpeech,
                                        PromptTurnSettlement.FlushLagRetryNeeded,
                                        -> {
                                            stillWorkingJob?.cancel()
                                            stillWorkingJob = null
                                            permissionDelayJob?.cancel()
                                            permissionDelayJob = null
                                            speechCoordinator.cancelPermissionAlert()
                                            onCancelPermissionAlertNotification()
                                            voiceUxLogger.log(
                                                category = VoiceUxLogger.CAT_TURN,
                                                event = "completed",
                                                sessionKey = sessionKey,
                                                metrics = mapOf(
                                                    "turnMs" to (clockElapsedMs() - turnStartedMs),
                                                    "hasReply" to false,
                                                    "fromDraft" to fromStagedDraft,
                                                    "settlement" to settlement.toString(),
                                                ),
                                            )
                                            onAgentCompleted(false)
                                            setActivePromptTurn(false)
                                            break
                                        }
                                    }
                                } else {
                                    stillWorkingJob?.cancel()
                                    stillWorkingJob = null
                                    permissionDelayJob?.cancel()
                                    permissionDelayJob = null
                                    speechCoordinator.cancelPermissionAlert()
                                    onCancelPermissionAlertNotification()

                                    val afterWindow = sessionClient.fetchTurnsWindow(
                                        serverUrl = serverUrl,
                                        sessionId = pinnedSession.sessionId,
                                        directory = pinnedSession.directory,
                                        stopAtMessageIds = baselineAssistantId?.let(::setOf).orEmpty(),
                                        maxPages = SessionClient.DEFAULT_MAX_PAGES,
                                        pageLimit = SessionClient.DEFAULT_PAGE_LIMIT,
                                    )
                                    val afterIdx = baselineAssistantId?.let { id ->
                                        afterWindow.records.indexOfFirst { it.id == id }
                                    } ?: -1
                                    val newerIds = if (afterIdx >= 0) {
                                        afterWindow.records.subList(afterIdx + 1, afterWindow.records.size)
                                            .asSequence()
                                            .map { it.id }
                                            .toHashSet()
                                    } else {
                                        null
                                    }
                                    val itemsToSpeak = afterWindow.turns
                                        .asSequence()
                                        .flatMap { it.responses.asSequence() }
                                        .filter { it.isCompleted && it.assistantText.isNotBlank() }
                                        .filter { newerIds == null || it.messageId in newerIds }
                                        .toList()

                                    voiceUxLogger.log(
                                        category = VoiceUxLogger.CAT_TURN,
                                        event = "completed",
                                        sessionKey = sessionKey,
                                        metrics = mapOf(
                                            "turnMs" to (clockElapsedMs() - turnStartedMs),
                                            "hasReply" to itemsToSpeak.isNotEmpty(),
                                            "fromDraft" to fromStagedDraft,
                                        ),
                                    )

                                    if (itemsToSpeak.isNotEmpty()) {
                                        updateLastSpokenSummary(null)
                                        updateLastReply(itemsToSpeak.last().assistantText)
                                        syncActiveBubble(null)
                                        onAgentCompleted(true)
                                        setActivePromptTurn(false)
                                        if (currentState() == PttState.SPEAKING_TTS) {
                                            speechCoordinator.enqueueExplicitItemsAndAwait(
                                                serverId = serverId,
                                                serverUrl = serverUrl,
                                                pinnedSession = pinnedSession,
                                                items = itemsToSpeak,
                                            )
                                        }
                                    } else {
                                        onAgentCompleted(false)
                                        setActivePromptTurn(false)
                                    }
                                    break
                                }
                            }

                            is TurnEvent.Failed -> {
                                stillWorkingJob?.cancel()
                                stillWorkingJob = null
                                permissionDelayJob?.cancel()
                                permissionDelayJob = null
                                speechCoordinator.cancelPermissionAlert()
                                onCancelPermissionAlertNotification()
                                voiceUxLogger.log(
                                    category = VoiceUxLogger.CAT_TURN,
                                    event = "failed",
                                    sessionKey = sessionKey,
                                    metrics = mapOf("turnMs" to (clockElapsedMs() - turnStartedMs)),
                                )
                                onError(turnEvent.message.ifBlank { "Agent execution failed" }, false)
                                break
                            }

                            is TurnEvent.Interrupted -> {
                                stillWorkingJob?.cancel()
                                stillWorkingJob = null
                                permissionDelayJob?.cancel()
                                permissionDelayJob = null
                                speechCoordinator.cancelPermissionAlert()
                                onCancelPermissionAlertNotification()
                                updateStatusText("Turn interrupted (${turnEvent.reason})")
                                voiceUxLogger.log(
                                    category = VoiceUxLogger.CAT_TURN,
                                    event = "interrupted",
                                    sessionKey = sessionKey,
                                    metrics = mapOf(
                                        "reason" to turnEvent.reason,
                                        "turnMs" to (clockElapsedMs() - turnStartedMs),
                                    ),
                                )
                                onAgentInterrupted()
                                break
                            }
                        }
                    }
                } finally {
                    setActivePromptTurn(false)
                    stillWorkingJob?.cancel()
                    permissionDelayJob?.cancel()
                    speechCoordinator.cancelPermissionAlert()
                    eventCollectorJob.cancel()
                    turnEvents.close()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            handleUnauthorizedIfNeeded(serverId, t)
            val is401 = PttForegroundService.isUnauthorizedError(t)
            voiceUxLogger.log(
                category = VoiceUxLogger.CAT_TURN,
                event = if (is401) "auth_401" else "error",
                sessionKey = sessionKey,
                metrics = mapOf("turnMs" to (clockElapsedMs() - turnStartedMs)),
            )
            onError(
                t.message?.takeIf { it.isNotBlank() } ?: "Voice turn failed",
                is401,
            )
        }
    }

    fun speakPendingReplies() {
        val activeServer = tokenStore.getActiveServer()
        val pinnedSession = activeServer?.lastPinnedSession
        if (activeServer == null || pinnedSession == null) {
            onError("Server connection or pinned session is missing", false)
            return
        }
        speechCoordinator.enqueueDrainAutoReplies(
            serverId = activeServer.id,
            serverUrl = activeServer.serverUrl,
            pinnedSession = pinnedSession,
        )
    }

    fun readLastReply() {
        val activeServer = tokenStore.getActiveServer()
        val pinnedSession = activeServer?.lastPinnedSession
        if (activeServer == null || pinnedSession == null) {
            onError("Server connection or pinned session is missing", false)
            return
        }
        speechCoordinator.preemptWithManualRead(
            serverId = activeServer.id,
            serverUrl = activeServer.serverUrl,
            pinnedSession = pinnedSession,
            drainTrailingAutoItems = (onSyncForManualReadAloud != null),
        )
    }

    fun launchDraftPreviewSpeech(serverUrl: String, draft: StagedDraft) {
        val activeServerId = tokenStore.getActiveServer()?.id
            ?: draft.sessionKey.substringBefore('|').takeIf { it.isNotBlank() }
        speechCoordinator.preemptWithDraftPreview(
            serverId = activeServerId,
            serverUrl = serverUrl,
            draft = draft,
        )
    }

    private enum class ActiveSpeechKind {
        DRAIN_AUTO_REPLIES,
        OPERATOR_SPEECH,
        PERMISSION_ALERT,
        MANUAL_READ,
        DRAFT_PREVIEW,
        EXPLICIT_ITEMS,
    }

    private enum class IntentOutcome {
        COMPLETED,
        ABORTED,
        ERROR,
    }

    private sealed interface SpeechIntent {
        val epoch: Long
        val kind: ActiveSpeechKind
        val isTtsStateIntent: Boolean
            get() = kind != ActiveSpeechKind.PERMISSION_ALERT

        data class DrainAutoReplies(
            override val epoch: Long,
            val serverId: String,
            val serverUrl: String,
            val pinnedSession: PinnedSession,
            val waiters: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
        ) : SpeechIntent {
            override val kind: ActiveSpeechKind = ActiveSpeechKind.DRAIN_AUTO_REPLIES
        }

        data class OperatorSpeech(
            override val epoch: Long,
            val serverId: String,
            val serverUrl: String,
            val pinnedSession: PinnedSession,
            val text: String,
            val speechKind: String,
            val waiters: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
        ) : SpeechIntent {
            override val kind: ActiveSpeechKind = ActiveSpeechKind.OPERATOR_SPEECH
        }

        data class PermissionAlert(
            override val epoch: Long,
            val permissionEpoch: Long,
            val serverId: String,
            val serverUrl: String,
            val toolName: String,
        ) : SpeechIntent {
            override val kind: ActiveSpeechKind = ActiveSpeechKind.PERMISSION_ALERT
        }

        data class ManualRead(
            override val epoch: Long,
            val serverId: String,
            val serverUrl: String,
            val pinnedSession: PinnedSession,
            val drainTrailingAutoItems: Boolean,
        ) : SpeechIntent {
            override val kind: ActiveSpeechKind = ActiveSpeechKind.MANUAL_READ
        }

        data class DraftPreview(
            override val epoch: Long,
            val serverId: String?,
            val serverUrl: String,
            val draft: StagedDraft,
        ) : SpeechIntent {
            override val kind: ActiveSpeechKind = ActiveSpeechKind.DRAFT_PREVIEW
        }

        data class ExplicitItems(
            override val epoch: Long,
            val serverId: String,
            val serverUrl: String,
            val pinnedSession: PinnedSession,
            val items: List<AssistantResponseItem>,
            val waiters: MutableList<CompletableDeferred<Unit>> = mutableListOf(),
        ) : SpeechIntent {
            override val kind: ActiveSpeechKind = ActiveSpeechKind.EXPLICIT_ITEMS
        }
    }

    private inner class SpeechCoordinator {
        private val lock = Any()
        private val playbackActiveFlag = AtomicBoolean(false)
        private var epoch: Long = 0L
        private var permissionEpoch: Long = 0L
        private val queue = ArrayDeque<SpeechIntent>()
        private var workerJob: Job? = null
        private var activeIntentJob: Job? = null
        private var activeIntent: SpeechIntent? = null
        private var drainRecheckRequested: Boolean = false
        private val activeDrainWaiters = mutableListOf<CompletableDeferred<Unit>>()

        val isPlaybackActive: Boolean
            get() = playbackActiveFlag.get()

        fun enqueueDrainAutoReplies(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
        ) {
            enqueueDrainAutoRepliesInternal(serverId, serverUrl, pinnedSession, waiter = null)
        }

        suspend fun enqueueDrainAutoRepliesAndAwait(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
        ) {
            val waiter = CompletableDeferred<Unit>()
            enqueueDrainAutoRepliesInternal(serverId, serverUrl, pinnedSession, waiter = waiter)
            waiter.await()
        }

        private fun enqueueDrainAutoRepliesInternal(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            waiter: CompletableDeferred<Unit>?,
        ) {
            val jobToStart: Job?
            synchronized(lock) {
                val curActive = activeIntent
                if ((curActive is SpeechIntent.DrainAutoReplies || curActive is SpeechIntent.OperatorSpeech) &&
                    curActive.epoch == epoch
                ) {
                    drainRecheckRequested = true
                    if (waiter != null) activeDrainWaiters += waiter
                    playbackActiveFlag.set(true)
                    jobToStart = ensureWorkerLocked()
                } else {
                    val existingQueued = queue.firstOrNull {
                        it is SpeechIntent.DrainAutoReplies && it.epoch == epoch
                    } as? SpeechIntent.DrainAutoReplies
                    if (existingQueued != null) {
                        if (waiter != null) existingQueued.waiters += waiter
                        playbackActiveFlag.set(true)
                        jobToStart = ensureWorkerLocked()
                    } else {
                        val intent = SpeechIntent.DrainAutoReplies(
                            epoch = epoch,
                            serverId = serverId,
                            serverUrl = serverUrl,
                            pinnedSession = pinnedSession,
                        )
                        if (waiter != null) intent.waiters += waiter
                        queue.addLast(intent)
                        playbackActiveFlag.set(true)
                        jobToStart = ensureWorkerLocked()
                    }
                }
            }
            jobToStart?.start()
        }

        fun enqueueOperatorSpeech(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            text: String,
            speechKind: String,
        ) {
            enqueueOperatorSpeechInternal(serverId, serverUrl, pinnedSession, text, speechKind, waiter = null)
        }

        suspend fun enqueueOperatorSpeechAndAwait(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            text: String,
            speechKind: String,
        ) {
            val waiter = CompletableDeferred<Unit>()
            enqueueOperatorSpeechInternal(serverId, serverUrl, pinnedSession, text, speechKind, waiter = waiter)
            waiter.await()
        }

        private fun enqueueOperatorSpeechInternal(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            text: String,
            speechKind: String,
            waiter: CompletableDeferred<Unit>?,
        ) {
            val jobToStart: Job?
            synchronized(lock) {
                val intent = SpeechIntent.OperatorSpeech(
                    epoch = epoch,
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                    text = text,
                    speechKind = speechKind,
                )
                if (waiter != null) intent.waiters += waiter
                queue.addLast(intent)
                playbackActiveFlag.set(true)
                jobToStart = ensureWorkerLocked()
            }
            jobToStart?.start()
        }

        suspend fun enqueueExplicitItemsAndAwait(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            items: List<AssistantResponseItem>,
        ) {
            val waiter = CompletableDeferred<Unit>()
            val jobToStart: Job?
            synchronized(lock) {
                val intent = SpeechIntent.ExplicitItems(
                    epoch = epoch,
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                    items = items,
                )
                intent.waiters += waiter
                queue.addLast(intent)
                playbackActiveFlag.set(true)
                jobToStart = ensureWorkerLocked()
            }
            jobToStart?.start()
            waiter.await()
        }

        fun enqueuePermissionAlert(
            serverId: String,
            serverUrl: String,
            toolName: String,
        ) {
            val jobToStart: Job?
            synchronized(lock) {
                queue.removeAll { it is SpeechIntent.PermissionAlert }
                queue.addLast(
                    SpeechIntent.PermissionAlert(
                        epoch = epoch,
                        permissionEpoch = permissionEpoch,
                        serverId = serverId,
                        serverUrl = serverUrl,
                        toolName = toolName,
                    ),
                )
                playbackActiveFlag.set(true)
                jobToStart = ensureWorkerLocked()
            }
            jobToStart?.start()
        }

        fun cancelPermissionAlert() {
            val jobToCancel: Job?
            val shouldStopTts: Boolean
            synchronized(lock) {
                permissionEpoch += 1L
                queue.removeAll { it is SpeechIntent.PermissionAlert }
                if (activeIntent is SpeechIntent.PermissionAlert) {
                    jobToCancel = activeIntentJob
                    shouldStopTts = true
                } else {
                    jobToCancel = null
                    shouldStopTts = false
                }
            }
            if (shouldStopTts) {
                stopTts()
                jobToCancel?.cancel(CancellationException("Permission resolved"))
            }
        }

        fun preemptWithManualRead(
            serverId: String,
            serverUrl: String,
            pinnedSession: PinnedSession,
            drainTrailingAutoItems: Boolean,
        ) {
            val waitersToRelease: List<CompletableDeferred<Unit>>
            val jobToCancel: Job?
            val jobToStart: Job?
            synchronized(lock) {
                epoch += 1L
                permissionEpoch += 1L
                drainRecheckRequested = false
                waitersToRelease = collectAndClearAllWaitersLocked()
                queue.clear()
                queue.addLast(
                    SpeechIntent.ManualRead(
                        epoch = epoch,
                        serverId = serverId,
                        serverUrl = serverUrl,
                        pinnedSession = pinnedSession,
                        drainTrailingAutoItems = drainTrailingAutoItems,
                    ),
                )
                jobToCancel = activeIntentJob
                playbackActiveFlag.set(true)
                jobToStart = ensureWorkerLocked()
            }
            setSpeakingMessage(null)
            stopTts()
            jobToCancel?.cancel(CancellationException("Preempted by manual read"))
            waitersToRelease.forEach { it.complete(Unit) }
            jobToStart?.start()
        }

        fun preemptWithDraftPreview(
            serverId: String?,
            serverUrl: String,
            draft: StagedDraft,
        ) {
            val waitersToRelease: List<CompletableDeferred<Unit>>
            val jobToCancel: Job?
            val jobToStart: Job?
            synchronized(lock) {
                epoch += 1L
                permissionEpoch += 1L
                drainRecheckRequested = false
                waitersToRelease = collectAndClearAllWaitersLocked()
                queue.clear()
                queue.addLast(
                    SpeechIntent.DraftPreview(
                        epoch = epoch,
                        serverId = serverId,
                        serverUrl = serverUrl,
                        draft = draft,
                    ),
                )
                jobToCancel = activeIntentJob
                playbackActiveFlag.set(true)
                jobToStart = ensureWorkerLocked()
            }
            setSpeakingMessage(null)
            stopTts()
            jobToCancel?.cancel(CancellationException("Preempted by draft preview"))
            waitersToRelease.forEach { it.complete(Unit) }
            jobToStart?.start()
        }

        fun abortAllSpeech() {
            val waitersToRelease: List<CompletableDeferred<Unit>>
            val jobToCancel: Job?
            synchronized(lock) {
                epoch += 1L
                permissionEpoch += 1L
                drainRecheckRequested = false
                waitersToRelease = collectAndClearAllWaitersLocked()
                queue.clear()
                jobToCancel = activeIntentJob
                playbackActiveFlag.set(false)
            }
            setSpeakingMessage(null)
            stopTts()
            jobToCancel?.cancel(CancellationException("Speech aborted"))
            waitersToRelease.forEach { it.complete(Unit) }
        }

        private fun ensureWorkerLocked(): Job? {
            if (workerJob?.isActive == true) return null
            lateinit var myJob: Job
            val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                runWorkerLoop(myJob)
            }
            myJob = job
            workerJob = job
            return job
        }

        private suspend fun runWorkerLoop(myWorkerJob: Job) {
            try {
                supervisorScope {
                    while (isActive) {
                        val intent = synchronized(lock) {
                            if (queue.isEmpty()) {
                                playbackActiveFlag.set(false)
                                if (workerJob === myWorkerJob) {
                                    workerJob = null
                                }
                                null
                            } else {
                                val next = queue.removeFirst()
                                activeIntent = next
                                playbackActiveFlag.set(true)
                                next
                            }
                        } ?: break

                        var outcome = IntentOutcome.ABORTED
                        val child = launch(start = CoroutineStart.LAZY) {
                            outcome = executeIntent(intent)
                        }
                        val registered = synchronized(lock) {
                            val epochOk = epoch == intent.epoch
                            val permOk = intent !is SpeechIntent.PermissionAlert ||
                                permissionEpoch == intent.permissionEpoch
                            if (epochOk && permOk && activeIntent === intent) {
                                activeIntentJob = child
                                true
                            } else {
                                false
                            }
                        }
                        if (!registered) {
                            child.cancel()
                        } else {
                            child.start()
                            child.join()
                        }

                        var shouldNotifyFinished = false
                        val waitersToComplete: List<CompletableDeferred<Unit>>
                        synchronized(lock) {
                            if (activeIntent === intent) {
                                activeIntent = null
                            }
                            if (activeIntentJob === child) {
                                activeIntentJob = null
                            }
                            if (drainRecheckRequested &&
                                epoch == intent.epoch &&
                                outcome == IntentOutcome.COMPLETED &&
                                (intent is SpeechIntent.DrainAutoReplies || intent is SpeechIntent.OperatorSpeech) &&
                                queue.none { it is SpeechIntent.OperatorSpeech } &&
                                currentState() == PttState.SPEAKING_TTS
                            ) {
                                drainRecheckRequested = false
                                val (sid, surl, ps) = if (intent is SpeechIntent.DrainAutoReplies) {
                                    Triple(intent.serverId, intent.serverUrl, intent.pinnedSession)
                                } else {
                                    val op = intent as SpeechIntent.OperatorSpeech
                                    Triple(op.serverId, op.serverUrl, op.pinnedSession)
                                }
                                val carryWaiters = activeDrainWaiters.toMutableList()
                                activeDrainWaiters.clear()
                                queue.addFirst(
                                    SpeechIntent.DrainAutoReplies(
                                        epoch = intent.epoch,
                                        serverId = sid,
                                        serverUrl = surl,
                                        pinnedSession = ps,
                                        waiters = carryWaiters,
                                    ),
                                )
                                waitersToComplete = intentWaitersOf(intent)
                            } else {
                                drainRecheckRequested = false
                                waitersToComplete = buildList {
                                    addAll(intentWaitersOf(intent))
                                    addAll(activeDrainWaiters)
                                }
                                activeDrainWaiters.clear()
                                val state = currentState()
                                val stateMatches = when (intent.kind) {
                                    ActiveSpeechKind.DRAFT_PREVIEW -> state == PttState.STAGED_DRAFT_SPEAKING
                                    ActiveSpeechKind.PERMISSION_ALERT -> false
                                    ActiveSpeechKind.DRAIN_AUTO_REPLIES,
                                    ActiveSpeechKind.OPERATOR_SPEECH,
                                    ActiveSpeechKind.MANUAL_READ,
                                    ActiveSpeechKind.EXPLICIT_ITEMS,
                                    -> state == PttState.SPEAKING_TTS
                                }
                                shouldNotifyFinished = intent.isTtsStateIntent &&
                                    outcome == IntentOutcome.COMPLETED &&
                                    epoch == intent.epoch &&
                                    queue.isEmpty() &&
                                    stateMatches
                                if (queue.isEmpty()) {
                                    playbackActiveFlag.set(false)
                                }
                            }
                        }

                        waitersToComplete.forEach { it.complete(Unit) }

                        if (shouldNotifyFinished) {
                            val stillCurrent = synchronized(lock) {
                                epoch == intent.epoch && queue.isEmpty() && activeIntent == null
                            }
                            if (stillCurrent) {
                                onTtsFinished()
                            }
                        }
                    }
                }
            } finally {
                val leftoverWaiters: List<CompletableDeferred<Unit>>
                synchronized(lock) {
                    activeIntentJob = null
                    activeIntent = null
                    drainRecheckRequested = false
                    leftoverWaiters = collectAndClearAllWaitersLocked()
                    queue.clear()
                    playbackActiveFlag.set(false)
                    if (workerJob === myWorkerJob) {
                        workerJob = null
                    }
                }
                leftoverWaiters.forEach { it.complete(Unit) }
            }
        }

        private suspend fun executeIntent(intent: SpeechIntent): IntentOutcome = when (intent) {
            is SpeechIntent.DrainAutoReplies -> {
                try {
                    speakQueuedAutoItemsLoop(
                        myEpoch = intent.epoch,
                        serverUrl = intent.serverUrl,
                        pinnedSession = intent.pinnedSession,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setSpeakingMessage(null)
                    handleUnauthorizedIfNeeded(intent.serverId, t)
                    onError(
                        t.message?.takeIf { it.isNotBlank() } ?: "TTS playback failed",
                        PttForegroundService.isUnauthorizedError(t),
                    )
                    IntentOutcome.ERROR
                }
            }

            is SpeechIntent.OperatorSpeech -> {
                try {
                    if (!isEpochActive(intent.epoch) ||
                        currentState() != PttState.SPEAKING_TTS ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    val result = speakTts(
                        TtsSpeakRequest(
                            serverUrl = intent.serverUrl,
                            text = intent.text,
                            role = VoiceRole.OPERATOR,
                            inputModeOverride = TtsInputMode.RAW,
                            kind = intent.speechKind,
                            notifySpokenSummary = false,
                        ),
                    )
                    if (result != TtsPlaybackResult.COMPLETED ||
                        !isEpochActive(intent.epoch) ||
                        currentState() != PttState.SPEAKING_TTS ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    speakQueuedAutoItemsLoop(
                        myEpoch = intent.epoch,
                        serverUrl = intent.serverUrl,
                        pinnedSession = intent.pinnedSession,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setSpeakingMessage(null)
                    handleUnauthorizedIfNeeded(intent.serverId, t)
                    onError(
                        t.message?.takeIf { it.isNotBlank() } ?: "TTS failed",
                        PttForegroundService.isUnauthorizedError(t),
                    )
                    IntentOutcome.ERROR
                }
            }

            is SpeechIntent.PermissionAlert -> {
                try {
                    if (!isEpochActive(intent.epoch) ||
                        !isPermissionEpochActive(intent.permissionEpoch) ||
                        currentState() != PttState.PERMISSION_BLOCKED ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    withStandalonePlaybackFocus {
                        if (!isEpochActive(intent.epoch) ||
                            !isPermissionEpochActive(intent.permissionEpoch) ||
                            currentState() != PttState.PERMISSION_BLOCKED ||
                            !currentCoroutineContext().isActive
                        ) {
                            return@withStandalonePlaybackFocus
                        }
                        speakTts(
                            TtsSpeakRequest(
                                serverUrl = intent.serverUrl,
                                text = PttForegroundService.formatPermissionAlertSpeech(intent.toolName),
                                role = VoiceRole.OPERATOR,
                                inputModeOverride = TtsInputMode.RAW,
                                kind = TTS_KIND_PERMISSION,
                                notifySpokenSummary = false,
                            ),
                        )
                    }
                    IntentOutcome.COMPLETED
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    IntentOutcome.ERROR
                }
            }

            is SpeechIntent.ManualRead -> executeManualRead(intent)

            is SpeechIntent.DraftPreview -> {
                try {
                    if (!isEpochActive(intent.epoch) ||
                        currentState() != PttState.STAGED_DRAFT_SPEAKING ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    val result = speakTts(
                        TtsSpeakRequest(
                            serverUrl = intent.serverUrl,
                            text = intent.draft.organizedText,
                            role = VoiceRole.OPERATOR,
                            inputModeOverride = TtsInputMode.RAW,
                            kind = TTS_KIND_DRAFT_PREVIEW,
                            notifySpokenSummary = false,
                        ),
                    )
                    if (result != TtsPlaybackResult.COMPLETED ||
                        !isEpochActive(intent.epoch) ||
                        currentState() != PttState.STAGED_DRAFT_SPEAKING ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_TTS,
                        event = "completed",
                        sessionKey = intent.draft.sessionKey,
                        metrics = mapOf("kind" to TTS_KIND_DRAFT_PREVIEW, "chars" to intent.draft.organizedText.length),
                    )
                    IntentOutcome.COMPLETED
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val sid = intent.serverId ?: tokenStore.getActiveServer()?.id
                    if (sid != null) {
                        handleUnauthorizedIfNeeded(sid, t)
                    }
                    if (isEpochActive(intent.epoch) &&
                        currentState() == PttState.STAGED_DRAFT_SPEAKING &&
                        currentCoroutineContext().isActive
                    ) {
                        IntentOutcome.COMPLETED
                    } else {
                        IntentOutcome.ABORTED
                    }
                }
            }

            is SpeechIntent.ExplicitItems -> {
                try {
                    speakExplicitItemsLoop(
                        myEpoch = intent.epoch,
                        serverUrl = intent.serverUrl,
                        pinnedSession = intent.pinnedSession,
                        initialItems = intent.items,
                        drainTrailingAutoItems = false,
                        isManualRead = false,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    setSpeakingMessage(null)
                    handleUnauthorizedIfNeeded(intent.serverId, t)
                    onError(
                        t.message?.takeIf { it.isNotBlank() } ?: "TTS playback failed",
                        PttForegroundService.isUnauthorizedError(t),
                    )
                    IntentOutcome.ERROR
                }
            }
        }

        private suspend fun executeManualRead(intent: SpeechIntent.ManualRead): IntentOutcome {
            return try {
                if (!isEpochActive(intent.epoch) ||
                    currentState() != PttState.SPEAKING_TTS ||
                    !currentCoroutineContext().isActive
                ) {
                    return IntentOutcome.ABORTED
                }
                val initialItems: List<AssistantResponseItem>
                if (onSyncForManualReadAloud != null) {
                    val refreshError = try {
                        onSyncForManualReadAloud.invoke()
                        null
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        t
                    }
                    if (!isEpochActive(intent.epoch) ||
                        currentState() != PttState.SPEAKING_TTS ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    if (refreshError != null) {
                        if (PttForegroundService.isUnauthorizedError(refreshError)) {
                            handleUnauthorizedIfNeeded(intent.serverId, refreshError)
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Session expired",
                                true,
                            )
                            return IntentOutcome.ERROR
                        }
                        if (!hasCachedResponses()) {
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                                false,
                            )
                            return IntentOutcome.ERROR
                        }
                    }
                    initialItems = itemsForManualReadAloud()
                } else {
                    val refreshResult = runCatching {
                        sessionClient.fetchTurnsWindow(
                            serverUrl = intent.serverUrl,
                            sessionId = intent.pinnedSession.sessionId,
                            directory = intent.pinnedSession.directory,
                            maxPages = 2,
                            pageLimit = SessionClient.DEFAULT_PAGE_LIMIT,
                        )
                    }
                    if (!isEpochActive(intent.epoch) ||
                        currentState() != PttState.SPEAKING_TTS ||
                        !currentCoroutineContext().isActive
                    ) {
                        return IntentOutcome.ABORTED
                    }
                    val refreshError = refreshResult.exceptionOrNull()
                    if (refreshError != null) {
                        if (refreshError is CancellationException) throw refreshError
                        if (PttForegroundService.isUnauthorizedError(refreshError)) {
                            handleUnauthorizedIfNeeded(intent.serverId, refreshError)
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Session expired",
                                true,
                            )
                            return IntentOutcome.ERROR
                        }
                        val cached = currentLastReply()?.takeIf { it.isNotBlank() }
                        if (cached == null) {
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                                false,
                            )
                            return IntentOutcome.ERROR
                        }
                        initialItems = listOf(
                            AssistantResponseItem(
                                messageId = "cached_reply",
                                turnId = "cached_turn",
                                triggerKind = TurnTriggerKind.USER_PROMPT,
                                triggerLabel = null,
                                triggerText = null,
                                assistantText = cached,
                                createdAtMs = 0L,
                                isFinalStep = true,
                                isCompleted = true,
                            ),
                        )
                    } else {
                        val window = refreshResult.getOrThrow()
                        initialItems = window.turns.lastOrNull { it.responses.isNotEmpty() }?.responses.orEmpty()
                    }
                }

                if (!isEpochActive(intent.epoch) ||
                    currentState() != PttState.SPEAKING_TTS ||
                    !currentCoroutineContext().isActive
                ) {
                    return IntentOutcome.ABORTED
                }

                if (initialItems.isEmpty()) {
                    updateStatusText("No previous assistant reply in this session")
                    return if (isEpochActive(intent.epoch) &&
                        currentState() == PttState.SPEAKING_TTS &&
                        currentCoroutineContext().isActive
                    ) {
                        IntentOutcome.COMPLETED
                    } else {
                        IntentOutcome.ABORTED
                    }
                }

                speakExplicitItemsLoop(
                    myEpoch = intent.epoch,
                    serverUrl = intent.serverUrl,
                    pinnedSession = intent.pinnedSession,
                    initialItems = initialItems,
                    drainTrailingAutoItems = intent.drainTrailingAutoItems,
                    isManualRead = true,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                setSpeakingMessage(null)
                handleUnauthorizedIfNeeded(intent.serverId, t)
                onError(
                    t.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                    PttForegroundService.isUnauthorizedError(t),
                )
                IntentOutcome.ERROR
            }
        }

        private suspend fun speakQueuedAutoItemsLoop(
            myEpoch: Long,
            serverUrl: String,
            pinnedSession: PinnedSession,
        ): IntentOutcome {
            val sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession)
            val attemptedIds = HashSet<String>()
            while (currentCoroutineContext().isActive &&
                isEpochActive(myEpoch) &&
                currentState() == PttState.SPEAKING_TTS
            ) {
                val nextItem = nextAutoSpeakItem()
                if (nextItem == null) {
                    val recheck = synchronized(lock) {
                        if (drainRecheckRequested && epoch == myEpoch) {
                            drainRecheckRequested = false
                            true
                        } else {
                            false
                        }
                    }
                    if (recheck) continue
                    break
                }
                if (!attemptedIds.add(nextItem.messageId)) break
                if (nextItem.assistantText.isBlank()) {
                    markItemHeard(nextItem)
                    continue
                }
                setSpeakingMessage(nextItem.messageId)
                val result = speakTts(
                    TtsSpeakRequest(
                        serverUrl = serverUrl,
                        text = nextItem.assistantText,
                        role = VoiceRole.AGENT,
                        sessionLabel = sessionLabel,
                        kind = TTS_KIND_REPLY,
                        notifySpokenSummary = true,
                        messageId = nextItem.messageId,
                        onAudibleStart = onSpeechAudibleStart,
                    ),
                )
                if (result != TtsPlaybackResult.COMPLETED ||
                    !isEpochActive(myEpoch) ||
                    currentState() != PttState.SPEAKING_TTS ||
                    !currentCoroutineContext().isActive
                ) {
                    setSpeakingMessage(null)
                    return IntentOutcome.ABORTED
                }
                markItemHeard(nextItem)
                setSpeakingMessage(null)
                val yieldToQueuedIntent = synchronized(lock) {
                    epoch == myEpoch && queue.any { it !is SpeechIntent.DrainAutoReplies }
                }
                if (yieldToQueuedIntent) {
                    break
                }
            }
            setSpeakingMessage(null)
            return if (isEpochActive(myEpoch) &&
                currentState() == PttState.SPEAKING_TTS &&
                currentCoroutineContext().isActive
            ) {
                IntentOutcome.COMPLETED
            } else {
                IntentOutcome.ABORTED
            }
        }

        private suspend fun speakExplicitItemsLoop(
            myEpoch: Long,
            serverUrl: String,
            pinnedSession: PinnedSession,
            initialItems: List<AssistantResponseItem>,
            drainTrailingAutoItems: Boolean,
            isManualRead: Boolean,
        ): IntentOutcome {
            val sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession)
            val attemptedIds = HashSet<String>()
            val standaloneFallbackMode = (onSyncForManualReadAloud == null && syncForPromptTurnCompletion == null)

            for (item in initialItems) {
                if (!isEpochActive(myEpoch) ||
                    currentState() != PttState.SPEAKING_TTS ||
                    !currentCoroutineContext().isActive
                ) {
                    setSpeakingMessage(null)
                    return IntentOutcome.ABORTED
                }
                if (!attemptedIds.add(item.messageId)) continue
                if (item.assistantText.isBlank()) {
                    markItemHeard(item)
                    continue
                }
                setSpeakingMessage(item.messageId)
                if (standaloneFallbackMode) {
                    if (currentLastReply() != item.assistantText) {
                        updateLastSpokenSummary(null)
                    }
                    updateLastReply(item.assistantText)
                    syncActiveBubble(null)
                }
                val result = speakTts(
                    TtsSpeakRequest(
                        serverUrl = serverUrl,
                        text = item.assistantText,
                        role = VoiceRole.AGENT,
                        sessionLabel = sessionLabel,
                        kind = TTS_KIND_REPLY,
                        notifySpokenSummary = true,
                        isManualRead = isManualRead,
                        messageId = item.messageId,
                        onAudibleStart = onSpeechAudibleStart,
                    ),
                )
                if (result != TtsPlaybackResult.COMPLETED ||
                    !isEpochActive(myEpoch) ||
                    currentState() != PttState.SPEAKING_TTS ||
                    !currentCoroutineContext().isActive
                ) {
                    setSpeakingMessage(null)
                    return IntentOutcome.ABORTED
                }
                markItemHeard(item)
                setSpeakingMessage(null)
            }

            if (drainTrailingAutoItems) {
                while (currentCoroutineContext().isActive &&
                    isEpochActive(myEpoch) &&
                    currentState() == PttState.SPEAKING_TTS
                ) {
                    val trailing = nextUnreadItem() ?: break
                    if (!attemptedIds.add(trailing.messageId)) break
                    if (trailing.assistantText.isBlank()) {
                        markItemHeard(trailing)
                        continue
                    }
                    setSpeakingMessage(trailing.messageId)
                    val result = speakTts(
                        TtsSpeakRequest(
                            serverUrl = serverUrl,
                            text = trailing.assistantText,
                            role = VoiceRole.AGENT,
                            sessionLabel = sessionLabel,
                            kind = TTS_KIND_REPLY,
                            notifySpokenSummary = true,
                            messageId = trailing.messageId,
                            onAudibleStart = onSpeechAudibleStart,
                        ),
                    )
                    if (result != TtsPlaybackResult.COMPLETED ||
                        !isEpochActive(myEpoch) ||
                        currentState() != PttState.SPEAKING_TTS ||
                        !currentCoroutineContext().isActive
                    ) {
                        setSpeakingMessage(null)
                        return IntentOutcome.ABORTED
                    }
                    markItemHeard(trailing)
                    setSpeakingMessage(null)
                }
            }

            setSpeakingMessage(null)
            return if (isEpochActive(myEpoch) &&
                currentState() == PttState.SPEAKING_TTS &&
                currentCoroutineContext().isActive
            ) {
                IntentOutcome.COMPLETED
            } else {
                IntentOutcome.ABORTED
            }
        }

        private fun isEpochActive(expectedEpoch: Long): Boolean =
            synchronized(lock) { epoch == expectedEpoch }

        private fun isPermissionEpochActive(expectedPermEpoch: Long): Boolean =
            synchronized(lock) { permissionEpoch == expectedPermEpoch }

        private fun intentWaitersOf(intent: SpeechIntent): List<CompletableDeferred<Unit>> = when (intent) {
            is SpeechIntent.DrainAutoReplies -> intent.waiters
            is SpeechIntent.OperatorSpeech -> intent.waiters
            is SpeechIntent.ExplicitItems -> intent.waiters
            is SpeechIntent.PermissionAlert,
            is SpeechIntent.ManualRead,
            is SpeechIntent.DraftPreview,
            -> emptyList()
        }

        private fun collectAndClearAllWaitersLocked(): List<CompletableDeferred<Unit>> = buildList {
            activeIntent?.let { addAll(intentWaitersOf(it)) }
            for (queued in queue) {
                addAll(intentWaitersOf(queued))
            }
            addAll(activeDrainWaiters)
            activeDrainWaiters.clear()
        }
    }

    fun organizeAndSpeakDraft(rawBurst: String) {
        val activeServer = tokenStore.getActiveServer()
        val pinnedSession = activeServer?.lastPinnedSession
        val burst = rawBurst.trim().takeIf { it.isNotEmpty() }
        if (activeServer == null || pinnedSession == null || burst == null) {
            onError("No draft burst available to organize", false)
            return
        }

        val serverId = activeServer.id
        val serverUrl = activeServer.serverUrl
        val sessionKey = TokenStore.sessionKeyFor(serverId, pinnedSession)
        val sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession)

        val token = Any()
        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            setOrganizingDraft(true)
            try {
                val existing = tokenStore.getStagedDraft(sessionKey)
                val result = promptOrganizer.organizeDraft(
                    serverUrl = serverUrl,
                    existingDraft = existing?.organizedText,
                    newBurst = burst,
                    sessionLabel = sessionLabel,
                )
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_ORGANIZE,
                    event = if (existing == null) "organize_new" else "smart_merge",
                    sessionKey = sessionKey,
                    metrics = mapOf(
                        "latencyMs" to result.latencyMs,
                        "timedOut" to result.timedOut,
                        "fallbackUsed" to result.fallbackUsed,
                        "inputChars" to burst.length,
                        "outputChars" to result.text.length,
                    ),
                )

                val saved = StagedDraft(
                    sessionKey = sessionKey,
                    rawBursts = (existing?.rawBursts ?: emptyList()) + burst,
                    organizedText = result.text,
                    previousOrganizedText = existing?.organizedText?.takeIf { it != result.text },
                    updatedAtMs = wallClockMs(),
                )
                tokenStore.saveStagedDraft(saved)

                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_DRAFT,
                    event = if (existing == null) "staged" else "appended",
                    sessionKey = sessionKey,
                    metrics = mapOf("bursts" to saved.rawBursts.size, "chars" to saved.organizedText.length),
                )

                if (activeSessionKey() == sessionKey) {
                    updateStagedDraft(saved)
                    updateLastTranscript(saved.organizedText)
                    syncActiveBubble(null)

                    if (currentState() == PttState.TRANSCRIBING_STAGE) {
                        val tr = onDraftPreviewReady()
                        if (tr.current == PttState.STAGED_DRAFT_SPEAKING) {
                            launchDraftPreviewSpeech(serverUrl, saved)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                handleUnauthorizedIfNeeded(serverId, t)
                if (activeSessionKey() == sessionKey) {
                    onError(
                        t.message?.takeIf { it.isNotBlank() } ?: "Failed to organize draft",
                        PttForegroundService.isUnauthorizedError(t),
                    )
                }
            } finally {
                synchronized(turnLock) {
                    if (activeOrganizeToken === token) {
                        activeOrganizeToken = null
                        if (organizeJob === jobRef) {
                            organizeJob = null
                        }
                        setOrganizingDraft(false)
                    }
                }
            }
        }
        jobRef = job

        val prev: Job?
        synchronized(turnLock) {
            prev = organizeJob
            activeOrganizeToken = token
            organizeJob = job
        }
        prev?.cancel()
        job.start()
    }

    fun reorganizeCurrentDraft() {
        val activeServer = tokenStore.getActiveServer() ?: return
        val pinnedSession = activeServer.lastPinnedSession ?: return
        val key = TokenStore.sessionKeyFor(activeServer.id, pinnedSession)
        val cur = tokenStore.getStagedDraft(key) ?: currentStagedDraft() ?: return
        if (cur.organizedText.isBlank()) return

        val token = Any()
        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            setOrganizingDraft(true)
            try {
                val res = promptOrganizer.reorganizeWholeDraft(
                    serverUrl = activeServer.serverUrl,
                    draftText = cur.organizedText,
                    sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession),
                )
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_ORGANIZE,
                    event = "reorganize_manual",
                    sessionKey = key,
                    metrics = mapOf(
                        "latencyMs" to res.latencyMs,
                        "timedOut" to res.timedOut,
                        "fallbackUsed" to res.fallbackUsed,
                        "inputChars" to cur.organizedText.length,
                        "outputChars" to res.text.length,
                    ),
                )
                val next = cur.copy(
                    organizedText = res.text,
                    previousOrganizedText = cur.organizedText.takeIf { it != res.text } ?: cur.previousOrganizedText,
                    updatedAtMs = wallClockMs(),
                )
                tokenStore.saveStagedDraft(next)
                if (activeSessionKey() == key) {
                    updateStagedDraft(next)
                    updateLastTranscript(next.organizedText)
                    syncActiveBubble(null)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                handleUnauthorizedIfNeeded(activeServer.id, t)
                if (activeSessionKey() == key) {
                    onError(
                        t.message?.takeIf { it.isNotBlank() } ?: "Failed to reorganize draft",
                        PttForegroundService.isUnauthorizedError(t),
                    )
                }
            } finally {
                synchronized(turnLock) {
                    if (activeOrganizeToken === token) {
                        activeOrganizeToken = null
                        if (organizeJob === jobRef) {
                            organizeJob = null
                        }
                        setOrganizingDraft(false)
                    }
                }
            }
        }
        jobRef = job

        val prev: Job?
        synchronized(turnLock) {
            prev = organizeJob
            activeOrganizeToken = token
            organizeJob = job
        }
        prev?.cancel()
        job.start()
    }

    fun cancelOrganize() {
        val orgJob: Job?
        synchronized(turnLock) {
            activeOrganizeToken = null
            orgJob = organizeJob
            organizeJob = null
        }
        setOrganizingDraft(false)
        orgJob?.cancel()
    }

    fun cancelSpeechJobs() {
        speechCoordinator.abortAllSpeech()
    }

    fun cancelActiveTurnAndReadReply(reason: String) {
        val turnJob: Job?
        synchronized(turnLock) {
            turnJob = activeTurnJob
            activeTurnJob = null
        }
        setActivePromptTurn(false)
        speechCoordinator.abortAllSpeech()
        turnJob?.cancel(CancellationException(reason))
    }

    fun cancelAll() {
        val orgJob: Job?
        val turnJob: Job?
        synchronized(turnLock) {
            activeOrganizeToken = null
            orgJob = organizeJob
            organizeJob = null
            turnJob = activeTurnJob
            activeTurnJob = null
        }
        setOrganizingDraft(false)
        setActivePromptTurn(false)
        speechCoordinator.abortAllSpeech()
        orgJob?.cancel()
        turnJob?.cancel(CancellationException("PTT cancelled"))
    }

    private fun handleUnauthorizedIfNeeded(serverId: String, t: Throwable) {
        if (PttForegroundService.isUnauthorizedError(t)) {
            tokenStore.clearAuth(serverId)
        }
    }

    companion object {
        internal const val TTS_KIND_REPLY = "reply"
        internal const val TTS_KIND_DRAFT_PREVIEW = "draft_preview"
        internal const val TTS_KIND_PERMISSION = "permission_alert"
        internal const val TTS_KIND_CONTROL = "control_plane"
        internal const val EVENT_STREAM_READY_TIMEOUT_MS = 5_000L
        internal const val DEFAULT_STILL_WORKING_DELAY_MS = 8_000L
        internal const val FLUSH_LAG_RETRY_1_MS = 250L
        internal const val FLUSH_LAG_RETRY_2_MS = 650L
    }
}
