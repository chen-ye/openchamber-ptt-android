package com.openchamber.ptt.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import com.openchamber.ptt.audio.BluetoothAudioRouter
import com.openchamber.ptt.audio.Earcon
import com.openchamber.ptt.audio.EarconPlayer
import com.openchamber.ptt.audio.ScoDropEvent
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.logging.VoiceUxSummary
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.session.SessionApiException
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsException
import com.openchamber.ptt.tts.TtsPlayer
import com.openchamber.ptt.voice.DictationClient
import com.openchamber.ptt.voice.DictationException
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.MlKitSttClient
import com.openchamber.ptt.voice.PromptOrganizer
import com.openchamber.ptt.voice.PromptOrganizerException
import com.openchamber.ptt.voice.ResponseFeedState
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.TurnTriggerKind
import com.openchamber.ptt.voice.VoiceControlPlane
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PttForegroundService : Service() {
    inner class PttBinder : Binder() {
        val service: PttForegroundService
            get() = this@PttForegroundService
    }

    private val binder = PttBinder()
    private val dispatchLock = Any()
    private val stateMachine = PttStateMachine()
    private val _transitions = MutableSharedFlow<PttTransition>(extraBufferCapacity = 32)
    private val _statusText = MutableStateFlow("")
    private val _lastTranscript = MutableStateFlow<String?>(null)
    private val _lastReply = MutableStateFlow<String?>(null)
    private val _lastSpokenSummary = MutableStateFlow<String?>(null)
    private val _lastTtsInfo = MutableStateFlow<String?>(null)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _pinBluetoothSco = MutableStateFlow(false)
    private val _recordingStartedAtElapsedMs = MutableStateFlow<Long?>(null)
    private val _stagedDraft = MutableStateFlow<StagedDraft?>(null)
    private val _pendingQuickTranscript = MutableStateFlow<String?>(null)
    private val _sendGraceRemainingMs = MutableStateFlow<Long?>(null)
    private val _isOrganizingDraft = MutableStateFlow(false)

    val state: StateFlow<PttState> = stateMachine.state
    val statusText: StateFlow<String> = _statusText.asStateFlow()
    val lastTranscript: StateFlow<String?> = _lastTranscript.asStateFlow()
    val lastReply: StateFlow<String?> = _lastReply.asStateFlow()
    val lastSpokenSummary: StateFlow<String?> = _lastSpokenSummary.asStateFlow()
    val lastTtsInfo: StateFlow<String?> = _lastTtsInfo.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val permissionPrompt: StateFlow<String?> = stateMachine.permissionPrompt
    val pinBluetoothScoFlow: StateFlow<Boolean> = _pinBluetoothSco.asStateFlow()
    val recordingStartedAtElapsedMs: StateFlow<Long?> = _recordingStartedAtElapsedMs.asStateFlow()
    val stagedDraft: StateFlow<StagedDraft?> = _stagedDraft.asStateFlow()
    val pendingQuickTranscript: StateFlow<String?> = _pendingQuickTranscript.asStateFlow()
    val sendGraceRemainingMs: StateFlow<Long?> = _sendGraceRemainingMs.asStateFlow()
    val isOrganizingDraft: StateFlow<Boolean> = _isOrganizingDraft.asStateFlow()
    val transitions: SharedFlow<PttTransition> = _transitions.asSharedFlow()
    val responseFeed: StateFlow<ResponseFeedState>
        get() = sessionResponseController.feedState

    var pinBluetoothSco: Boolean
        get() = _pinBluetoothSco.value
        set(value) {
            _pinBluetoothSco.value = value
        }

    private lateinit var earconPlayer: EarconPlayer
    private lateinit var bluetoothAudioRouter: BluetoothAudioRouter
    private lateinit var gestureDetector: MediaButtonGestureDetector
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var notificationBuilder: PttNotificationBuilder
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private lateinit var tokenStore: TokenStore
    private lateinit var sessionClient: SessionClient
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var voiceUxLogger: VoiceUxLogger
    private lateinit var serviceScope: CoroutineScope
    private lateinit var audioBurstRecorder: AudioBurstRecorder
    private lateinit var sessionResponseController: SessionResponseController
    private lateinit var agentTurnRunner: AgentTurnRunner

    private var sendGraceJob: Job? = null
    private var loadedSessionKey: String? = null

    @Volatile
    private var sendGraceStartedAtMs: Long = 0L

    @Volatile
    private var promotedForStandaloneTts: Boolean = false

    @Volatile
    private var lastTtsStopElapsedMs: Long = 0L

    @Volatile
    private var activeSpokenMessageId: String? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        bluetoothAudioRouter = BluetoothAudioRouter(this)
        earconPlayer = EarconPlayer(
            context = applicationContext,
            useCommunicationUsage = { bluetoothAudioRouter.isScoPinned },
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationBuilder = PttNotificationBuilder(this, notificationManager)
        voiceUxLogger = VoiceUxLogger(applicationContext)

        tokenStore = TokenStore(applicationContext)
        val httpClient = OpenChamberHttp.createClient(
            tokenForUrlProvider = { url -> tokenStore.getTokenForUrl(url) },
            directoryForUrlProvider = { url -> tokenStore.getDirectoryForUrl(url) },
        )
        val dictationClient = DictationClient(httpClient)
        val mlKitSttClient = MlKitSttClient()
        sessionClient = SessionClient(httpClient)
        val eventStreamClient = EventStreamClient(httpClient)
        val ttsClient = TtsClient(httpClient)
        val promptOrganizer = PromptOrganizer(httpClient)
        val voiceControlPlane = VoiceControlPlane(httpClient)
        ttsPlayer = TtsPlayer(
            context = applicationContext,
            ttsClient = ttsClient,
            useCommunicationUsage = { bluetoothAudioRouter.isScoPinned },
            pipelineProvider = { tokenStore.getEffectiveTtsPipeline() },
            mlKitVoiceNameProvider = { tokenStore.getMlKitTtsVoice() },
            openAiVoiceProvider = { tokenStore.getEffectiveOpenAiTtsVoice() },
            systemVoiceNameProvider = { tokenStore.getSystemTtsVoice() },
            inputModeProvider = { tokenStore.getEffectiveTtsInputMode() },
            detailLevelProvider = { tokenStore.getEffectiveSpokenDetailLevel() },
            onStatusEvent = { info -> _lastTtsInfo.value = info },
            onSpokenTextSynthesized = { spoken ->
                val msgId = activeSpokenMessageId ?: sessionResponseController.feedState.value.speakingMessageId
                if (msgId != null) {
                    sessionResponseController.recordSpokenSummary(msgId, spoken)
                    if (sessionResponseController.feedState.value.focusedResponse?.messageId == msgId) {
                        _lastSpokenSummary.value = spoken
                    }
                } else {
                    _lastSpokenSummary.value = spoken
                }
            },
            onSpeakStats = { stats ->
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_TTS,
                    event = "spoken",
                    sessionKey = activeSessionKey(),
                    metrics = mapOf(
                        "kind" to stats.kind,
                        "inputMode" to stats.inputMode.name,
                        "prepareMs" to stats.prepareMs,
                        "rawChars" to stats.rawChars,
                        "spokenChars" to stats.spokenChars,
                        "engine" to stats.engine.name,
                    ),
                )
            },
        )

        audioBurstRecorder = AudioBurstRecorder(
            scope = serviceScope,
            dictationClient = dictationClient,
            mlKitSttClient = mlKitSttClient,
            voiceUxLogger = voiceUxLogger,
            currentState = { stateMachine.currentState },
            sessionKeyProvider = { activeSessionKey() },
            lastTtsStopElapsedMsProvider = { lastTtsStopElapsedMs },
            hasRecordAudioPermission = { hasRecordAudioPermission() },
            pinBluetoothScoProvider = { pinBluetoothSco },
            sttEngineProvider = { tokenStore.getEffectiveSttEngine() },
            sttFallbackToParakeetProvider = { tokenStore.getEffectiveSttFallbackToParakeet() },
            onPlayWarningCue = { earconPlayer.play(Earcon.STAGED_PAUSE) },
            onMaxCapAutoStage = { stageOnMaxCap() },
            onPartialTranscript = { partial -> _lastTranscript.value = partial },
            onStatusUpdate = { msg -> _statusText.value = msg },
        )

        sessionResponseController = SessionResponseController(
            scope = serviceScope,
            tokenStore = tokenStore,
            sessionClient = sessionClient,
            eventStreamClient = eventStreamClient,
            currentState = { stateMachine.currentState },
            isPlaybackActive = {
                (::agentTurnRunner.isInitialized && agentTurnRunner.isPlaybackActive) ||
                    ttsPlayer.isSpeaking ||
                    stateMachine.currentState == PttState.SPEAKING_TTS ||
                    stateMachine.currentState == PttState.STAGED_DRAFT_SPEAKING
            },
            onTriggerAutoSpeak = { dispatch(PttEvent.PlayQueuedReply) },
            onFeedUpdated = { feed -> syncFromResponseFeed(feed) },
        )

        agentTurnRunner = AgentTurnRunner(
            scope = serviceScope,
            tokenStore = tokenStore,
            sessionClient = sessionClient,
            eventStreamClient = eventStreamClient,
            promptOrganizer = promptOrganizer,
            voiceControlPlane = voiceControlPlane,
            voiceUxLogger = voiceUxLogger,
            speakTts = { req ->
                ttsPlayer.speak(
                    serverUrl = req.serverUrl,
                    text = req.text,
                    voice = req.voice,
                    sessionLabel = req.sessionLabel,
                    inputModeOverride = req.inputModeOverride,
                    kind = req.kind,
                    notifySpokenSummary = req.notifySpokenSummary,
                )
            },
            currentState = { stateMachine.currentState },
            activeSessionKey = { activeSessionKey() },
            currentStagedDraft = { _stagedDraft.value },
            updateStagedDraft = { _stagedDraft.value = it },
            currentPendingQuickTranscript = { _pendingQuickTranscript.value },
            clearPendingQuickTranscript = { _pendingQuickTranscript.value = null },
            setPendingQuickTranscript = { _pendingQuickTranscript.value = it },
            currentLastReply = { _lastReply.value },
            updateLastReply = { _lastReply.value = it },
            updateLastTranscript = { _lastTranscript.value = it },
            updateLastSpokenSummary = { _lastSpokenSummary.value = it },
            updateStatusText = { _statusText.value = it },
            setOrganizingDraft = { _isOrganizingDraft.value = it },
            syncActiveBubble = { statusSummary -> syncActiveBubble(statusSummary ?: _statusText.value) },
            onTranscriptReady = { onTranscriptReady(it) },
            onDraftPreviewReady = { dispatch(PttEvent.DraftPreviewReady) },
            onAgentCompleted = { hasTts -> onAgentCompleted(hasTts) },
            onAgentInterrupted = { dispatch(PttEvent.AgentInterrupted) },
            onPermissionAsked = { tool -> onPermissionAsked(tool) },
            onPermissionResolved = { onPermissionResolved() },
            onTtsFinished = { onTtsFinished() },
            onError = { message, isAuthExpired -> onError(message, isAuthExpired) },
            onPostPermissionAlertNotification = { tool -> notificationBuilder.postPermissionAlertNotification(tool) },
            onCancelPermissionAlertNotification = { notificationBuilder.cancelPermissionAlertNotification() },
            observeTurnEvents = sessionResponseController::observeEventsForActiveTurn,
            buildVoiceReplyContext = sessionResponseController::buildVoiceReplyContext,
            currentReplyContextTargetMessageId = sessionResponseController::currentReplyContextTargetMessageId,
            onDirectAnswer = sessionResponseController::setDirectAnswer,
            setActivePromptTurn = sessionResponseController::setActivePromptTurn,
            ensureAutoSpeakAnchorBeforeTurn = sessionResponseController::ensureAutoSpeakAnchorBeforeTurn,
            ensureCursorSeededBeforeTurn = sessionResponseController::ensureCursorSeededBeforeTurn,
            onSyncTurnCompleted = {
                sessionResponseController.syncSessionTurns(
                    reason = "turn_completed",
                    triggerAutoSpeak = false,
                    throwOnError = true,
                )
            },
            onSyncForManualReadAloud = {
                sessionResponseController.syncSessionTurns(
                    reason = "manual_read_aloud",
                    triggerAutoSpeak = false,
                    throwOnError = true,
                )
            },
            hasCachedResponses = sessionResponseController::hasCachedResponses,
            nextAutoSpeakItem = sessionResponseController::nextItemToAutoSpeak,
            nextUnreadItem = sessionResponseController::nextUnreadItem,
            itemsForManualReadAloud = sessionResponseController::itemsForManualReadAloud,
            markItemHeard = sessionResponseController::markItemHeard,
            setSpeakingMessage = { msgId ->
                activeSpokenMessageId = msgId
                sessionResponseController.setSpeakingMessage(msgId)
            },
        )

        val mainHandler = Handler(Looper.getMainLooper())
        val scheduler = object : TapTimerScheduler {
            override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
                val r = Runnable { block() }
                mainHandler.postDelayed(r, delayMs)
                return { mainHandler.removeCallbacks(r) }
            }
        }
        gestureDetector = MediaButtonGestureDetector(
            scheduler = scheduler,
            onGesture = { gesture, rawInput, waitMs ->
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_GESTURE,
                    event = gesture.name.lowercase(),
                    sessionKey = activeSessionKey(),
                    metrics = mapOf(
                        "raw" to rawInput.name,
                        "state" to stateMachine.currentState.name,
                        "waitMs" to waitMs,
                    ),
                )
                when (gesture) {
                    MediaGesture.SINGLE_TAP -> onSingleTap()
                    MediaGesture.DOUBLE_TAP -> onDoubleTap()
                    MediaGesture.TRIPLE_TAP -> onTripleTap()
                    MediaGesture.DISARM -> disarm()
                }
            },
        )

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        restoreDraftForActiveSession()
        _statusText.value = notificationBuilder.statusTextForState(stateMachine.currentState)
        notificationBuilder.createNotificationChannels()
        initMediaSession()
        bluetoothAudioRouter.startMonitoring { drop ->
            handleUnexpectedScoDrop(drop)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_PIN_SCO)) {
                pinBluetoothSco = intent.getBooleanExtra(EXTRA_PIN_SCO, false)
            }
            when (intent.action) {
                Intent.ACTION_MEDIA_BUTTON -> {
                    startForegroundWithState(stateMachine.currentState)
                    mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
                }
                ACTION_ARM -> {
                    startForegroundWithState(stateMachine.currentState)
                    if (!arm()) {
                        stopForegroundAndSelf()
                    }
                }
                ACTION_DISARM -> {
                    disarm()
                    stopForegroundAndSelf()
                }
                ACTION_TOGGLE_PTT -> {
                    startForegroundWithState(stateMachine.currentState)
                    onSingleTap()
                }
                ACTION_STAGE_CAPTURE -> {
                    startForegroundWithState(stateMachine.currentState)
                    stageCurrentRecording()
                }
                ACTION_DISCARD_CAPTURE -> {
                    startForegroundWithState(stateMachine.currentState)
                    discardCurrent()
                }
                ACTION_COMMIT_DRAFT -> {
                    startForegroundWithState(stateMachine.currentState)
                    commitStagedDraft()
                }
                ACTION_PULL_BACK_DRAFT -> {
                    startForegroundWithState(stateMachine.currentState)
                    pullBackToDraft()
                }
                ACTION_STOP_SERVICE -> {
                    disarm()
                    stopForegroundAndSelf()
                }
                ACTION_ENSURE_MEDIA_FOREGROUND -> {
                    val cur = stateMachine.currentState
                    if (cur == PttState.SPEAKING_TTS || cur == PttState.STAGED_DRAFT_SPEAKING) {
                        startForegroundWithState(
                            pttState = cur,
                            serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                        )
                    } else if (cur == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::gestureDetector.isInitialized) {
            gestureDetector.cancelPending()
        }
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.setArmed(false)
        }
        cancelAllTurnJobs(stopTts = true)
        if (::notificationBuilder.isInitialized) {
            notificationBuilder.cancelPermissionAlertNotification()
        }
        releaseWakeAndWifiLocks()
        if (::bluetoothAudioRouter.isInitialized) {
            bluetoothAudioRouter.stopMonitoring()
            bluetoothAudioRouter.release()
        }
        if (::earconPlayer.isInitialized) {
            earconPlayer.release()
        }
        if (::voiceUxLogger.isInitialized) {
            voiceUxLogger.close()
        }
        if (::ttsPlayer.isInitialized) {
            ttsPlayer.release()
        }
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        if (::serviceScope.isInitialized) {
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    fun arm(): Boolean {
        val validationError = validateReadyToArm()
        if (validationError != null) {
            _lastError.value = validationError
            _statusText.value = validationError
            earconPlayer.play(Earcon.ERROR)
            return false
        }
        promotedForStandaloneTts = false
        _lastError.value = null
        val restored = restoreDraftForActiveSession()
        if (restored != null) {
            voiceUxLogger.log(
                category = VoiceUxLogger.CAT_DRAFT,
                event = "restored_on_arm",
                sessionKey = restored.sessionKey,
                metrics = mapOf("bursts" to restored.rawBursts.size, "chars" to restored.organizedText.length),
            )
        }
        resolveBoundSession()?.let { bound ->
            loadedSessionKey = bound.sessionKey
            sessionResponseController.bindSession(bound, forceClear = false)
        }
        val transition = dispatch(PttEvent.Arm(hasPersistedDraft = restored != null))
        val isArmedNow = transition.current == PttState.ARMED_IDLE || transition.current == PttState.STAGED_DRAFT
        if (isArmedNow) {
            if (hasRecordAudioPermission()) {
                runCatching { startForegroundWithState(transition.current) }
            }
            sessionResponseController.setArmed(true)
            if (transition.changed) {
                sessionResponseController.requestBackgroundSync(
                    reason = "arm",
                    triggerAutoSpeak = false,
                    captureAutoSpeakAnchor = true,
                )
            }
        }
        return isArmedNow
    }

    fun disarm() {
        gestureDetector.cancelPending()
        promotedForStandaloneTts = false
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.setArmed(false)
        }
        dispatch(PttEvent.Disarm)
        stopForegroundAndSelf()
    }

    fun selectResponse(messageId: String) {
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.selectResponse(messageId)
        }
    }

    fun stepResponse(delta: Int) {
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.stepResponse(delta)
        }
    }

    fun markAllResponsesRead() {
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.markAllResponsesRead()
        }
    }

    fun dismissDirectAnswer() {
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.dismissDirectAnswer()
        }
    }

    fun onSingleTap() {
        dispatch(PttEvent.SingleTap)
    }

    fun onDoubleTap() {
        dispatch(PttEvent.DoubleTap)
    }

    fun onTripleTap() {
        dispatch(PttEvent.TripleTap)
    }

    fun stageCurrentRecording() {
        gestureDetector.cancelPending()
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_GESTURE,
            event = "ui_stage",
            sessionKey = activeSessionKey(),
            metrics = mapOf("state" to stateMachine.currentState.name),
        )
        dispatch(PttEvent.StageCapture)
    }

    private fun stageOnMaxCap() {
        gestureDetector.cancelPending()
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_GESTURE,
            event = "max_cap_stage",
            sessionKey = activeSessionKey(),
            metrics = mapOf("state" to stateMachine.currentState.name),
        )
        dispatch(PttEvent.StageCapture)
    }

    fun discardCurrent() {
        gestureDetector.cancelPending()
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_GESTURE,
            event = "ui_discard",
            sessionKey = activeSessionKey(),
            metrics = mapOf("state" to stateMachine.currentState.name),
        )
        dispatch(PttEvent.DiscardCapture)
    }

    fun discardStagedDraft() {
        gestureDetector.cancelPending()
        val key = activeSessionKey()
        if (key != null) {
            tokenStore.clearStagedDraft(key)
            voiceUxLogger.log(VoiceUxLogger.CAT_DRAFT, "discarded", key)
        }
        _stagedDraft.value = null
        earconPlayer.play(Earcon.CANCEL_DISCARD)
        dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = false))
        syncActiveBubble()
    }

    fun commitStagedDraft() {
        gestureDetector.cancelPending()
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_GESTURE,
            event = "ui_commit_draft",
            sessionKey = activeSessionKey(),
            metrics = mapOf("state" to stateMachine.currentState.name),
        )
        dispatch(PttEvent.CommitDraft)
    }

    fun pullBackToDraft() {
        gestureDetector.cancelPending()
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_GESTURE,
            event = "ui_pull_back",
            sessionKey = activeSessionKey(),
            metrics = mapOf("state" to stateMachine.currentState.name),
        )
        dispatch(PttEvent.PullBackToDraft)
    }

    fun updateDraftText(newText: String) {
        val key = activeSessionKey() ?: return
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) {
            tokenStore.clearStagedDraft(key)
            _stagedDraft.value = null
            dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = false))
            voiceUxLogger.log(VoiceUxLogger.CAT_DRAFT, "cleared_via_edit", key)
            return
        }
        val prev = tokenStore.getStagedDraft(key)
        val updated = StagedDraft(
            sessionKey = key,
            rawBursts = prev?.rawBursts ?: listOf(trimmed),
            organizedText = trimmed,
            previousOrganizedText = prev?.organizedText?.takeIf { it != trimmed } ?: prev?.previousOrganizedText,
            updatedAtMs = System.currentTimeMillis(),
        )
        tokenStore.saveStagedDraft(updated)
        _stagedDraft.value = updated
        _lastTranscript.value = trimmed
        dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = true))
        voiceUxLogger.log(
            category = VoiceUxLogger.CAT_DRAFT,
            event = "edited",
            sessionKey = key,
            metrics = mapOf("chars" to trimmed.length),
        )
        syncActiveBubble()
    }

    fun reorganizeCurrentDraft() {
        agentTurnRunner.reorganizeCurrentDraft()
    }

    fun revertDraftToPrevious() {
        val key = activeSessionKey() ?: return
        val cur = tokenStore.getStagedDraft(key) ?: return
        val prevText = cur.previousOrganizedText?.takeIf { it.isNotBlank() } ?: return
        val reverted = cur.copy(
            organizedText = prevText,
            previousOrganizedText = cur.organizedText,
            updatedAtMs = System.currentTimeMillis(),
        )
        tokenStore.saveStagedDraft(reverted)
        _stagedDraft.value = reverted
        _lastTranscript.value = prevText
        earconPlayer.play(Earcon.UNDO)
        voiceUxLogger.log(VoiceUxLogger.CAT_DRAFT, "reverted", key)
        syncActiveBubble()
    }

    fun readVoiceUxSummary(recentLimit: Int = 25): VoiceUxSummary =
        voiceUxLogger.readSummary(recentLimit)

    fun exportVoiceUxLog(): String = voiceUxLogger.exportJsonl()

    fun clearVoiceUxLog() {
        voiceUxLogger.clear()
    }

    fun onActiveSessionOrServerChanged() {
        gestureDetector.cancelPending()
        agentTurnRunner.cancelOrganize()
        val currentState = stateMachine.currentState
        if (currentState.isBusyWithTurn) {
            disarm()
        }
        val bound = resolveBoundSession()
        val nextKey = bound?.sessionKey
        val sessionChanged = loadedSessionKey != nextKey
        loadedSessionKey = nextKey
        if (sessionChanged) {
            _lastTranscript.value = null
            _lastReply.value = null
            _lastSpokenSummary.value = null
            _lastTtsInfo.value = null
        }
        val restored = restoreDraftForActiveSession()
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.bindSession(bound, forceClear = true)
        }
        if (stateMachine.currentState == PttState.ARMED_IDLE || stateMachine.currentState == PttState.STAGED_DRAFT) {
            dispatch(PttEvent.SyncDraftPresence(hasPersistedDraft = restored != null))
            updateForegroundNotification(stateMachine.currentState)
            if (bound != null && ::sessionResponseController.isInitialized) {
                sessionResponseController.setArmed(true)
                sessionResponseController.requestBackgroundSync(
                    reason = "session_switch_armed",
                    triggerAutoSpeak = false,
                    captureAutoSpeakAnchor = true,
                    forceFocusLatest = true,
                )
            } else {
                disarm()
            }
            return
        }
        refreshLatestTurn(forceClearOnSessionChange = true)
    }

    fun refreshLatestTurn(forceClearOnSessionChange: Boolean = true) {
        val bound = resolveBoundSession()
        if (bound == null) {
            if (forceClearOnSessionChange) {
                loadedSessionKey = null
                _lastTranscript.value = null
                _lastReply.value = null
                _lastSpokenSummary.value = null
                _lastTtsInfo.value = null
                _stagedDraft.value = null
                if (::sessionResponseController.isInitialized) {
                    sessionResponseController.bindSession(null, forceClear = true)
                }
            }
            return
        }

        val currentState = stateMachine.currentState
        if (currentState.isBusyWithTurn) {
            return
        }

        val nextKey = bound.sessionKey
        if (forceClearOnSessionChange && loadedSessionKey != null && loadedSessionKey != nextKey) {
            _lastTranscript.value = null
            _lastReply.value = null
            _lastSpokenSummary.value = null
            _lastTtsInfo.value = null
        }
        loadedSessionKey = nextKey
        val restoredDraft = tokenStore.getStagedDraft(nextKey)
        _stagedDraft.value = restoredDraft
        if (restoredDraft != null) {
            _lastTranscript.value = restoredDraft.organizedText
        }

        if (::sessionResponseController.isInitialized) {
            sessionResponseController.bindSession(bound, forceClear = forceClearOnSessionChange)
            sessionResponseController.requestBackgroundSync(
                reason = "refresh",
                triggerAutoSpeak = false,
                captureAutoSpeakAnchor = false,
                forceFocusLatest = false,
            )
        }
    }

    fun readPreviousMessage() {
        val current = stateMachine.currentState
        if (current == PttState.SPEAKING_TTS || current == PttState.STAGED_DRAFT_SPEAKING) {
            onSingleTap()
            return
        }
        val bound = resolveBoundSession()
        if (bound == null) {
            _lastError.value = "Connect to OpenChamber and select a session first"
            _statusText.value = _lastError.value.orEmpty()
            earconPlayer.play(Earcon.ERROR)
            return
        }
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.bindSession(bound, forceClear = false)
        }
        val wasDisarmed = current == PttState.DISARMED
        if (wasDisarmed) {
            promotedForStandaloneTts = true
        }
        val transition = dispatch(PttEvent.PlayLastReply)
        if (transition.current != PttState.SPEAKING_TTS) {
            if (wasDisarmed) {
                promotedForStandaloneTts = false
            }
            return
        }
        if (transition.previous == PttState.DISARMED) {
            runCatching {
                val fgIntent = Intent(this, PttForegroundService::class.java).apply {
                    action = ACTION_ENSURE_MEDIA_FOREGROUND
                }
                ContextCompat.startForegroundService(this, fgIntent)
                startForegroundWithState(
                    pttState = transition.current,
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }
        }
    }

    private fun onTranscriptReady(text: String? = null) {
        dispatch(PttEvent.TranscriptReady(text))
    }

    private fun onAgentCompleted(hasTts: Boolean = true) {
        dispatch(PttEvent.AgentCompleted(hasTts))
    }

    private fun onPermissionAsked(reason: String? = null) {
        dispatch(PttEvent.PermissionAsked(reason))
    }

    private fun onPermissionResolved() {
        dispatch(PttEvent.PermissionResolved)
    }

    private fun onTtsFinished() {
        dispatch(PttEvent.TtsFinished)
    }

    private fun onError(message: String? = null, isAuthExpired: Boolean = false) {
        dispatch(PttEvent.Error(message, isAuthExpired = isAuthExpired))
    }

    fun dispatch(event: PttEvent): PttTransition =
        dispatchInternal(event, requiredStates = null, requiredPinGeneration = null)
            ?: error("Unconstrained dispatch must return a transition")

    private fun dispatchInternal(
        event: PttEvent,
        requiredStates: Set<PttState>?,
        requiredPinGeneration: Long?,
    ): PttTransition? {
        val transition = synchronized(dispatchLock) {
            val current = stateMachine.currentState
            if (requiredStates != null && current !in requiredStates) {
                return null
            }
            if (requiredPinGeneration != null && !bluetoothAudioRouter.isCurrentPinGeneration(requiredPinGeneration)) {
                return null
            }
            val tr = stateMachine.dispatch(event)
            syncStateAndStatus(tr)
            applyEffects(tr)
            updateMediaSessionPlaybackState(tr.current)
            if (tr.changed) {
                updateForegroundNotification(tr.current)
            }
            _transitions.tryEmit(tr)
            tr
        }
        if (shouldCheckAutoSpeakAfterIdleReturn(transition) && ::sessionResponseController.isInitialized) {
            sessionResponseController.maybeTriggerAutoSpeakIfIdle()
        }
        return transition
    }

    private fun handleUnexpectedScoDrop(drop: ScoDropEvent) {
        val current = stateMachine.currentState
        when (resolveScoDropAction(current, drop)) {
            ScoDropAction.TRIGGER_STOP_SEND -> {
                gestureDetector.cancelPending()
                val tr = dispatchInternal(
                    PttEvent.SingleTap,
                    requiredStates = setOf(PttState.RECORDING),
                    requiredPinGeneration = drop.pinGeneration,
                )
                if (tr != null) {
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_GESTURE,
                        event = "sco_drop_stop",
                        sessionKey = activeSessionKey(),
                        metrics = mapOf("source" to drop.source.name, "state" to current.name),
                    )
                }
            }
            ScoDropAction.TRIGGER_BARGE_IN -> {
                gestureDetector.cancelPending()
                val tr = dispatchInternal(
                    PttEvent.SingleTap,
                    requiredStates = setOf(PttState.SPEAKING_TTS, PttState.STAGED_DRAFT_SPEAKING),
                    requiredPinGeneration = drop.pinGeneration,
                )
                if (tr != null) {
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_GESTURE,
                        event = "sco_drop_barge_in",
                        sessionKey = activeSessionKey(),
                        metrics = mapOf("source" to drop.source.name, "state" to current.name),
                    )
                }
            }
            ScoDropAction.RELEASE_ONLY -> {
                if (!bluetoothAudioRouter.isCurrentPinGeneration(drop.pinGeneration)) {
                    return
                }
                val reason = when {
                    drop.interruptedByFocusOrCall -> "sco_drop_interrupted"
                    !drop.btScoStillAvailable -> "sco_drop_bt_unavailable"
                    else -> "sco_drop_released"
                }
                bluetoothAudioRouter.releaseIfGeneration(drop.pinGeneration)
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_GESTURE,
                    event = reason,
                    sessionKey = activeSessionKey(),
                    metrics = mapOf("source" to drop.source.name, "state" to current.name),
                )
            }
            ScoDropAction.IGNORE -> Unit
        }
    }

    private fun resolveBoundSession(): BoundServerSession? {
        val activeServer = tokenStore.getActiveServer() ?: return null
        val serverUrl = activeServer.serverUrl
        val token = activeServer.token
        val pinnedSession = activeServer.lastPinnedSession
        if (serverUrl.isNullOrBlank() || token.isNullOrBlank() || pinnedSession == null) {
            return null
        }
        return BoundServerSession(
            serverId = activeServer.id,
            serverUrl = serverUrl,
            pinnedSession = pinnedSession,
            sessionKey = TokenStore.sessionKeyFor(activeServer.id, pinnedSession),
        )
    }

    private fun syncFromResponseFeed(feed: ResponseFeedState) {
        val focused = feed.focusedResponse
        _lastReply.value = focused?.assistantText
        _lastSpokenSummary.value = feed.focusedSpokenSummary
        if (_lastTranscript.value.isNullOrBlank() &&
            _stagedDraft.value == null &&
            !stateMachine.currentState.isBusyWithTurn &&
            focused?.triggerKind == TurnTriggerKind.USER_PROMPT &&
            !focused.triggerText.isNullOrBlank()
        ) {
            _lastTranscript.value = focused.triggerText
        }
        syncActiveBubble()
    }

    private fun activeSessionKey(): String? {
        val activeServer = tokenStore.getActiveServer() ?: return null
        val pinned = activeServer.lastPinnedSession ?: return null
        return TokenStore.sessionKeyFor(activeServer.id, pinned)
    }

    private fun restoreDraftForActiveSession(): StagedDraft? {
        val key = activeSessionKey()
        val draft = key?.let { tokenStore.getStagedDraft(it) }
        _stagedDraft.value = draft
        if (draft != null) {
            _lastTranscript.value = draft.organizedText
        }
        return draft
    }

    private fun syncStateAndStatus(transition: PttTransition) {
        val smError = stateMachine.lastError.value
        if (transition.event is PttEvent.Arm || transition.event is PttEvent.Disarm) {
            _lastError.value = smError
        } else if (smError != null) {
            _lastError.value = smError
        }

        if (transition.changed && transition.previous == PttState.PERMISSION_BLOCKED) {
            notificationBuilder.cancelPermissionAlertNotification()
        }

        if (transition.changed) {
            _recordingStartedAtElapsedMs.value = if (transition.current == PttState.RECORDING) {
                SystemClock.elapsedRealtime()
            } else {
                null
            }
            val nextStatus = when (transition.current) {
                PttState.ERROR -> smError?.let { "Error: $it" }
                    ?: notificationBuilder.statusTextForState(PttState.ERROR)
                PttState.PERMISSION_BLOCKED -> {
                    val tool = stateMachine.permissionPrompt.value ?: "unknown"
                    formatPermissionAlertSpeech(tool)
                }
                else -> notificationBuilder.statusTextForState(transition.current)
            }
            _statusText.value = nextStatus
            syncActiveBubble(nextStatus)
        }
    }

    private fun syncActiveBubble(statusSummary: String = _statusText.value) {
        val title = tokenStore.getPinnedSession()?.title ?: "OpenChamber Voice"
        val feed = if (::sessionResponseController.isInitialized) {
            sessionResponseController.feedState.value
        } else {
            null
        }
        val focused = feed?.focusedResponse
        val triggerPrompt = focused?.let { item ->
            val label = item.triggerLabel?.trim()?.takeIf { it.isNotEmpty() }
            val text = item.triggerText?.trim()?.takeIf { it.isNotEmpty() }
            when {
                label != null && text != null -> "$label: $text"
                text != null -> text
                label != null -> label
                else -> null
            }
        }
        val userPrompt = _lastTranscript.value?.takeIf { it.isNotBlank() } ?: triggerPrompt
        val rawReply = _lastReply.value?.takeIf { it.isNotBlank() }
            ?: focused?.assistantText?.takeIf { it.isNotBlank() }
        val unreadCount = feed?.unreadQueue?.size ?: 0
        val agentReply = when {
            unreadCount > 0 && rawReply != null -> "$unreadCount unread · $rawReply"
            unreadCount > 0 -> "$unreadCount unread"
            else -> rawReply
        }
        BubbleHelper.updateIfPosted(
            context = this,
            sessionTitle = title,
            statusSummary = statusSummary,
            userPrompt = userPrompt,
            agentReply = agentReply,
        )
    }

    private fun applyEffects(transition: PttTransition) {
        for (effect in transition.effects) {
            when (effect) {
                is PttEffect.PlayEarcon -> earconPlayer.play(effect.earcon)
                PttEffect.AcquireWakeAndWifiLocks -> acquireWakeAndWifiLocks()
                PttEffect.ReleaseWakeAndWifiLocks -> releaseWakeAndWifiLocks()
                PttEffect.PinBluetoothAudio -> bluetoothAudioRouter.configureForPtt(pinSco = pinBluetoothSco)
                PttEffect.ReleaseBluetoothAudio -> bluetoothAudioRouter.release()
                PttEffect.StartRecording -> startBurstRecording()
                PttEffect.StopRecording -> audioBurstRecorder.requestStop()
                PttEffect.DiscardRecording -> cancelBurstRecording()
                PttEffect.StopTts -> {
                    if (transition.previous == PttState.SPEAKING_TTS &&
                        transition.event is PttEvent.SingleTap &&
                        ::sessionResponseController.isInitialized
                    ) {
                        sessionResponseController.onUserBargeIn()
                    }
                    if (transition.previous == PttState.STAGED_DRAFT_SPEAKING) {
                        val evtName = if (transition.event is PttEvent.DoubleTap || transition.event is PttEvent.CommitDraft) {
                            "committed_during_preview"
                        } else {
                            "interrupted"
                        }
                        voiceUxLogger.log(
                            category = VoiceUxLogger.CAT_TTS,
                            event = evtName,
                            sessionKey = activeSessionKey(),
                            metrics = mapOf("kind" to AgentTurnRunner.TTS_KIND_DRAFT_PREVIEW),
                        )
                    }
                    stopTtsWithBargeInCooldown()
                }
                PttEffect.StartSendGraceTimer -> startSendGraceCountdown()
                PttEffect.CancelSendGraceTimer -> {
                    cancelSendGraceCountdown(
                        pulledBackToDraft = transition.current == PttState.STAGED_DRAFT &&
                            (transition.event is PttEvent.SingleTap ||
                                transition.event is PttEvent.DoubleTap ||
                                transition.event is PttEvent.PullBackToDraft),
                        discarded = transition.event is PttEvent.TripleTap || transition.event is PttEvent.DiscardCapture,
                    )
                }
                is PttEffect.OrganizeAndSpeakDraft -> agentTurnRunner.organizeAndSpeakDraft(effect.burst)
                is PttEffect.DispatchPrompt -> agentTurnRunner.dispatchPrompt(fromStagedDraft = effect.fromStagedDraft)
                PttEffect.ReadLastReply -> agentTurnRunner.readLastReply()
                PttEffect.SpeakPendingReplies -> agentTurnRunner.speakPendingReplies()
                PttEffect.ClearDraft -> {
                    val key = activeSessionKey()
                    if (key != null) {
                        tokenStore.clearStagedDraft(key)
                        voiceUxLogger.log(VoiceUxLogger.CAT_DRAFT, "discarded", key)
                    }
                    _stagedDraft.value = null
                    _pendingQuickTranscript.value = null
                }
            }
        }

        when (transition.current) {
            PttState.DISARMED,
            PttState.ERROR,
            -> {
                if (::sessionResponseController.isInitialized) {
                    sessionResponseController.setArmed(false)
                }
                cancelAllTurnJobs(stopTts = transition.effects.none { it is PttEffect.StopTts })
                notificationBuilder.cancelPermissionAlertNotification()
                releaseWakeAndWifiLocks()
                if (promotedForStandaloneTts) {
                    promotedForStandaloneTts = false
                    stopForegroundAndSelf()
                }
            }

            PttState.ARMED_IDLE,
            PttState.STAGED_DRAFT,
            -> {
                if (shouldCancelTurnOnIdleReturn(transition)) {
                    agentTurnRunner.cancelActiveTurnAndReadReply("Returned to ${transition.current.name}")
                }
                releaseWakeAndWifiLocks()
                if (promotedForStandaloneTts) {
                    promotedForStandaloneTts = false
                }
            }

            PttState.PERMISSION_BLOCKED -> releaseWakeAndWifiLocks()
            else -> Unit
        }
    }

    private fun validateReadyToArm(): String? {
        if (!hasRecordAudioPermission()) {
            return "Microphone permission (RECORD_AUDIO) is required"
        }
        if (tokenStore.getServerUrl().isNullOrBlank()) {
            return "Server URL is not configured"
        }
        if (tokenStore.getToken().isNullOrBlank()) {
            return "Not authenticated — connect to OpenChamber first"
        }
        if (tokenStore.getPinnedSession() == null) {
            return "Select a pinned session first"
        }
        return null
    }

    private fun stopTtsWithBargeInCooldown() {
        lastTtsStopElapsedMs = SystemClock.elapsedRealtime()
        activeSpokenMessageId = null
        agentTurnRunner.cancelSpeechJobs()
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.setSpeakingMessage(null)
        }
        if (::ttsPlayer.isInitialized) {
            ttsPlayer.stop()
        }
    }

    private fun startBurstRecording() {
        val activeServer = tokenStore.getActiveServer()
        val serverId = activeServer?.id
        val serverUrl = activeServer?.serverUrl
        val token = activeServer?.token
        val pinnedSession = activeServer?.lastPinnedSession
        if (serverId == null || serverUrl.isNullOrBlank() || token.isNullOrBlank() || pinnedSession == null) {
            onError("Server connection or pinned session is missing")
            return
        }
        if (!hasRecordAudioPermission()) {
            onError("Microphone permission is required")
            return
        }

        _pendingQuickTranscript.value = null
        audioBurstRecorder.startBurst(
            serverId = serverId,
            serverUrl = serverUrl,
            pinnedSession = pinnedSession,
            onStoppedEarly = {
                if (stateMachine.currentState.isTranscribing) {
                    onTranscriptReady("")
                }
            },
            onOutcome = { sid, url, session, outcome ->
                val rawTranscript = outcome?.transcript
                if (rawTranscript.isNullOrBlank()) {
                    if (stateMachine.currentState.isTranscribing) {
                        _statusText.value = "No speech detected"
                        onTranscriptReady("")
                    }
                    return@startBurst
                }

                if (stateMachine.currentState == PttState.TRANSCRIBING_STAGE) {
                    onTranscriptReady(rawTranscript)
                    return@startBurst
                }

                if (stateMachine.currentState != PttState.TRANSCRIBING_SEND) {
                    return@startBurst
                }

                agentTurnRunner.finalizeQuickTranscript(
                    serverId = sid,
                    serverUrl = url,
                    sessionKey = TokenStore.sessionKeyFor(sid, session),
                    pinnedSession = session,
                    rawTranscript = rawTranscript,
                )
            },
            onFailure = { failedServerId, t ->
                handleUnauthorizedIfNeeded(failedServerId, t)
                onError(
                    message = t.message?.takeIf { it.isNotBlank() } ?: "Voice capture failed",
                    isAuthExpired = isUnauthorizedError(t),
                )
            },
        )
    }

    private fun cancelBurstRecording() {
        _pendingQuickTranscript.value = null
        if (::audioBurstRecorder.isInitialized) {
            audioBurstRecorder.cancel("Recording discarded")
        }
    }

    private fun cancelAllTurnJobs(stopTts: Boolean) {
        sendGraceJob?.cancel()
        sendGraceJob = null
        _sendGraceRemainingMs.value = null
        if (::audioBurstRecorder.isInitialized) {
            audioBurstRecorder.cancel("PTT cancelled")
        }
        if (::agentTurnRunner.isInitialized) {
            agentTurnRunner.cancelAll()
        }
        if (stopTts && ::ttsPlayer.isInitialized) {
            lastTtsStopElapsedMs = SystemClock.elapsedRealtime()
            ttsPlayer.stop()
        }
    }

    private fun startSendGraceCountdown() {
        sendGraceJob?.cancel()
        sendGraceStartedAtMs = SystemClock.elapsedRealtime()
        sendGraceJob = serviceScope.launch {
            var remaining = SEND_GRACE_MS
            while (remaining > 0L && stateMachine.currentState == PttState.SEND_GRACE) {
                _sendGraceRemainingMs.value = remaining
                val step = minOf(250L, remaining)
                delay(step)
                remaining = (SEND_GRACE_MS - (SystemClock.elapsedRealtime() - sendGraceStartedAtMs))
                    .coerceAtLeast(0L)
            }
            _sendGraceRemainingMs.value = null
            if (stateMachine.currentState == PttState.SEND_GRACE) {
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_SEND_GRACE,
                    event = "committed",
                    sessionKey = activeSessionKey(),
                    metrics = mapOf("elapsedMs" to SEND_GRACE_MS),
                )
                dispatch(PttEvent.GraceTimerExpired)
            }
        }
    }

    private fun cancelSendGraceCountdown(pulledBackToDraft: Boolean, discarded: Boolean) {
        sendGraceJob?.cancel()
        sendGraceJob = null
        val elapsed = (SystemClock.elapsedRealtime() - sendGraceStartedAtMs).coerceAtLeast(0L)
        _sendGraceRemainingMs.value = null
        val pending = _pendingQuickTranscript.value?.trim()?.takeIf { it.isNotEmpty() }
        _pendingQuickTranscript.value = null

        if (pulledBackToDraft) {
            val key = activeSessionKey()
            if (key != null && pending != null) {
                val existing = tokenStore.getStagedDraft(key)
                val updated = if (existing != null) {
                    existing.copy(
                        rawBursts = existing.rawBursts + pending,
                        organizedText = "${existing.organizedText}\n\n$pending",
                        previousOrganizedText = existing.organizedText,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                } else {
                    StagedDraft(
                        sessionKey = key,
                        rawBursts = listOf(pending),
                        organizedText = pending,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                tokenStore.saveStagedDraft(updated)
                _stagedDraft.value = updated
                _lastTranscript.value = updated.organizedText
            }
            voiceUxLogger.log(
                category = VoiceUxLogger.CAT_SEND_GRACE,
                event = "pulled_back",
                sessionKey = key,
                metrics = mapOf("elapsedMs" to elapsed),
            )
        } else if (discarded) {
            voiceUxLogger.log(
                category = VoiceUxLogger.CAT_SEND_GRACE,
                event = "discarded",
                sessionKey = activeSessionKey(),
                metrics = mapOf("elapsedMs" to elapsed),
            )
        }
    }

    private fun acquireWakeAndWifiLocks() {
        runCatching {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        runCatching {
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
            }
        }
    }

    private fun releaseWakeAndWifiLocks() {
        runCatching {
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
            }
        }
        runCatching {
            if (::wifiLock.isInitialized && wifiLock.isHeld) {
                wifiLock.release()
            }
        }
    }

    private fun initMediaSession() {
        val mbrComponent = ComponentName(this, MediaButtonReceiver::class.java)
        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = mbrComponent
        }
        val mbrPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mbrIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val session = MediaSessionCompat(this, MEDIA_SESSION_TAG, mbrComponent, mbrPendingIntent).apply {
            setPlaybackToLocal(android.media.AudioManager.STREAM_MUSIC)
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                        val keyEvent = IntentCompat.getParcelableExtra(
                            mediaButtonEvent,
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java,
                        )
                        if (keyEvent != null) {
                            val rawInput = when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY,
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                -> RawMediaInput.PRIMARY_TAP
                                KeyEvent.KEYCODE_MEDIA_NEXT -> RawMediaInput.NEXT
                                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> RawMediaInput.PREVIOUS
                                KeyEvent.KEYCODE_MEDIA_STOP -> RawMediaInput.STOP
                                else -> null
                            }
                            if (rawInput != null) {
                                if (keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0) {
                                    val mode = MediaButtonGestureDetector.groupingModeFor(stateMachine.currentState)
                                    gestureDetector.onInput(rawInput, mode)
                                }
                                return true
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }

                    override fun onPlay() {
                        val mode = MediaButtonGestureDetector.groupingModeFor(stateMachine.currentState)
                        gestureDetector.onInput(RawMediaInput.PRIMARY_TAP, mode)
                    }

                    override fun onPause() {
                        val mode = MediaButtonGestureDetector.groupingModeFor(stateMachine.currentState)
                        gestureDetector.onInput(RawMediaInput.PRIMARY_TAP, mode)
                    }

                    override fun onSkipToNext() {
                        val mode = MediaButtonGestureDetector.groupingModeFor(stateMachine.currentState)
                        gestureDetector.onInput(RawMediaInput.NEXT, mode)
                    }

                    override fun onSkipToPrevious() {
                        val mode = MediaButtonGestureDetector.groupingModeFor(stateMachine.currentState)
                        gestureDetector.onInput(RawMediaInput.PREVIOUS, mode)
                    }

                    override fun onStop() {
                        val mode = MediaButtonGestureDetector.groupingModeFor(stateMachine.currentState)
                        gestureDetector.onInput(RawMediaInput.STOP, mode)
                    }
                },
            )
            isActive = true
        }
        mediaSession = session
        updateMediaSessionPlaybackState(stateMachine.currentState)
    }

    private fun updateMediaSessionPlaybackState(pttState: PttState) {
        val session = mediaSession ?: return
        val playbackStateCode = when (pttState) {
            PttState.RECORDING,
            PttState.TRANSCRIBING_SEND,
            PttState.TRANSCRIBING_STAGE,
            PttState.SEND_GRACE,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            PttState.STAGED_DRAFT_SPEAKING,
            -> PlaybackStateCompat.STATE_PLAYING

            PttState.ARMED_IDLE,
            PttState.STAGED_DRAFT,
            -> PlaybackStateCompat.STATE_PAUSED

            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            PttState.DISARMED,
            -> PlaybackStateCompat.STATE_STOPPED
        }
        val stateObj = PlaybackStateCompat.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(playbackStateCode, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(stateObj)
    }

    private fun startForegroundWithState(
        pttState: PttState,
        serviceType: Int = resolveForegroundServiceType(),
    ) {
        val notification = notificationBuilder.buildNotification(pttState, promotedForStandaloneTts)
        ServiceCompat.startForeground(this, PttNotificationBuilder.NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateForegroundNotification(pttState: PttState) {
        if (pttState == PttState.DISARMED) {
            return
        }
        if (pttState == PttState.RECORDING && hasRecordAudioPermission()) {
            val notification = notificationBuilder.buildNotification(pttState, promotedForStandaloneTts)
            ServiceCompat.startForeground(
                this,
                PttNotificationBuilder.NOTIFICATION_ID,
                notification,
                resolveForegroundServiceType(),
            )
        } else {
            notificationBuilder.notifyServiceState(pttState, promotedForStandaloneTts)
        }
    }

    private fun resolveForegroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasRecordAudioPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun handleUnauthorizedIfNeeded(serverId: String, t: Throwable) {
        if (isUnauthorizedError(t)) {
            tokenStore.clearAuth(serverId)
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    internal enum class ScoDropAction {
        TRIGGER_STOP_SEND,
        TRIGGER_BARGE_IN,
        RELEASE_ONLY,
        IGNORE,
    }

    companion object {
        const val ACTION_ARM = "com.openchamber.ptt.action.ARM"
        const val ACTION_DISARM = "com.openchamber.ptt.action.DISARM"
        const val ACTION_TOGGLE_PTT = "com.openchamber.ptt.action.TOGGLE_PTT"
        const val ACTION_STAGE_CAPTURE = "com.openchamber.ptt.action.STAGE_CAPTURE"
        const val ACTION_DISCARD_CAPTURE = "com.openchamber.ptt.action.DISCARD_CAPTURE"
        const val ACTION_COMMIT_DRAFT = "com.openchamber.ptt.action.COMMIT_DRAFT"
        const val ACTION_PULL_BACK_DRAFT = "com.openchamber.ptt.action.PULL_BACK_DRAFT"
        const val ACTION_STOP_SERVICE = "com.openchamber.ptt.action.STOP_SERVICE"
        const val ACTION_ENSURE_MEDIA_FOREGROUND = "com.openchamber.ptt.action.ENSURE_MEDIA_FOREGROUND"
        const val EXTRA_PIN_SCO = "com.openchamber.ptt.extra.PIN_SCO"

        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_DURATION_MS = 200
        const val CHUNK_SIZE_BYTES = SAMPLE_RATE_HZ * 2 * CHUNK_DURATION_MS / 1000
        const val MAX_UTTERANCE_MS = 300_000L
        const val MAX_UTTERANCE_WARNING_MS = 285_000L
        const val MAX_FALLBACK_PCM_BYTES = 12 * 1024 * 1024
        const val SEND_GRACE_MS = 2_500L
        const val BARGE_IN_COOLDOWN_MS = 150L
        const val PERMISSION_ALERT_DELAY_MS = 4_000L
        const val VOICE_PROMPT_PREFIX = "[voice]"

        private const val MEDIA_SESSION_TAG = "OpenChamberPttMediaSession"
        private const val WAKE_LOCK_TAG = "OpenChamberPtt::PttWakeLock"
        private const val WIFI_LOCK_TAG = "OpenChamberPtt::PttWifiLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 1000L

        private const val MEDIA_SESSION_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

        fun startIntent(context: Context, action: String, pinSco: Boolean? = null): Intent =
            Intent(context, PttForegroundService::class.java).apply {
                this.action = action
                if (pinSco != null) {
                    putExtra(EXTRA_PIN_SCO, pinSco)
                }
            }

        internal fun shouldCancelTurnOnIdleReturn(transition: PttTransition): Boolean {
            if (transition.current != PttState.ARMED_IDLE && transition.current != PttState.STAGED_DRAFT) {
                return false
            }
            return (transition.previous == PttState.PERMISSION_BLOCKED && transition.event is PttEvent.Arm) ||
                (transition.previous == PttState.SPEAKING_TTS && transition.event is PttEvent.SingleTap)
        }

        internal fun shouldCheckAutoSpeakAfterIdleReturn(transition: PttTransition): Boolean {
            if (transition.current != PttState.ARMED_IDLE && transition.current != PttState.STAGED_DRAFT) {
                return false
            }
            if (transition.previous == PttState.SPEAKING_TTS && transition.event is PttEvent.SingleTap) {
                return false
            }
            if (transition.previous == PttState.DISARMED && transition.event is PttEvent.Arm) {
                return false
            }
            return true
        }

        internal fun isUnauthorizedError(t: Throwable): Boolean {
            val statusCode = when (t) {
                is SessionApiException -> t.statusCode
                is TtsException -> t.statusCode
                is DictationException -> t.statusCode
                is PromptOrganizerException -> t.statusCode
                else -> null
            }
            return statusCode == 401
        }

        internal fun formatVoicePrompt(transcript: String): String {
            val cleaned = transcript.trim()
            if (cleaned.isEmpty()) return ""
            return "$VOICE_PROMPT_PREFIX\n$cleaned"
        }

        internal fun formatPermissionAlertSpeech(tool: String): String {
            val cleaned = tool.trim().ifEmpty { "unknown" }
            return "Tool permission needed on screen for $cleaned"
        }

        internal fun formatSpeechSessionLabel(session: PinnedSession): String? {
            val title = session.title.trim().takeIf { it.isNotEmpty() }
            if (title != null) return title
            return session.directory
                .trim()
                .trimEnd('/', '\\')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        internal fun resolveScoDropAction(
            state: PttState,
            drop: ScoDropEvent,
        ): ScoDropAction {
            if (drop.interruptedByFocusOrCall) {
                return when (state) {
                    PttState.RECORDING,
                    PttState.SPEAKING_TTS,
                    PttState.STAGED_DRAFT_SPEAKING,
                    -> ScoDropAction.RELEASE_ONLY
                    else -> ScoDropAction.IGNORE
                }
            }
            if (!drop.btScoStillAvailable) {
                return ScoDropAction.RELEASE_ONLY
            }
            return when (state) {
                PttState.RECORDING -> ScoDropAction.TRIGGER_STOP_SEND
                PttState.SPEAKING_TTS,
                PttState.STAGED_DRAFT_SPEAKING,
                -> ScoDropAction.TRIGGER_BARGE_IN
                else -> ScoDropAction.RELEASE_ONLY
            }
        }

        internal fun remainingBargeInCooldownMs(
            lastTtsStopElapsedMs: Long,
            nowElapsedMs: Long,
            cooldownMs: Long = BARGE_IN_COOLDOWN_MS,
        ): Long {
            if (lastTtsStopElapsedMs <= 0L) return 0L
            val elapsed = nowElapsedMs - lastTtsStopElapsedMs
            if (elapsed < 0L) return cooldownMs
            return (cooldownMs - elapsed).coerceAtLeast(0L)
        }
    }
}
