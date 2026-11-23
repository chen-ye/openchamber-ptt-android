package com.openchamber.ptt.service

import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.SessionReplyCursor
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.voice.AssistantResponseItem
import com.openchamber.ptt.voice.DirectVoiceAnswer
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.ParsedSessionRecord
import com.openchamber.ptt.voice.ResponseFeedState
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.SessionTurnProjection
import com.openchamber.ptt.voice.SpokenSummaryState
import com.openchamber.ptt.voice.TurnEvent
import com.openchamber.ptt.voice.TurnTriggerKind
import com.openchamber.ptt.voice.TurnsWindow
import com.openchamber.ptt.voice.UnreadReplyPreview
import com.openchamber.ptt.voice.VoiceReplyContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class BoundServerSession(
    val serverId: String,
    val serverUrl: String,
    val pinnedSession: PinnedSession,
    val sessionKey: String = TokenStore.sessionKeyFor(serverId, pinnedSession),
)

internal class SessionResponseController(
    private val scope: CoroutineScope,
    private val tokenStore: TokenStore,
    private val sessionClient: SessionClient,
    private val eventStreamClient: EventStreamClient,
    private val currentState: () -> PttState,
    private val isPlaybackActive: () -> Boolean = { false },
    private val onTriggerAutoSpeak: () -> Unit = {},
    private val onFeedUpdated: (ResponseFeedState) -> Unit = {},
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val lookupCachedSpokenSummary: ((String) -> String?)? = null,
) {
    private val stateLock = Any()
    private val syncMutex = Mutex()
    private val _feedState = MutableStateFlow(ResponseFeedState())
    val feedState: StateFlow<ResponseFeedState> = _feedState.asStateFlow()

    private val activeTurnEvents = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 64)
    private val windowCache = LinkedHashMap<String, TurnsWindow>(16, 0.75f, true)
    private val seenCompletedIds = HashSet<String>()

    private var boundSession: BoundServerSession? = null
    private var sessionGeneration: Long = 0L
    private var armed: Boolean = false
    private var activePromptTurn: Boolean = false

    private var autoSpeakAnchorId: String? = null
    private var autoSpeakAnchorCreatedAtMs: Long = 0L
    private var needsArmedAnchorCapture: Boolean = false
    private var lastInterruptedMessageId: String? = null
    private var userNavigatedFocus: Boolean = false
    private var initialSyncDoneForSession: Boolean = false

    private var armedStreamJob: Job? = null
    private var armedStreamReady: CompletableDeferred<Unit>? = null
    private var streamServerUrl: String? = null
    private var streamSessionId: String? = null

    private var activeSyncLoopToken: Any? = null
    private var resyncRequested: Boolean = false
    private var pendingTriggerAutoSpeak: Boolean = false
    private var pendingCaptureAutoSpeakAnchor: Boolean = false
    private var pendingForceFocusLatest: Boolean = false
    private var pendingReason: String? = null
    private val pendingWaiters = mutableListOf<CompletableDeferred<Result<ResponseFeedState>>>()

    fun bindSession(session: BoundServerSession?, forceClear: Boolean = true) {
        var oldStreamJob: Job? = null
        var oldReadySignal: CompletableDeferred<Unit>? = null
        var newStreamJob: Job? = null
        var oldWaiters: List<CompletableDeferred<Result<ResponseFeedState>>> = emptyList()
        var notifyFeed: ResponseFeedState? = null

        synchronized(stateLock) {
            if (session == null) {
                if (!forceClear) return
                val hadState = boundSession != null || _feedState.value != ResponseFeedState()
                boundSession = null
                sessionGeneration += 1L
                clearSessionScopedStateLocked()
                val cancelled = detachArmedStreamLocked()
                oldStreamJob = cancelled.first
                oldReadySignal = cancelled.second
                oldWaiters = clearPendingSyncOnSessionSwitchLocked()
                val empty = ResponseFeedState()
                _feedState.value = empty
                if (hadState) {
                    notifyFeed = empty
                }
            } else {
                val prev = boundSession
                if (prev?.sessionKey == session.sessionKey) {
                    boundSession = session
                    if (armed &&
                        (streamServerUrl != session.serverUrl ||
                            streamSessionId != session.pinnedSession.sessionId ||
                            armedStreamJob?.isActive != true)
                    ) {
                        val cancelled = detachArmedStreamLocked()
                        oldStreamJob = cancelled.first
                        oldReadySignal = cancelled.second
                        newStreamJob = prepareArmedStreamLocked(session, sessionGeneration)
                    }
                } else {
                    boundSession = session
                    sessionGeneration += 1L
                    clearSessionScopedStateLocked()
                    val cancelled = detachArmedStreamLocked()
                    oldStreamJob = cancelled.first
                    oldReadySignal = cancelled.second
                    oldWaiters = clearPendingSyncOnSessionSwitchLocked()

                    val cached = if (!forceClear) windowCache[session.sessionKey] else null
                    val nextFeed = if (cached != null) {
                        val cursor = tokenStore.getReplyCursor(session.sessionKey)
                        val projected = SessionTurnProjection.projectResponseFeed(
                            chronologicalRecords = cached.records,
                            turns = cached.turns,
                            cursor = cursor,
                            previousState = ResponseFeedState(),
                            forceFocusLatest = false,
                        )
                        seenCompletedIds.addAll(
                            cached.turns.asSequence()
                                .flatMap { it.responses.asSequence() }
                                .filter { it.isCompleted && it.assistantText.isNotBlank() }
                                .map { it.messageId },
                        )
                        initialSyncDoneForSession = true
                        hydrateFocusedSpokenSummaryLocked(projected)
                    } else {
                        ResponseFeedState()
                    }
                    _feedState.value = nextFeed
                    notifyFeed = nextFeed

                    if (armed) {
                        if (cached != null && autoSpeakAnchorId == null) {
                            setAutoSpeakAnchorFromWindowLocked(cached)
                        } else if (autoSpeakAnchorId == null) {
                            needsArmedAnchorCapture = true
                        }
                        newStreamJob = prepareArmedStreamLocked(session, sessionGeneration)
                    }
                }
            }
        }

        oldStreamJob?.cancel()
        oldReadySignal?.cancel()
        newStreamJob?.start()
        val switchedFeed = notifyFeed ?: _feedState.value
        oldWaiters.forEach { it.complete(Result.success(switchedFeed)) }
        notifyFeed?.let(onFeedUpdated)
    }

    fun setArmed(armed: Boolean) {
        var oldStreamJob: Job? = null
        var oldReadySignal: CompletableDeferred<Unit>? = null
        var newStreamJob: Job? = null
        var notifyFeed: ResponseFeedState? = null

        synchronized(stateLock) {
            this.armed = armed
            if (!armed) {
                activePromptTurn = false
                autoSpeakAnchorId = null
                autoSpeakAnchorCreatedAtMs = 0L
                needsArmedAnchorCapture = false
                val cancelled = detachArmedStreamLocked()
                oldStreamJob = cancelled.first
                oldReadySignal = cancelled.second
                val cur = _feedState.value
                if (cur.autoSpeakPaused) {
                    val updated = cur.copy(autoSpeakPaused = false)
                    _feedState.value = updated
                    notifyFeed = updated
                }
            } else {
                val session = boundSession
                if (session != null) {
                    if (autoSpeakAnchorId == null) {
                        val cached = windowCache[session.sessionKey]
                        if (cached != null) {
                            setAutoSpeakAnchorFromWindowLocked(cached)
                        } else {
                            needsArmedAnchorCapture = true
                        }
                    }
                    val alreadyStreamingSameTarget = armedStreamJob?.isActive == true &&
                        streamServerUrl == session.serverUrl &&
                        streamSessionId == session.pinnedSession.sessionId
                    if (!alreadyStreamingSameTarget) {
                        val cancelled = detachArmedStreamLocked()
                        oldStreamJob = cancelled.first
                        oldReadySignal = cancelled.second
                        newStreamJob = prepareArmedStreamLocked(session, sessionGeneration)
                    }
                }
            }
        }

        oldStreamJob?.cancel()
        oldReadySignal?.cancel()
        newStreamJob?.start()
        notifyFeed?.let(onFeedUpdated)
    }

    fun setActivePromptTurn(active: Boolean) {
        synchronized(stateLock) {
            activePromptTurn = active
        }
    }

    fun observeEventsForActiveTurn(
        serverUrl: String,
        sessionId: String,
        onReady: () -> Unit,
    ): Flow<TurnEvent> {
        val readySignal = synchronized(stateLock) {
            if (armed &&
                armedStreamJob?.isActive == true &&
                streamServerUrl == serverUrl &&
                streamSessionId == sessionId
            ) {
                armedStreamReady
            } else {
                null
            }
        }
        if (readySignal == null) {
            return eventStreamClient.observeTurnEvents(
                serverUrl = serverUrl,
                targetSessionId = sessionId,
                onReady = onReady,
            )
        }
        return channelFlow {
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                activeTurnEvents.collect { event ->
                    send(event)
                }
            }
            if (readySignal.isCompleted) {
                if (!readySignal.isCancelled) {
                    onReady()
                }
            } else {
                try {
                    readySignal.await()
                    onReady()
                } catch (ce: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw ce
                }
            }
            collector.join()
        }
    }

    fun ensureAutoSpeakAnchorBeforeTurn(baselineMessageId: String?) {
        synchronized(stateLock) {
            if (autoSpeakAnchorId != null) {
                needsArmedAnchorCapture = false
                return
            }
            val cleaned = baselineMessageId?.trim()?.takeIf { it.isNotEmpty() }
            if (cleaned == null || cleaned == SessionTurnProjection.SESSION_START_BASELINE) {
                autoSpeakAnchorId = SessionTurnProjection.SESSION_START_BASELINE
                autoSpeakAnchorCreatedAtMs = 0L
            } else {
                autoSpeakAnchorId = cleaned
                val sessionKey = boundSession?.sessionKey
                autoSpeakAnchorCreatedAtMs = sessionKey
                    ?.let { windowCache[it]?.records?.firstOrNull { rec -> rec.id == cleaned }?.createdAtMs }
                    ?: 0L
            }
            needsArmedAnchorCapture = false
        }
    }

    fun ensureCursorSeededBeforeTurn(
        baselineMessageId: String?,
        baselineCreatedAtMs: Long = 0L,
    ) {
        synchronized(stateLock) {
            val session = boundSession ?: return
            if (tokenStore.getReplyCursor(session.sessionKey) != null) return
            val cleaned = baselineMessageId?.trim()?.takeIf { it.isNotEmpty() }
                ?: SessionTurnProjection.SESSION_START_BASELINE
            val createdAt = if (cleaned == SessionTurnProjection.SESSION_START_BASELINE) {
                0L
            } else {
                baselineCreatedAtMs
            }
            tokenStore.saveReplyCursor(
                session.sessionKey,
                SessionReplyCursor(
                    armedBaselineMessageId = cleaned,
                    armedBaselineCreatedAtMs = createdAt,
                    updatedAtMs = wallClockMs(),
                ),
            )
        }
    }

    fun requestBackgroundSync(
        reason: String,
        triggerAutoSpeak: Boolean = false,
        captureAutoSpeakAnchor: Boolean = false,
        forceFocusLatest: Boolean = false,
    ) {
        val loopTokenToStart: Any?
        synchronized(stateLock) {
            if (boundSession == null) return
            enqueueSyncRequestLocked(
                reason = reason,
                triggerAutoSpeak = triggerAutoSpeak,
                captureAutoSpeakAnchor = captureAutoSpeakAnchor,
                forceFocusLatest = forceFocusLatest,
                waiter = null,
            )
            loopTokenToStart = if (activeSyncLoopToken == null) {
                Any().also { activeSyncLoopToken = it }
            } else {
                null
            }
        }
        if (loopTokenToStart != null) {
            launchSyncLoop(loopTokenToStart, reason)
        }
    }

    suspend fun syncSessionTurns(
        reason: String,
        triggerAutoSpeak: Boolean = false,
        captureAutoSpeakAnchor: Boolean = false,
        forceFocusLatest: Boolean = false,
        throwOnError: Boolean = false,
    ): ResponseFeedState {
        val waiter = CompletableDeferred<Result<ResponseFeedState>>()
        val loopTokenToStart: Any?
        synchronized(stateLock) {
            if (boundSession == null) {
                return _feedState.value
            }
            enqueueSyncRequestLocked(
                reason = reason,
                triggerAutoSpeak = triggerAutoSpeak,
                captureAutoSpeakAnchor = captureAutoSpeakAnchor,
                forceFocusLatest = forceFocusLatest,
                waiter = waiter,
            )
            loopTokenToStart = if (activeSyncLoopToken == null) {
                Any().also { activeSyncLoopToken = it }
            } else {
                null
            }
        }
        if (loopTokenToStart != null) {
            launchSyncLoop(loopTokenToStart, reason)
        }
        val result = waiter.await()
        return result.getOrElse { err ->
            if (err is CancellationException) throw err
            if (throwOnError) throw err
            _feedState.value
        }
    }

    fun hasCachedResponses(): Boolean = synchronized(stateLock) {
        if (_feedState.value.allResponses.isNotEmpty()) return true
        val key = boundSession?.sessionKey ?: return false
        windowCache[key]?.turns?.any { it.responses.isNotEmpty() } == true
    }

    fun nextItemToAutoSpeak(): AssistantResponseItem? = synchronized(stateLock) {
        val session = boundSession ?: return null
        val feed = _feedState.value
        nextItemToAutoSpeakLocked(feed, windowCache[session.sessionKey])
    }

    fun nextUnreadItem(): AssistantResponseItem? = synchronized(stateLock) {
        _feedState.value.unreadQueue.firstOrNull()
    }

    fun itemsForManualReadAloud(): List<AssistantResponseItem> {
        var notifyFeed: ResponseFeedState? = null
        val items: List<AssistantResponseItem>
        synchronized(stateLock) {
            val restored = restoreCachedFeedIfEmptyLocked()
            var feed = _feedState.value
            if (feed.autoSpeakPaused) {
                feed = feed.copy(autoSpeakPaused = false)
                _feedState.value = feed
                notifyFeed = feed
            } else if (restored) {
                notifyFeed = feed
            }
            items = if (feed.unreadQueue.isNotEmpty()) {
                feed.unreadQueue
            } else {
                feed.activeTurn?.responses.orEmpty()
            }
        }
        notifyFeed?.let(onFeedUpdated)
        return items
    }

    fun markItemHeard(item: AssistantResponseItem) {
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val session = boundSession ?: return
            val clearInterrupted = lastInterruptedMessageId != null && item.messageId != lastInterruptedMessageId
            if (clearInterrupted) {
                lastInterruptedMessageId = null
            }
            val changed = markItemHeardLocked(session, item)
            if (changed) {
                val reprojected = reprojectCurrentFeedLocked(forceFocusLatest = false)
                _feedState.value = reprojected
                notifyFeed = reprojected
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun markAllResponsesRead() {
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val session = boundSession ?: return
            val cur = _feedState.value
            lastInterruptedMessageId = null
            val latestCompleted = cur.allResponses.lastOrNull { it.isCompleted }
                ?: cur.inspectableResponses.lastOrNull { it.isCompleted }
            if (latestCompleted != null) {
                markItemHeardLocked(session, latestCompleted)
            }
            val baseReprojected = reprojectCurrentFeedLocked(forceFocusLatest = false)
            val finalFeed = if (baseReprojected.autoSpeakPaused) {
                baseReprojected.copy(autoSpeakPaused = false)
            } else {
                baseReprojected
            }
            if (finalFeed != cur) {
                _feedState.value = finalFeed
                notifyFeed = finalFeed
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun onUserBargeIn() {
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val session = boundSession
            val cur = _feedState.value
            val speakingId = cur.speakingMessageId
            if (speakingId != null) {
                lastInterruptedMessageId = speakingId
                val item = cur.allResponses.firstOrNull { it.messageId == speakingId }
                    ?: cur.unreadQueue.firstOrNull { it.messageId == speakingId }
                if (session != null && item != null) {
                    markItemHeardLocked(session, item)
                }
                val reprojected = reprojectCurrentFeedLocked(forceFocusLatest = false)
                val updated = reprojected.copy(
                    focusedMessageId = speakingId,
                    speakingMessageId = null,
                    autoSpeakPaused = true,
                )
                _feedState.value = updated
                notifyFeed = updated
            } else if (!cur.autoSpeakPaused) {
                val updated = cur.copy(
                    speakingMessageId = null,
                    autoSpeakPaused = true,
                )
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun setSpeakingMessage(messageId: String?) {
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val cur = _feedState.value
            if (messageId != null && messageId != lastInterruptedMessageId) {
                lastInterruptedMessageId = null
            }
            val nextFocusedId = if (messageId != null &&
                cur.inspectableResponses.any { it.messageId == messageId }
            ) {
                messageId
            } else {
                cur.focusedMessageId
            }
            if (messageId != null && nextFocusedId == cur.inspectableResponses.lastOrNull()?.messageId) {
                userNavigatedFocus = false
            }
            val updated = cur.copy(
                focusedMessageId = nextFocusedId,
                speakingMessageId = messageId,
            )
            if (updated != cur) {
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun recordSpokenSummary(messageId: String, text: String) {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val cur = _feedState.value
            val updated = cur.copy(
                spokenSummary = SpokenSummaryState(messageId = messageId, text = cleaned),
            )
            if (updated != cur) {
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun setDirectAnswer(answer: DirectVoiceAnswer?) {
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val cur = _feedState.value
            if (cur.directAnswer != answer) {
                val updated = cur.copy(directAnswer = answer)
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun dismissDirectAnswer() {
        setDirectAnswer(null)
    }

    fun selectResponse(messageId: String) {
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val cur = _feedState.value
            val inspectable = cur.inspectableResponses
            val idx = inspectable.indexOfFirst { it.messageId == messageId }
            if (idx < 0) return
            lastInterruptedMessageId = null
            userNavigatedFocus = !(cur.unreadQueue.isEmpty() && idx == inspectable.lastIndex)
            val updated = hydrateFocusedSpokenSummaryLocked(cur.copy(focusedMessageId = messageId))
            if (updated != cur) {
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun stepResponse(delta: Int) {
        if (delta == 0) return
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val cur = _feedState.value
            val inspectable = cur.inspectableResponses
            if (inspectable.isEmpty()) return
            val currentId = cur.focusedResponse?.messageId
            val currentIdx = currentId?.let { id -> inspectable.indexOfFirst { it.messageId == id } }
                ?.takeIf { it >= 0 }
                ?: inspectable.lastIndex
            val targetIdx = (currentIdx + delta).coerceIn(0, inspectable.lastIndex)
            val targetId = inspectable[targetIdx].messageId
            lastInterruptedMessageId = null
            userNavigatedFocus = !(cur.unreadQueue.isEmpty() && targetIdx == inspectable.lastIndex)
            val updated = hydrateFocusedSpokenSummaryLocked(cur.copy(focusedMessageId = targetId))
            if (updated != cur) {
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun refreshFocusedSpokenSummaryFromCache() {
        val lookup = lookupCachedSpokenSummary ?: return
        var notifyFeed: ResponseFeedState? = null
        synchronized(stateLock) {
            val cur = _feedState.value
            val focused = cur.focusedResponse
            val cached = focused?.let { lookup(it.assistantText) }?.trim()?.takeIf { it.isNotEmpty() }
            val nextSummary = if (focused != null && cached != null) {
                SpokenSummaryState(messageId = focused.messageId, text = cached)
            } else {
                null
            }
            if (cur.spokenSummary != nextSummary) {
                val updated = cur.copy(spokenSummary = nextSummary)
                _feedState.value = updated
                notifyFeed = updated
            }
        }
        notifyFeed?.let(onFeedUpdated)
    }

    fun maybeTriggerAutoSpeakIfIdle() {
        val shouldTrigger = synchronized(stateLock) {
            val session = boundSession ?: return@synchronized false
            val state = currentState()
            val feed = _feedState.value
            armed &&
                (state == PttState.ARMED_IDLE || state == PttState.STAGED_DRAFT) &&
                !feed.autoSpeakPaused &&
                !isPlaybackActive() &&
                nextItemToAutoSpeakLocked(feed, windowCache[session.sessionKey]) != null
        }
        if (shouldTrigger) {
            onTriggerAutoSpeak()
        }
    }

    fun buildVoiceReplyContext(): VoiceReplyContext = synchronized(stateLock) {
        val feed = _feedState.value
        val focusedItem = resolveVoiceContextFocusedItemLocked(feed)
        val sameTurnSiblings = if (focusedItem != null) {
            feed.turns
                .firstOrNull { it.turnId == focusedItem.turnId }
                ?.responses
                .orEmpty()
                .asSequence()
                .filter { it.messageId != focusedItem.messageId && it.assistantText.isNotBlank() }
                .map { it.assistantText }
                .toList()
        } else {
            emptyList()
        }
        val pendingUnreadPreviews = feed.unreadQueue
            .asSequence()
            .filter { it.messageId != focusedItem?.messageId && it.assistantText.isNotBlank() }
            .map { item ->
                UnreadReplyPreview(
                    messageId = item.messageId,
                    sourceLabel = formatUnreadSourceLabel(item),
                    previewText = item.assistantText,
                )
            }
            .toList()

        VoiceReplyContext(
            focusedReply = focusedItem?.assistantText,
            focusedTriggerLabel = focusedItem?.let(::formatFocusedTriggerLabel),
            sameTurnSiblings = sameTurnSiblings,
            pendingUnreadPreviews = pendingUnreadPreviews,
        )
    }

    fun currentReplyContextTargetMessageId(): String? = synchronized(stateLock) {
        resolveVoiceContextFocusedItemLocked(_feedState.value)?.messageId
    }

    private fun resolveVoiceContextFocusedItemLocked(feed: ResponseFeedState): AssistantResponseItem? {
        val all = feed.allResponses
        val interruptedItem = lastInterruptedMessageId?.let { id ->
            all.firstOrNull { it.messageId == id }
        }
        if (interruptedItem != null) return interruptedItem

        if (userNavigatedFocus) {
            feed.focusedResponse?.let { return it }
        }

        val heardId = boundSession?.let { tokenStore.getReplyCursor(it.sessionKey)?.lastHeardMessageId }
        val heardItem = heardId?.let { id -> all.firstOrNull { it.messageId == id } }
        if (heardItem != null) return heardItem

        return feed.focusedResponse ?: all.lastOrNull()
    }

    private fun launchSyncLoop(loopToken: Any, initialReason: String) {
        val job = scope.launch(ioDispatcher) {
            runSyncLoop(loopToken = loopToken, initialReason = initialReason)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                cleanupAbandonedSyncLoop(loopToken, cause)
            }
        }
    }

    private suspend fun runSyncLoop(loopToken: Any, initialReason: String) {
        var activeWaiters: List<CompletableDeferred<Result<ResponseFeedState>>> = emptyList()
        try {
            syncMutex.withLock {
                while (true) {
                    val targetSession: BoundServerSession?
                    val targetGen: Long
                    val doAutoSpeak: Boolean
                    val doCaptureAnchor: Boolean
                    val doForceFocus: Boolean
                    val iterationWaiters: List<CompletableDeferred<Result<ResponseFeedState>>>
                    val cursorBeforeFetch: SessionReplyCursor?
                    @Suppress("UNUSED_VARIABLE")
                    val reason: String

                    synchronized(stateLock) {
                        if (!resyncRequested || activeSyncLoopToken !== loopToken) {
                            if (activeSyncLoopToken === loopToken) {
                                activeSyncLoopToken = null
                            }
                            return@withLock
                        }
                        resyncRequested = false
                        doAutoSpeak = pendingTriggerAutoSpeak
                        doCaptureAnchor = pendingCaptureAutoSpeakAnchor
                        doForceFocus = pendingForceFocusLatest
                        reason = pendingReason ?: initialReason
                        pendingTriggerAutoSpeak = false
                        pendingCaptureAutoSpeakAnchor = false
                        pendingForceFocusLatest = false
                        pendingReason = null
                        iterationWaiters = pendingWaiters.toList()
                        pendingWaiters.clear()
                        targetSession = boundSession
                        targetGen = sessionGeneration
                        cursorBeforeFetch = targetSession?.let { tokenStore.getReplyCursor(it.sessionKey) }
                    }
                    activeWaiters = iterationWaiters

                    if (targetSession == null) {
                        val feed = _feedState.value
                        iterationWaiters.forEach { it.complete(Result.success(feed)) }
                        activeWaiters = emptyList()
                        continue
                    }

                    val stopTargets = buildSet {
                        cursorBeforeFetch?.lastHeardMessageId
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() && it != SessionTurnProjection.SESSION_START_BASELINE }
                            ?.let(::add)
                        cursorBeforeFetch?.armedBaselineMessageId
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() && it != SessionTurnProjection.SESSION_START_BASELINE }
                            ?.let(::add)
                    }

                    val fetchResult = runCatching {
                        sessionClient.fetchTurnsWindow(
                            serverUrl = targetSession.serverUrl,
                            sessionId = targetSession.pinnedSession.sessionId,
                            directory = targetSession.pinnedSession.directory,
                            stopAtMessageIds = stopTargets,
                            stopOnAnyTarget = true,
                        )
                    }

                    val fetchErr = fetchResult.exceptionOrNull()
                    if (fetchErr != null) {
                        if (fetchErr is CancellationException) throw fetchErr
                        if (PttForegroundService.isUnauthorizedError(fetchErr)) {
                            tokenStore.clearAuth(targetSession.serverId)
                        }
                        iterationWaiters.forEach { it.complete(Result.failure(fetchErr)) }
                        activeWaiters = emptyList()
                        continue
                    }

                    val window = fetchResult.getOrThrow()
                    var appliedFeed: ResponseFeedState? = null
                    var shouldFireAutoSpeak = false

                    synchronized(stateLock) {
                        if (boundSession?.sessionKey == targetSession.sessionKey &&
                            sessionGeneration == targetGen
                        ) {
                            val result = applyWindowLocked(
                                session = targetSession,
                                window = window,
                                captureAutoSpeakAnchor = doCaptureAnchor,
                                forceFocusLatest = doForceFocus,
                                triggerAutoSpeak = doAutoSpeak,
                            )
                            appliedFeed = result.first
                            shouldFireAutoSpeak = result.second
                        }
                    }

                    val feedToReturn = appliedFeed ?: _feedState.value
                    if (appliedFeed != null) {
                        onFeedUpdated(feedToReturn)
                    }
                    iterationWaiters.forEach { it.complete(Result.success(feedToReturn)) }
                    activeWaiters = emptyList()

                    if (shouldFireAutoSpeak) {
                        onTriggerAutoSpeak()
                    }
                }
            }
        } catch (ce: CancellationException) {
            activeWaiters.forEach { it.complete(Result.failure(ce)) }
            throw ce
        } finally {
            cleanupAbandonedSyncLoop(loopToken, CancellationException("Sync loop terminated"))
        }
    }

    private fun cleanupAbandonedSyncLoop(loopToken: Any, cause: Throwable) {
        val orphanedWaiters: List<CompletableDeferred<Result<ResponseFeedState>>>
        synchronized(stateLock) {
            if (activeSyncLoopToken === loopToken) {
                activeSyncLoopToken = null
                resyncRequested = false
                pendingTriggerAutoSpeak = false
                pendingCaptureAutoSpeakAnchor = false
                pendingForceFocusLatest = false
                pendingReason = null
                orphanedWaiters = pendingWaiters.toList()
                pendingWaiters.clear()
            } else {
                orphanedWaiters = emptyList()
            }
        }
        orphanedWaiters.forEach { it.complete(Result.failure(cause)) }
    }

    private fun applyWindowLocked(
        session: BoundServerSession,
        window: TurnsWindow,
        captureAutoSpeakAnchor: Boolean,
        forceFocusLatest: Boolean,
        triggerAutoSpeak: Boolean,
    ): Pair<ResponseFeedState, Boolean> {
        cacheWindowLocked(session.sessionKey, window)

        var cursor = tokenStore.getReplyCursor(session.sessionKey)
        if (cursor == null) {
            val baselineId = window.latestSettledMessageId ?: SessionTurnProjection.SESSION_START_BASELINE
            val baselineCreatedAt = if (baselineId == SessionTurnProjection.SESSION_START_BASELINE) {
                0L
            } else {
                window.records.firstOrNull { it.id == baselineId }?.createdAtMs ?: 0L
            }
            cursor = SessionReplyCursor(
                armedBaselineMessageId = baselineId,
                armedBaselineCreatedAtMs = baselineCreatedAt,
                updatedAtMs = wallClockMs(),
            )
            tokenStore.saveReplyCursor(session.sessionKey, cursor)
        }

        if ((captureAutoSpeakAnchor || needsArmedAnchorCapture) && !activePromptTurn) {
            setAutoSpeakAnchorFromWindowLocked(window)
        }

        val allCompleted = window.turns
            .asSequence()
            .flatMap { it.responses.asSequence() }
            .filter { it.isCompleted && it.assistantText.isNotBlank() }
            .toList()

        val wasInitialDone = initialSyncDoneForSession
        val prevFeed = _feedState.value
        val prevLastTurnId = prevFeed.turns.lastOrNull()?.turnId
        val newLastTurnId = window.turns.lastOrNull()?.turnId
        val prevLatestCompletedId = prevFeed.allResponses.lastOrNull { it.isCompleted }?.messageId
        val newLatestCompletedId = allCompleted.lastOrNull()?.messageId
        val prevLatestAnyId = prevFeed.allResponses.lastOrNull()?.messageId
        val newLatestAnyId = window.turns.lastOrNull()?.responses?.lastOrNull()?.messageId

        val newCompletedAfterAnchor = if (!wasInitialDone) {
            false
        } else {
            allCompleted.any { item ->
                item.messageId !in seenCompletedIds &&
                    isStrictlyAfterAnchorLocked(item, window.records, window.reachedHistoryStart)
            }
        }

        for (item in allCompleted) {
            seenCompletedIds.add(item.messageId)
        }
        initialSyncDoneForSession = true

        val nextAutoSpeakPaused = if (newCompletedAfterAnchor) {
            false
        } else {
            prevFeed.autoSpeakPaused
        }

        val newTurnOrResponseArrived = wasInitialDone && (
            newCompletedAfterAnchor ||
                (newLastTurnId != null && newLastTurnId != prevLastTurnId) ||
                (newLatestAnyId != null && newLatestAnyId != prevLatestAnyId) ||
                (newLatestCompletedId != null && newLatestCompletedId != prevLatestCompletedId)
            )

        val playbackBusy = isPlaybackActive()
        if (!playbackBusy && newTurnOrResponseArrived) {
            userNavigatedFocus = false
        }

        val effectiveForceFocusLatest = forceFocusLatest ||
            (!playbackBusy && !userNavigatedFocus && newTurnOrResponseArrived)

        val projected = hydrateFocusedSpokenSummaryLocked(
            SessionTurnProjection.projectResponseFeed(
                chronologicalRecords = window.records,
                turns = window.turns,
                cursor = cursor,
                previousState = prevFeed.copy(
                    autoSpeakPaused = nextAutoSpeakPaused,
                    directAnswer = if (newTurnOrResponseArrived) null else prevFeed.directAnswer,
                ),
                forceFocusLatest = effectiveForceFocusLatest,
            ),
        )

        if (projected.focusedMessageId == projected.inspectableResponses.lastOrNull()?.messageId) {
            userNavigatedFocus = false
        }

        _feedState.value = projected

        val state = currentState()
        val shouldTrigger = triggerAutoSpeak &&
            armed &&
            (state == PttState.ARMED_IDLE || state == PttState.STAGED_DRAFT) &&
            !projected.autoSpeakPaused &&
            !playbackBusy &&
            nextItemToAutoSpeakLocked(projected, window) != null

        return projected to shouldTrigger
    }

    private fun markItemHeardLocked(
        session: BoundServerSession,
        item: AssistantResponseItem,
    ): Boolean {
        val existing = tokenStore.getReplyCursor(session.sessionKey)
        val existingHeardId = existing?.lastHeardMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val records = windowCache[session.sessionKey]?.records.orEmpty()

        if (existingHeardId == item.messageId) {
            return false
        }
        if (existing != null && existingHeardId != null && records.isNotEmpty()) {
            val existingIdx = records.indexOfFirst { it.id == existingHeardId }
            val newIdx = records.indexOfFirst { it.id == item.messageId }
            if (existingIdx >= 0 && newIdx in 0 until existingIdx) {
                return false
            }
        } else if (existing != null && existingHeardId != null && item.createdAtMs > 0L && existing.lastHeardCreatedAtMs > item.createdAtMs) {
            return false
        }

        val updatedCursor = SessionReplyCursor(
            lastHeardMessageId = item.messageId,
            lastHeardCreatedAtMs = item.createdAtMs,
            armedBaselineMessageId = existing?.armedBaselineMessageId
                ?: SessionTurnProjection.SESSION_START_BASELINE,
            armedBaselineCreatedAtMs = existing?.armedBaselineCreatedAtMs ?: 0L,
            updatedAtMs = wallClockMs(),
        )
        tokenStore.saveReplyCursor(session.sessionKey, updatedCursor)
        return true
    }

    private fun reprojectCurrentFeedLocked(forceFocusLatest: Boolean): ResponseFeedState {
        val session = boundSession ?: return _feedState.value
        val cur = _feedState.value
        val cursor = tokenStore.getReplyCursor(session.sessionKey)
        val cached = windowCache[session.sessionKey]
        val records = cached?.records ?: synthesizeRecordsFromTurns(cur.turns)
        val projected = hydrateFocusedSpokenSummaryLocked(
            SessionTurnProjection.projectResponseFeed(
                chronologicalRecords = records,
                turns = cur.turns,
                cursor = cursor,
                previousState = cur,
                forceFocusLatest = forceFocusLatest,
            ),
        )
        if (projected.focusedMessageId == projected.inspectableResponses.lastOrNull()?.messageId) {
            userNavigatedFocus = false
        }
        return projected
    }

    private fun restoreCachedFeedIfEmptyLocked(): Boolean {
        val session = boundSession ?: return false
        val cur = _feedState.value
        if (cur.allResponses.isNotEmpty()) return false
        val cached = windowCache[session.sessionKey] ?: return false
        if (cached.turns.none { it.responses.isNotEmpty() }) return false
        val cursor = tokenStore.getReplyCursor(session.sessionKey)
        val projected = hydrateFocusedSpokenSummaryLocked(
            SessionTurnProjection.projectResponseFeed(
                chronologicalRecords = cached.records,
                turns = cached.turns,
                cursor = cursor,
                previousState = cur,
                forceFocusLatest = false,
            ),
        )
        _feedState.value = projected
        return true
    }

    private fun hydrateFocusedSpokenSummaryLocked(feed: ResponseFeedState): ResponseFeedState {
        val lookup = lookupCachedSpokenSummary ?: return feed
        val focused = feed.focusedResponse ?: return feed
        if (feed.spokenSummary?.messageId == focused.messageId && feed.spokenSummary.text.isNotBlank()) {
            return feed
        }
        val cachedText = lookup(focused.assistantText)?.trim()?.takeIf { it.isNotEmpty() } ?: return feed
        return feed.copy(
            spokenSummary = SpokenSummaryState(
                messageId = focused.messageId,
                text = cachedText,
            ),
        )
    }

    private fun nextItemToAutoSpeakLocked(
        feed: ResponseFeedState,
        window: TurnsWindow?,
    ): AssistantResponseItem? {
        if (feed.unreadQueue.isEmpty()) return null
        val records = window?.records.orEmpty()
        val reachedStart = window?.reachedHistoryStart ?: false
        return feed.unreadQueue.firstOrNull { item ->
            isStrictlyAfterAnchorLocked(item, records, reachedStart)
        }
    }

    private fun isStrictlyAfterAnchorLocked(
        item: AssistantResponseItem,
        records: List<ParsedSessionRecord>,
        reachedHistoryStart: Boolean,
    ): Boolean {
        val anchorId = autoSpeakAnchorId ?: return !(armed && needsArmedAnchorCapture)
        if (anchorId == SessionTurnProjection.SESSION_START_BASELINE) return true
        if (records.isNotEmpty()) {
            val anchorIdx = records.indexOfFirst { it.id == anchorId }
            val itemIdx = records.indexOfFirst { it.id == item.messageId }
            if (anchorIdx >= 0 && itemIdx >= 0) {
                return itemIdx > anchorIdx
            }
            if (anchorIdx < 0 && !reachedHistoryStart && autoSpeakAnchorCreatedAtMs <= 0L) {
                return true
            }
        }
        if (autoSpeakAnchorCreatedAtMs > 0L) {
            return item.createdAtMs > autoSpeakAnchorCreatedAtMs
        }
        return false
    }

    private fun setAutoSpeakAnchorFromWindowLocked(window: TurnsWindow) {
        val anchorId = window.latestSettledMessageId ?: SessionTurnProjection.SESSION_START_BASELINE
        autoSpeakAnchorId = anchorId
        autoSpeakAnchorCreatedAtMs = if (anchorId == SessionTurnProjection.SESSION_START_BASELINE) {
            0L
        } else {
            window.records.firstOrNull { it.id == anchorId }?.createdAtMs ?: 0L
        }
        needsArmedAnchorCapture = false
    }

    private fun prepareArmedStreamLocked(
        session: BoundServerSession,
        targetGen: Long,
    ): Job {
        val readySignal = CompletableDeferred<Unit>()
        armedStreamReady = readySignal
        streamServerUrl = session.serverUrl
        streamSessionId = session.pinnedSession.sessionId
        val job = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            try {
                eventStreamClient.observeTurnEvents(
                    serverUrl = session.serverUrl,
                    targetSessionId = session.pinnedSession.sessionId,
                    onReady = { readySignal.complete(Unit) },
                ).collect { event ->
                    val (isCurrentGen, shouldSync) = synchronized(stateLock) {
                        val currentGen = armed &&
                            boundSession?.sessionKey == session.sessionKey &&
                            sessionGeneration == targetGen
                        val sync = currentGen &&
                            !activePromptTurn &&
                            (event is TurnEvent.Completed || event is TurnEvent.SyntheticArrived)
                        currentGen to sync
                    }
                    if (!isCurrentGen) return@collect
                    activeTurnEvents.tryEmit(event)
                    if (shouldSync) {
                        val syncReason = when (event) {
                            TurnEvent.Completed -> "ws_completed"
                            is TurnEvent.SyntheticArrived -> "ws_synthetic_${event.source ?: "unknown"}"
                            else -> "ws_event"
                        }
                        requestBackgroundSync(
                            reason = syncReason,
                            triggerAutoSpeak = true,
                        )
                    }
                }
            } finally {
                readySignal.cancel()
            }
        }
        armedStreamJob = job
        return job
    }

    private fun detachArmedStreamLocked(): Pair<Job?, CompletableDeferred<Unit>?> {
        val job = armedStreamJob
        val ready = armedStreamReady
        armedStreamJob = null
        armedStreamReady = null
        streamServerUrl = null
        streamSessionId = null
        return job to ready
    }

    private fun clearSessionScopedStateLocked() {
        autoSpeakAnchorId = null
        autoSpeakAnchorCreatedAtMs = 0L
        needsArmedAnchorCapture = false
        lastInterruptedMessageId = null
        userNavigatedFocus = false
        seenCompletedIds.clear()
        initialSyncDoneForSession = false
        activePromptTurn = false
    }

    private fun enqueueSyncRequestLocked(
        reason: String,
        triggerAutoSpeak: Boolean,
        captureAutoSpeakAnchor: Boolean,
        forceFocusLatest: Boolean,
        waiter: CompletableDeferred<Result<ResponseFeedState>>?,
    ) {
        resyncRequested = true
        pendingTriggerAutoSpeak = pendingTriggerAutoSpeak || triggerAutoSpeak
        pendingCaptureAutoSpeakAnchor = pendingCaptureAutoSpeakAnchor || captureAutoSpeakAnchor
        pendingForceFocusLatest = pendingForceFocusLatest || forceFocusLatest
        pendingReason = reason
        if (waiter != null) {
            pendingWaiters.add(waiter)
        }
    }

    private fun clearPendingSyncOnSessionSwitchLocked(): List<CompletableDeferred<Result<ResponseFeedState>>> {
        resyncRequested = false
        pendingTriggerAutoSpeak = false
        pendingCaptureAutoSpeakAnchor = false
        pendingForceFocusLatest = false
        pendingReason = null
        val waiters = pendingWaiters.toList()
        pendingWaiters.clear()
        return waiters
    }

    private fun cacheWindowLocked(sessionKey: String, window: TurnsWindow) {
        windowCache[sessionKey] = window
        if (windowCache.size > MAX_CACHED_WINDOWS) {
            val oldestKey = windowCache.keys.firstOrNull()
            if (oldestKey != null && oldestKey != sessionKey) {
                windowCache.remove(oldestKey)
            }
        }
    }

    private fun formatFocusedTriggerLabel(item: AssistantResponseItem): String? {
        val explicit = item.triggerLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        return when (item.triggerKind) {
            TurnTriggerKind.USER_PROMPT -> null
            TurnTriggerKind.ASYNC_SESSION_RESULT -> "Async session"
            TurnTriggerKind.SUBAGENT_REPORT -> "Subagent"
        }
    }

    private fun formatUnreadSourceLabel(item: AssistantResponseItem): String {
        val explicit = item.triggerLabel?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        return when (item.triggerKind) {
            TurnTriggerKind.USER_PROMPT -> "Reply"
            TurnTriggerKind.ASYNC_SESSION_RESULT -> "Async session"
            TurnTriggerKind.SUBAGENT_REPORT -> "Subagent"
        }
    }

    private fun synthesizeRecordsFromTurns(turns: List<com.openchamber.ptt.voice.SessionTurn>): List<ParsedSessionRecord> {
        val list = ArrayList<ParsedSessionRecord>()
        for (turn in turns) {
            for (resp in turn.responses) {
                list.add(
                    ParsedSessionRecord.AssistantRecord(
                        id = resp.messageId,
                        createdAtMs = resp.createdAtMs,
                        completedAtMs = if (resp.isCompleted) resp.createdAtMs else null,
                        finish = if (resp.isCompleted) "stop" else null,
                        hasTimeObject = true,
                        text = resp.assistantText,
                    ),
                )
            }
        }
        return list
    }

    companion object {
        private const val MAX_CACHED_WINDOWS = 32
    }
}
