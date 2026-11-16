package com.openchamber.ptt.service

import android.os.SystemClock
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.PromptOrganizer
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.TurnEvent
import com.openchamber.ptt.voice.VoiceControlDecision
import com.openchamber.ptt.voice.VoiceControlPlane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private val currentLastReply: () -> String?,
    private val updateLastReply: (String?) -> Unit,
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
    private var readReplyJob: Job? = null

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
                    val decision = voiceControlPlane.evaluateTurn(
                        serverUrl = serverUrl,
                        utterance = promptText,
                        lastReply = currentLastReply(),
                        currentDetailLevel = tokenStore.getEffectiveSpokenDetailLevel(serverId),
                        currentInputMode = tokenStore.getEffectiveTtsInputMode(serverId),
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
                                try {
                                    speakTts(
                                        TtsSpeakRequest(
                                            serverUrl = serverUrl,
                                            text = decision.confirmationSpeech,
                                            voice = tokenStore.getEffectiveTtsVoice(),
                                            inputModeOverride = TtsInputMode.RAW,
                                            kind = TTS_KIND_CONTROL,
                                            notifySpokenSummary = false,
                                        ),
                                    )
                                    onTtsFinished()
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    handleUnauthorizedIfNeeded(serverId, t)
                                    onError(
                                        t.message?.takeIf { it.isNotBlank() } ?: "TTS failed",
                                        PttForegroundService.isUnauthorizedError(t),
                                    )
                                }
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
                            updateLastSpokenSummary(decision.spokenAnswer)
                            updateStatusText(decision.spokenAnswer)
                            syncActiveBubble(decision.spokenAnswer)
                            onAgentCompleted(true)
                            if (currentState() == PttState.SPEAKING_TTS) {
                                try {
                                    speakTts(
                                        TtsSpeakRequest(
                                            serverUrl = serverUrl,
                                            text = decision.spokenAnswer,
                                            voice = tokenStore.getEffectiveTtsVoice(),
                                            inputModeOverride = TtsInputMode.RAW,
                                            kind = TTS_KIND_CONTROL,
                                            notifySpokenSummary = false,
                                        ),
                                    )
                                    onTtsFinished()
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    handleUnauthorizedIfNeeded(serverId, t)
                                    onError(
                                        t.message?.takeIf { it.isNotBlank() } ?: "TTS failed",
                                        PttForegroundService.isUnauthorizedError(t),
                                    )
                                }
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

    internal suspend fun runAgentTurn(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        promptText: String,
        fromStagedDraft: Boolean,
    ) {
        val turnStartedMs = clockElapsedMs()
        val sessionKey = TokenStore.sessionKeyFor(serverId, pinnedSession)
        try {
            val baselineAssistantId = sessionClient.getLatestAssistantMessageId(
                serverUrl = serverUrl,
                sessionId = pinnedSession.sessionId,
                directory = pinnedSession.directory,
            )

            coroutineScope {
                val wsReady = CompletableDeferred<Unit>()
                val turnEvents = Channel<TurnEvent>(Channel.UNLIMITED)
                val eventCollectorJob = launch(ioDispatcher) {
                    try {
                        eventStreamClient.observeTurnEvents(
                            serverUrl = serverUrl,
                            targetSessionId = pinnedSession.sessionId,
                            onReady = { wsReady.complete(Unit) },
                        ).collect { event ->
                            turnEvents.trySend(event)
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
                                                    voice = tokenStore.getEffectiveTtsVoice(),
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

                            TurnEvent.Completed -> {
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
                                onCancelPermissionAlertNotification()

                                val replyText = sessionClient.fetchNewAssistantText(
                                    serverUrl = serverUrl,
                                    sessionId = pinnedSession.sessionId,
                                    directory = pinnedSession.directory,
                                    afterMessageId = baselineAssistantId,
                                )

                                voiceUxLogger.log(
                                    category = VoiceUxLogger.CAT_TURN,
                                    event = "completed",
                                    sessionKey = sessionKey,
                                    metrics = mapOf(
                                        "turnMs" to (clockElapsedMs() - turnStartedMs),
                                        "hasReply" to (!replyText.isNullOrBlank()),
                                        "fromDraft" to fromStagedDraft,
                                    ),
                                )

                                if (!replyText.isNullOrBlank()) {
                                    updateLastSpokenSummary(null)
                                    updateLastReply(replyText)
                                    syncActiveBubble(null)
                                    onAgentCompleted(true)
                                    if (currentState() == PttState.SPEAKING_TTS) {
                                        speakTts(
                                            TtsSpeakRequest(
                                                serverUrl = serverUrl,
                                                text = replyText,
                                                voice = tokenStore.getEffectiveTtsVoice(),
                                                sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession),
                                                kind = TTS_KIND_REPLY,
                                                notifySpokenSummary = true,
                                            ),
                                        )
                                        onTtsFinished()
                                    }
                                } else {
                                    onAgentCompleted(false)
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

    fun readLastReply() {
        val activeServer = tokenStore.getActiveServer() ?: return
        val serverId = activeServer.id
        val serverUrl = activeServer.serverUrl
        val pinnedSession = activeServer.lastPinnedSession ?: return

        var jobRef: Job? = null
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                val replyText = currentLastReply()?.takeIf { it.isNotBlank() }
                    ?: sessionClient.fetchLatestTurn(
                        serverUrl = serverUrl,
                        sessionId = pinnedSession.sessionId,
                        directory = pinnedSession.directory,
                    ).assistantText
                if (replyText.isNullOrBlank()) {
                    updateStatusText("No previous assistant reply in this session")
                    onTtsFinished()
                    return@launch
                }
                if (currentLastReply() != replyText) {
                    updateLastSpokenSummary(null)
                }
                updateLastReply(replyText)
                syncActiveBubble(null)
                if (currentState() == PttState.SPEAKING_TTS) {
                    speakTts(
                        TtsSpeakRequest(
                            serverUrl = serverUrl,
                            text = replyText,
                            voice = tokenStore.getEffectiveTtsVoice(),
                            sessionLabel = PttForegroundService.formatSpeechSessionLabel(pinnedSession),
                            kind = TTS_KIND_REPLY,
                            notifySpokenSummary = true,
                        ),
                    )
                    onTtsFinished()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                handleUnauthorizedIfNeeded(serverId, t)
                onError(
                    t.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message",
                    PttForegroundService.isUnauthorizedError(t),
                )
            } finally {
                synchronized(turnLock) {
                    if (readReplyJob === jobRef) {
                        readReplyJob = null
                    }
                }
            }
        }
        jobRef = job

        val prev: Job?
        synchronized(turnLock) {
            prev = readReplyJob
            readReplyJob = job
        }
        prev?.cancel()
        job.start()
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
        val readJob: Job?
        val previewJob: Job?
        synchronized(turnLock) {
            readJob = readReplyJob
            readReplyJob = null
            previewJob = draftPreviewJob
            draftPreviewJob = null
        }
        readJob?.cancel()
        previewJob?.cancel()
    }

    fun cancelActiveTurnAndReadReply(reason: String) {
        val readJob: Job?
        val turnJob: Job?
        synchronized(turnLock) {
            readJob = readReplyJob
            readReplyJob = null
            turnJob = activeTurnJob
            activeTurnJob = null
        }
        readJob?.cancel()
        turnJob?.cancel(CancellationException(reason))
    }

    fun cancelAll() {
        val orgJob: Job?
        val previewJob: Job?
        val readJob: Job?
        val turnJob: Job?
        synchronized(turnLock) {
            activeOrganizeToken = null
            orgJob = organizeJob
            organizeJob = null
            previewJob = draftPreviewJob
            draftPreviewJob = null
            readJob = readReplyJob
            readReplyJob = null
            turnJob = activeTurnJob
            activeTurnJob = null
        }
        setOrganizingDraft(false)
        orgJob?.cancel()
        previewJob?.cancel()
        readJob?.cancel()
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
