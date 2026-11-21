package com.openchamber.ptt.service

import android.os.SystemClock
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.TtsInputMode
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class TtsSpeakRequest(
    val serverUrl: String,
    val text: String,
    val voice: String,
    val sessionLabel: String? = null,
    val inputModeOverride: TtsInputMode? = null,
    val kind: String = AgentTurnRunner.TTS_KIND_REPLY,
    val notifySpokenSummary: Boolean = true,
)

internal class AgentTurnRunner(
    private val scope: CoroutineScope,
    private val tokenStore: TokenStore,
    private val sessionClient: SessionClient,
    private val eventStreamClient: EventStreamClient,
    private val promptOrganizer: PromptOrganizer,
    private val voiceControlPlane: VoiceControlPlane,
    private val voiceUxLogger: VoiceUxLogger,
    private val speakTts: suspend (TtsSpeakRequest) -> Unit,
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
    private val ensureAutoSpeakAnchorBeforeTurn: (baselineMessageId: String?) -> Unit = {},
    private val ensureCursorSeededBeforeTurn: (baselineMessageId: String?, baselineCreatedAtMs: Long) -> Unit = { _, _ -> },
    private val onSyncTurnCompleted: (suspend () -> Unit)? = null,
    private val onSyncForManualReadAloud: (suspend () -> Unit)? = null,
    private val hasCachedResponses: () -> Boolean = { !currentLastReply().isNullOrBlank() },
    private val nextAutoSpeakItem: () -> AssistantResponseItem? = { null },
    private val nextUnreadItem: () -> AssistantResponseItem? = { null },
    private val itemsForManualReadAloud: () -> List<AssistantResponseItem> = { emptyList() },
    private val markItemHeard: (AssistantResponseItem) -> Unit = {},
    private val setSpeakingMessage: (String?) -> Unit = {},
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val permissionAlertDelayMs: Long = PttForegroundService.PERMISSION_ALERT_DELAY_MS,
    private val eventStreamReadyTimeoutMs: Long = EVENT_STREAM_READY_TIMEOUT_MS,
) {
    private val turnLock = Any()
    private var activeTurnJob: Job? = null
    private var organizeJob: Job? = null
    private var activeOrganizeToken: Any? = null
    private var draftPreviewJob: Job? = null
    private var playbackJob: Job? = null

    val isPlaybackActive: Boolean
        get() = synchronized(turnLock) { playbackJob?.isActive == true }

    suspend fun finalizeQuickTranscript(
        serverUrl: String,
        sessionKey: String,
        pinnedSession: PinnedSession,
        rawTranscript: String,
    ) {
        val cleanupResult = promptOrganizer.cleanQuickTranscript(
            serverUrl = serverUrl,
            rawTranscript = rawTranscript,
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
                "inputChars" to rawTranscript.length,
                "outputChars" to cleanupResult.text.length,
            ),
        )

        if (currentState() != PttState.TRANSCRIBING_SEND) {
            return
        }

        val finalQuick = cleanupResult.text.ifBlank { rawTranscript }
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
                    )
                    when (decision) {
                        is VoiceControlDecision.LiveTuning -> {
                            decision.detailLevel?.let { tokenStore.saveSpokenDetailLevel(it, customServerId) }
                            decision.inputMode?.let { tokenStore.saveTtsInputMode(it, customServerId) }
                            voiceUxLogger.log(
                                category = VoiceUxLogger.CAT_TURN,
                                event = "live_tuning",
                                sessionKey = sessionKey,
                                metrics = mapOf(
                                    "detailLevel" to decision.detailLevel?.name,
                                    "inputMode" to decision.inputMode?.name,
                                ),
                            )
                            updateStatusText(decision.confirmationSpeech)
                            syncActiveBubble(decision.confirmationSpeech)
                            onAgentCompleted(true)
                            if (currentState() == PttState.SPEAKING_TTS) {
                                speakControlSpeechAndJoin(
                                    serverId = serverId,
                                    serverUrl = serverUrl,
                                    spokenText = decision.confirmationSpeech,
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
                                speakControlSpeechAndJoin(
                                    serverId = serverId,
                                    serverUrl = serverUrl,
                                    spokenText = decision.spokenAnswer,
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

    private suspend fun speakControlSpeechAndJoin(
        serverId: String,
        serverUrl: String,
        spokenText: String,
    ) {
        val pb = launchPlayback {
            try {
                if (currentState() != PttState.SPEAKING_TTS || !currentCoroutineContext().isActive) {
                    return@launchPlayback false
                }
                speakTts(
                    TtsSpeakRequest(
                        serverUrl = serverUrl,
                        text = spokenText,
                        voice = tokenStore.getEffectiveTtsVoice(serverId),
                        inputModeOverride = TtsInputMode.RAW,
                        kind = TTS_KIND_CONTROL,
                        notifySpokenSummary = false,
                    ),
                )
                currentState() == PttState.SPEAKING_TTS && currentCoroutineContext().isActive
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                handleUnauthorizedIfNeeded(serverId, t)
                onError(
                    t.message?.takeIf { it.isNotBlank() } ?: "TTS failed",
                    PttForegroundService.isUnauthorizedError(t),
                )
                false
            }
        }
        try {
            pb.join()
        } catch (ce: CancellationException) {
            pb.cancel(ce)
            throw ce
        }
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

            ensureAutoSpeakAnchorBeforeTurn(baselineSettledId ?: baselineAssistantId)
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
                var permissionTtsJob: Job? = null
                try {
                    withTimeoutOrNull(eventStreamReadyTimeoutMs) {
                        wsReady.await()
                    }
                    while (turnEvents.tryReceive().isSuccess) {
                        // Drain any stale buffered event before arming prompt acceptance.
                    }
                    setActivePromptTurn(true)
                    acceptEvents.set(true)

                    sessionClient.sendPrompt(
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

                    for (turnEvent in turnEvents) {
                        when (turnEvent) {
                            is TurnEvent.PermissionAsked -> {
                                permissionDelayJob?.cancel()
                                permissionDelayJob = launch(ioDispatcher) {
                                    delay(permissionAlertDelayMs)
                                    onPermissionAsked(turnEvent.tool)
                                    onPostPermissionAlertNotification(turnEvent.tool)
                                    permissionTtsJob?.cancel()
                                    permissionTtsJob = launch(ioDispatcher) {
                                        runCatching {
                                            speakTts(
                                                TtsSpeakRequest(
                                                    serverUrl = serverUrl,
                                                    text = PttForegroundService.formatPermissionAlertSpeech(turnEvent.tool),
                                                    voice = tokenStore.getEffectiveTtsVoice(serverId),
                                                    kind = TTS_KIND_PERMISSION,
                                                    notifySpokenSummary = false,
                                                ),
                                            )
                                        }.onFailure { err ->
                                            if (err is CancellationException) throw err
                                        }
                                    }
                                }
                            }

                            TurnEvent.PermissionResolved -> {
                                permissionDelayJob?.cancel()
                                permissionDelayJob = null
                                permissionTtsJob?.cancel()
                                permissionTtsJob = null
                                onCancelPermissionAlertNotification()
                                if (currentState() == PttState.PERMISSION_BLOCKED) {
                                    onPermissionResolved()
                                }
                            }

                            is TurnEvent.SyntheticArrived -> Unit

                            TurnEvent.Completed -> {
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
                                onCancelPermissionAlertNotification()

                                val itemsToSpeak: List<AssistantResponseItem>
                                if (onSyncTurnCompleted != null) {
                                    onSyncTurnCompleted.invoke()
                                    itemsToSpeak = listOfNotNull(nextAutoSpeakItem())
                                } else {
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
                                    itemsToSpeak = afterWindow.turns
                                        .asSequence()
                                        .flatMap { it.responses.asSequence() }
                                        .filter { it.isCompleted && it.assistantText.isNotBlank() }
                                        .filter { newerIds == null || it.messageId in newerIds }
                                        .toList()
                                }

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
                                    if (onSyncTurnCompleted == null) {
                                        updateLastSpokenSummary(null)
                                        updateLastReply(itemsToSpeak.last().assistantText)
                                        syncActiveBubble(null)
                                    }
                                    onAgentCompleted(true)
                                    setActivePromptTurn(false)
                                    if (currentState() == PttState.SPEAKING_TTS) {
                                        val pb = launchPlayback {
                                            try {
                                                if (onSyncTurnCompleted != null) {
                                                    speakQueuedAutoItemsLoop(
                                                        serverId = serverId,
                                                        serverUrl = serverUrl,
                                                        pinnedSession = pinnedSession,
                                                    )
                                                } else {
                                                    speakExplicitItemsLoop(
                                                        serverId = serverId,
                                                        serverUrl = serverUrl,
                                                        pinnedSession = pinnedSession,
                                                        initialItems = itemsToSpeak,
                                                        drainTrailingAutoItems = false,
                                                    )
                                                }
                                            } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (t: Throwable) {
                                                setSpeakingMessage(null)
                                                handleUnauthorizedIfNeeded(serverId, t)
                                                onError(
                                                    t.message?.takeIf { it.isNotBlank() } ?: "TTS playback failed",
                                                    PttForegroundService.isUnauthorizedError(t),
                                                )
                                                false
                                            }
                                        }
                                        try {
                                            pb.join()
                                        } catch (ce: CancellationException) {
                                            pb.cancel(ce)
                                            throw ce
                                        }
                                    }
                                } else {
                                    onAgentCompleted(false)
                                    setActivePromptTurn(false)
                                }
                                break
                            }

                            is TurnEvent.Failed -> {
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
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
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
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
                    permissionDelayJob?.cancel()
                    permissionTtsJob?.cancel()
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
        val serverId = activeServer.id
        val serverUrl = activeServer.serverUrl

        launchPlayback {
            try {
                speakQueuedAutoItemsLoop(
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                setSpeakingMessage(null)
                handleUnauthorizedIfNeeded(serverId, t)
                onError(
                    t.message?.takeIf { it.isNotBlank() } ?: "TTS playback failed",
                    PttForegroundService.isUnauthorizedError(t),
                )
                false
            }
        }
    }

    fun readLastReply() {
        val activeServer = tokenStore.getActiveServer()
        val pinnedSession = activeServer?.lastPinnedSession
        if (activeServer == null || pinnedSession == null) {
            onError("Server connection or pinned session is missing", false)
            return
        }
        val serverId = activeServer.id
        val serverUrl = activeServer.serverUrl

        launchPlayback {
            try {
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
                    if (refreshError != null) {
                        if (PttForegroundService.isUnauthorizedError(refreshError)) {
                            handleUnauthorizedIfNeeded(serverId, refreshError)
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Session expired",
                                true,
                            )
                            return@launchPlayback false
                        }
                        if (!hasCachedResponses()) {
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                                false,
                            )
                            return@launchPlayback false
                        }
                    }
                    initialItems = itemsForManualReadAloud()
                } else {
                    val refreshResult = runCatching {
                        sessionClient.fetchTurnsWindow(
                            serverUrl = serverUrl,
                            sessionId = pinnedSession.sessionId,
                            directory = pinnedSession.directory,
                            maxPages = 2,
                            pageLimit = SessionClient.DEFAULT_PAGE_LIMIT,
                        )
                    }
                    val refreshError = refreshResult.exceptionOrNull()
                    if (refreshError != null) {
                        if (refreshError is CancellationException) throw refreshError
                        if (PttForegroundService.isUnauthorizedError(refreshError)) {
                            handleUnauthorizedIfNeeded(serverId, refreshError)
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Session expired",
                                true,
                            )
                            return@launchPlayback false
                        }
                        val cached = currentLastReply()?.takeIf { it.isNotBlank() }
                        if (cached == null) {
                            onError(
                                refreshError.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                                false,
                            )
                            return@launchPlayback false
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

                if (initialItems.isEmpty()) {
                    updateStatusText("No previous assistant reply in this session")
                    return@launchPlayback currentState() == PttState.SPEAKING_TTS && currentCoroutineContext().isActive
                }

                speakExplicitItemsLoop(
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                    initialItems = initialItems,
                    drainTrailingAutoItems = (onSyncForManualReadAloud != null),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                setSpeakingMessage(null)
                handleUnauthorizedIfNeeded(serverId, t)
                onError(
                    t.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                    PttForegroundService.isUnauthorizedError(t),
                )
                false
            }
        }
    }

    private suspend fun speakQueuedAutoItemsLoop(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
    ): Boolean {
        val sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession)
        val attemptedIds = HashSet<String>()
        while (currentCoroutineContext().isActive && currentState() == PttState.SPEAKING_TTS) {
            val nextItem = nextAutoSpeakItem() ?: break
            if (!attemptedIds.add(nextItem.messageId)) break
            if (nextItem.assistantText.isBlank()) {
                markItemHeard(nextItem)
                continue
            }
            setSpeakingMessage(nextItem.messageId)
            speakTts(
                TtsSpeakRequest(
                    serverUrl = serverUrl,
                    text = nextItem.assistantText,
                    voice = tokenStore.getEffectiveTtsVoice(serverId),
                    sessionLabel = sessionLabel,
                    kind = TTS_KIND_REPLY,
                    notifySpokenSummary = true,
                ),
            )
            if (currentState() != PttState.SPEAKING_TTS || !currentCoroutineContext().isActive) {
                return false
            }
            markItemHeard(nextItem)
            setSpeakingMessage(null)
        }
        setSpeakingMessage(null)
        return currentState() == PttState.SPEAKING_TTS && currentCoroutineContext().isActive
    }

    private suspend fun speakExplicitItemsLoop(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        initialItems: List<AssistantResponseItem>,
        drainTrailingAutoItems: Boolean,
    ): Boolean {
        val sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession)
        val attemptedIds = HashSet<String>()
        val standaloneFallbackMode = (onSyncForManualReadAloud == null && onSyncTurnCompleted == null)

        for (item in initialItems) {
            if (currentState() != PttState.SPEAKING_TTS || !currentCoroutineContext().isActive) {
                return false
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
            speakTts(
                TtsSpeakRequest(
                    serverUrl = serverUrl,
                    text = item.assistantText,
                    voice = tokenStore.getEffectiveTtsVoice(serverId),
                    sessionLabel = sessionLabel,
                    kind = TTS_KIND_REPLY,
                    notifySpokenSummary = true,
                ),
            )
            if (currentState() != PttState.SPEAKING_TTS || !currentCoroutineContext().isActive) {
                return false
            }
            markItemHeard(item)
            setSpeakingMessage(null)
        }

        if (drainTrailingAutoItems) {
            while (currentCoroutineContext().isActive && currentState() == PttState.SPEAKING_TTS) {
                val trailing = nextUnreadItem() ?: break
                if (!attemptedIds.add(trailing.messageId)) break
                if (trailing.assistantText.isBlank()) {
                    markItemHeard(trailing)
                    continue
                }
                setSpeakingMessage(trailing.messageId)
                speakTts(
                    TtsSpeakRequest(
                        serverUrl = serverUrl,
                        text = trailing.assistantText,
                        voice = tokenStore.getEffectiveTtsVoice(serverId),
                        sessionLabel = sessionLabel,
                        kind = TTS_KIND_REPLY,
                        notifySpokenSummary = true,
                    ),
                )
                if (currentState() != PttState.SPEAKING_TTS || !currentCoroutineContext().isActive) {
                    return false
                }
                markItemHeard(trailing)
                setSpeakingMessage(null)
            }
        }

        setSpeakingMessage(null)
        return currentState() == PttState.SPEAKING_TTS && currentCoroutineContext().isActive
    }

    private fun launchPlayback(block: suspend () -> Boolean): Job {
        var jobRef: Job? = null
        var prevJob: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            var notifyFinished = false
            try {
                prevJob?.cancelAndJoin()
                notifyFinished = block()
            } finally {
                synchronized(turnLock) {
                    if (playbackJob === jobRef) {
                        playbackJob = null
                    }
                }
            }
            if (notifyFinished && currentState() == PttState.SPEAKING_TTS && currentCoroutineContext().isActive) {
                onTtsFinished()
            }
        }
        jobRef = job
        synchronized(turnLock) {
            prevJob = playbackJob
            playbackJob = job
        }
        job.start()
        return job
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

    fun launchDraftPreviewSpeech(serverUrl: String, draft: StagedDraft) {
        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                if (currentState() == PttState.STAGED_DRAFT_SPEAKING) {
                    speakTts(
                        TtsSpeakRequest(
                            serverUrl = serverUrl,
                            text = draft.organizedText,
                            voice = tokenStore.getEffectiveTtsVoice(),
                            inputModeOverride = TtsInputMode.RAW,
                            kind = TTS_KIND_DRAFT_PREVIEW,
                            notifySpokenSummary = false,
                        ),
                    )
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_TTS,
                        event = "completed",
                        sessionKey = draft.sessionKey,
                        metrics = mapOf("kind" to TTS_KIND_DRAFT_PREVIEW, "chars" to draft.organizedText.length),
                    )
                    if (currentState() == PttState.STAGED_DRAFT_SPEAKING) {
                        onTtsFinished()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val activeId = tokenStore.getActiveServer()?.id
                if (activeId != null) {
                    handleUnauthorizedIfNeeded(activeId, t)
                }
                if (currentState() == PttState.STAGED_DRAFT_SPEAKING) {
                    onTtsFinished()
                }
            } finally {
                synchronized(turnLock) {
                    if (draftPreviewJob === jobRef) {
                        draftPreviewJob = null
                    }
                }
            }
        }
        jobRef = job

        val prev: Job?
        synchronized(turnLock) {
            prev = draftPreviewJob
            draftPreviewJob = job
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
        val pbJob: Job?
        val previewJob: Job?
        synchronized(turnLock) {
            pbJob = playbackJob
            playbackJob = null
            previewJob = draftPreviewJob
            draftPreviewJob = null
        }
        setSpeakingMessage(null)
        pbJob?.cancel()
        previewJob?.cancel()
    }

    fun cancelActiveTurnAndReadReply(reason: String) {
        val pbJob: Job?
        val turnJob: Job?
        synchronized(turnLock) {
            pbJob = playbackJob
            playbackJob = null
            turnJob = activeTurnJob
            activeTurnJob = null
        }
        setActivePromptTurn(false)
        setSpeakingMessage(null)
        pbJob?.cancel()
        turnJob?.cancel(CancellationException(reason))
    }

    fun cancelAll() {
        val orgJob: Job?
        val previewJob: Job?
        val pbJob: Job?
        val turnJob: Job?
        synchronized(turnLock) {
            activeOrganizeToken = null
            orgJob = organizeJob
            organizeJob = null
            previewJob = draftPreviewJob
            draftPreviewJob = null
            pbJob = playbackJob
            playbackJob = null
            turnJob = activeTurnJob
            activeTurnJob = null
        }
        setOrganizingDraft(false)
        setActivePromptTurn(false)
        setSpeakingMessage(null)
        orgJob?.cancel()
        previewJob?.cancel()
        pbJob?.cancel()
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
    }
}
