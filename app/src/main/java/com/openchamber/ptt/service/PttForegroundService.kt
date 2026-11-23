package com.openchamber.ptt.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
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
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import com.openchamber.ptt.audio.AudioOutputCoordinator
import com.openchamber.ptt.audio.BluetoothAudioRouter
import com.openchamber.ptt.audio.CueEvent
import com.openchamber.ptt.audio.CuePackWarmState
import com.openchamber.ptt.audio.CuePlayer
import com.openchamber.ptt.audio.EarconPlayer
import com.openchamber.ptt.audio.RecordingVolumeKeyController
import com.openchamber.ptt.audio.ScoDropEvent
import com.openchamber.ptt.audio.TelecomCallStartResult
import com.openchamber.ptt.audio.TelecomCaptureCoordinator
import com.openchamber.ptt.audio.VocalCuePackWarmer
import com.openchamber.ptt.audio.VolumeKeyAction
import com.openchamber.ptt.audio.VolumeKeyEpisode
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.diagnostics.AudioDebugCategory
import com.openchamber.ptt.diagnostics.AudioDebugOutcome
import com.openchamber.ptt.diagnostics.AudioDebugRegistry
import com.openchamber.ptt.diagnostics.AudioPlatformDiagnosticMonitor
import com.openchamber.ptt.logging.VoiceUxLogger
import java.util.concurrent.atomic.AtomicLong
import com.openchamber.ptt.logging.VoiceUxSummary
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.session.SessionApiException
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.TtsCache
import com.openchamber.ptt.tts.TtsCacheStats
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsException
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.TtsPlayer
import com.openchamber.ptt.tts.VoiceRole
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
    val cuePackWarmState: StateFlow<CuePackWarmState>
        get() = vocalCuePackWarmer.state

    var pinBluetoothSco: Boolean
        get() = _pinBluetoothSco.value
        set(value) {
            if (_pinBluetoothSco.value != value) {
                _pinBluetoothSco.value = value
                if (::tokenStore.isInitialized && tokenStore.getPinBluetoothSco() != value) {
                    tokenStore.setPinBluetoothSco(value)
                }
                if (::serviceScope.isInitialized) {
                    updateDiagnosticLiveStatus()
                }
            }
        }

    private lateinit var audioOutputCoordinator: AudioOutputCoordinator
    private lateinit var earconPlayer: EarconPlayer
    private lateinit var vocalCuePackWarmer: VocalCuePackWarmer
    private lateinit var cuePlayer: CuePlayer
    private lateinit var bluetoothAudioRouter: BluetoothAudioRouter
    private lateinit var telecomCaptureCoordinator: TelecomCaptureCoordinator
    private lateinit var recordingVolumeKeyController: RecordingVolumeKeyController
    private lateinit var recordingMirrorController: RecordingMirrorNotificationController
    private lateinit var audioDiagnosticMonitor: AudioPlatformDiagnosticMonitor
    private lateinit var gestureDetector: MediaButtonGestureDetector
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var notificationBuilder: PttNotificationBuilder
    private lateinit var powerManager: PowerManager
    private var screenStateReceiver: BroadcastReceiver? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private lateinit var tokenStore: TokenStore
    private lateinit var sessionClient: SessionClient
    private lateinit var ttsCache: TtsCache
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var voiceUxLogger: VoiceUxLogger
    private lateinit var serviceScope: CoroutineScope
    private lateinit var audioBurstRecorder: AudioBurstRecorder
    private lateinit var sessionResponseController: SessionResponseController
    private lateinit var agentTurnRunner: AgentTurnRunner

    private var sendGraceJob: Job? = null
    private var previewJob: Job? = null
    private var loadedSessionKey: String? = null

    @Volatile
    private var sendGraceStartedAtMs: Long = 0L

    @Volatile
    private var promotedForStandaloneTts: Boolean = false

    @Volatile
    private var lastTtsStopElapsedMs: Long = 0L

    private val captureSeqCounter = AtomicLong(0L)

    @Volatile
    private var activeBurstUseTelecom: Boolean = false

    @Volatile
    private var activeTelecomCallGeneration: Long? = null

    @Volatile
    private var diagnosticScoConfirmed: Boolean = false

    @Volatile
    private var lastMediaSessionPlaybackState: Int = PlaybackStateCompat.STATE_NONE

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        bluetoothAudioRouter = BluetoothAudioRouter(this).apply {
            onDiagnosticEvent = { event, outcome, details ->
                if (details["reason"] == "sco_confirmed") {
                    diagnosticScoConfirmed = true
                } else if (!isScoPinned) {
                    diagnosticScoConfirmed = false
                }
                val category = when (event) {
                    "onAudioFocusChange" -> AudioDebugCategory.AUDIO_FOCUS
                    "handleScoAudioStateUpdated" -> AudioDebugCategory.BT_HEADSET
                    else -> AudioDebugCategory.AUDIO_ROUTE
                }
                recordComponentDiagnostic(category, event, outcome, details)
                updateDiagnosticLiveStatus()
            }
        }
        voiceUxLogger = VoiceUxLogger(applicationContext)
        telecomCaptureCoordinator = TelecomCaptureCoordinator(
            context = applicationContext,
            scope = serviceScope,
            onRemoteHangupStop = { gen ->
                gestureDetector.cancelPending()
                val hangupAction = tokenStore.getTelecomHangupAction()
                val stopEvent = eventForTelecomHangupAction(hangupAction)
                val tr = dispatchInternal(
                    stopEvent,
                    requiredStates = setOf(PttState.RECORDING),
                    requiredPinGeneration = null,
                    requiredTelecomGeneration = gen,
                )
                if (tr != null) {
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_GESTURE,
                        event = "telecom_remote_hangup_stop",
                        sessionKey = activeSessionKey(),
                        metrics = mapOf(
                            "gen" to gen,
                            "state" to tr.previous.name,
                            "action" to hangupAction.storageValue,
                        ),
                    )
                    AudioDebugRegistry.shared.record(
                        category = AudioDebugCategory.TELECOM,
                        event = "REMOTE_HANGUP_STOP",
                        outcome = AudioDebugOutcome.ACCEPTED,
                        pttState = tr.current.name,
                        details = mapOf(
                            "generation" to gen.toString(),
                            "action" to hangupAction.storageValue,
                        ),
                    )
                } else {
                    AudioDebugRegistry.shared.record(
                        category = AudioDebugCategory.TELECOM,
                        event = "REMOTE_HANGUP_STOP",
                        outcome = AudioDebugOutcome.IGNORED,
                        pttState = stateMachine.currentState.name,
                        details = mapOf(
                            "generation" to gen.toString(),
                            "action" to hangupAction.storageValue,
                            "reason" to "stale_gen_or_not_recording",
                        ),
                    )
                }
            },
            onCellularOrSystemInterruption = { gen ->
                gestureDetector.cancelPending()
                val tr = dispatchInternal(
                    PttEvent.StageCapture,
                    requiredStates = setOf(PttState.RECORDING),
                    requiredPinGeneration = null,
                    requiredTelecomGeneration = gen,
                )
                if (tr != null) {
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_GESTURE,
                        event = "telecom_cellular_interruption_stage",
                        sessionKey = activeSessionKey(),
                        metrics = mapOf("gen" to gen, "state" to tr.previous.name),
                    )
                    AudioDebugRegistry.shared.record(
                        category = AudioDebugCategory.TELECOM,
                        event = "CELLULAR_INTERRUPTION_STAGE",
                        outcome = AudioDebugOutcome.ACCEPTED,
                        pttState = tr.current.name,
                        details = mapOf("generation" to gen),
                    )
                } else {
                    AudioDebugRegistry.shared.record(
                        category = AudioDebugCategory.TELECOM,
                        event = "CELLULAR_INTERRUPTION_STAGE",
                        outcome = AudioDebugOutcome.IGNORED,
                        pttState = stateMachine.currentState.name,
                        details = mapOf("generation" to gen, "reason" to "stale_gen_or_not_recording"),
                    )
                }
            },
        ).apply {
            onDiagnosticEvent = { event, outcome, details ->
                recordComponentDiagnostic(AudioDebugCategory.TELECOM, event, outcome, details)
                updateDiagnosticLiveStatus()
            }
        }
        audioOutputCoordinator = AudioOutputCoordinator()
        earconPlayer = EarconPlayer(
            context = applicationContext,
            useCommunicationUsage = ::shouldUseCommunicationAudioUsage,
            onCuePlaybackStarted = { durationMs, useComm ->
                audioOutputCoordinator.onCueStarted(durationMs, useComm)
            },
            onCuePlaybackStopped = { useComm ->
                audioOutputCoordinator.onCueStopped(useComm)
            },
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationBuilder = PttNotificationBuilder(this, notificationManager)
        notificationBuilder.createNotificationChannels()
        recordingMirrorController = RecordingMirrorNotificationController(
            context = applicationContext,
            notificationBuilder = notificationBuilder,
            onDiagnosticEvent = { event, outcome, details ->
                recordComponentDiagnostic(AudioDebugCategory.NOTIFICATION, event, outcome, details)
            },
        )
        recordingMirrorController.onServiceCreated()
        RecordingNotificationActionReceiver.onDiagnosticEvent = { event, outcome, details ->
            recordComponentDiagnostic(AudioDebugCategory.NOTIFICATION, event, outcome, details)
        }
        RecordingNotificationActionReceiver.onStopRecordingRequested = {
            if (stateMachine.currentState == PttState.RECORDING) {
                gestureDetector.cancelPending()
                val tr = dispatchInternal(
                    PttEvent.SingleTap,
                    requiredStates = setOf(PttState.RECORDING),
                    requiredPinGeneration = null,
                )
                if (tr != null) {
                    voiceUxLogger.log(
                        category = VoiceUxLogger.CAT_GESTURE,
                        event = "garmin_mirror_stop",
                        sessionKey = activeSessionKey(),
                        metrics = mapOf("state" to tr.previous.name),
                    )
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        tokenStore = TokenStore(applicationContext)
        _pinBluetoothSco.value = tokenStore.getPinBluetoothSco()
        val httpClient = OpenChamberHttp.createClient(
            tokenForUrlProvider = { url -> tokenStore.getTokenForUrl(url) },
            directoryForUrlProvider = { url -> tokenStore.getDirectoryForUrl(url) },
        )
        val dictationClient = DictationClient(httpClient)
        val mlKitSttClient = MlKitSttClient()
        sessionClient = SessionClient(httpClient)
        val eventStreamClient = EventStreamClient(httpClient)
        ttsCache = TtsCache(applicationContext)
        val ttsClient = TtsClient(httpClient, ttsCache = ttsCache)
        val promptOrganizer = PromptOrganizer(httpClient)
        val voiceControlPlane = VoiceControlPlane(httpClient)
        ttsPlayer = TtsPlayer(
            context = applicationContext,
            ttsClient = ttsClient,
            useCommunicationUsage = ::shouldUseCommunicationAudioUsage,
            pipelineProvider = { tokenStore.getEffectiveTtsPipeline() },
            roleProfilesProvider = { tokenStore.getEffectiveRoleVoiceProfiles() },
            inputModeProvider = { tokenStore.getEffectiveTtsInputMode() },
            detailLevelProvider = { tokenStore.getEffectiveSpokenDetailLevel() },
            audioOutputCoordinator = audioOutputCoordinator,
            onStatusEvent = { info -> _lastTtsInfo.value = info },
            onSpokenTextSynthesizedForMessage = { msgId, spoken ->
                val resolvedMsgId = msgId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: sessionResponseController.feedState.value.speakingMessageId
                if (resolvedMsgId != null) {
                    sessionResponseController.recordSpokenSummary(resolvedMsgId, spoken)
                    if (sessionResponseController.feedState.value.focusedResponse?.messageId == resolvedMsgId) {
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
                        "role" to stats.role.name,
                        "voice" to stats.voiceId,
                        "inputMode" to stats.inputMode.name,
                        "prepareMs" to stats.prepareMs,
                        "rawChars" to stats.rawChars,
                        "spokenChars" to stats.spokenChars,
                        "engine" to stats.engine.name,
                        "prerollMs" to stats.prerollMs,
                        "textCacheHit" to stats.textCacheHit,
                        "audioCacheHit" to stats.audioCacheHit,
                    ),
                )
            },
            onLatencyBridgeNeeded = { bridgeKind ->
                if (::cuePlayer.isInitialized) {
                    cuePlayer.play(
                        if (bridgeKind == TtsPlayer.BRIDGE_KIND_MANUAL_REPLY) {
                            CueEvent.READING_REPLY
                        } else {
                            CueEvent.REPLY_PREPARING
                        },
                    )
                }
            },
        )

        vocalCuePackWarmer = VocalCuePackWarmer(
            scope = serviceScope,
            ttsClient = ttsClient,
            ttsCache = ttsCache,
            serverUrlProvider = { tokenStore.getServerUrl() },
            cueModeProvider = { tokenStore.getEffectiveAudioCueMode() },
            pipelineProvider = { tokenStore.getEffectiveTtsPipeline() },
            roleProfilesProvider = { tokenStore.getEffectiveRoleVoiceProfiles() },
            availableMlKitVoicesProvider = { ttsPlayer.mlKitTtsSpeaker?.availableVoices.orEmpty() },
            availableSystemVoicesProvider = { ttsPlayer.systemTtsSpeaker.availableVoices },
            voiceUxLogger = voiceUxLogger,
        )

        cuePlayer = CuePlayer(
            scope = serviceScope,
            earconPlayer = earconPlayer,
            ttsCache = ttsCache,
            warmer = vocalCuePackWarmer,
            serverUrlProvider = { tokenStore.getServerUrl() },
            cueModeProvider = { tokenStore.getEffectiveAudioCueMode() },
            pipelineProvider = { tokenStore.getEffectiveTtsPipeline() },
            roleProfilesProvider = { tokenStore.getEffectiveRoleVoiceProfiles() },
            availableMlKitVoicesProvider = { ttsPlayer.mlKitTtsSpeaker?.availableVoices.orEmpty() },
            availableSystemVoicesProvider = { ttsPlayer.systemTtsSpeaker.availableVoices },
            useCommunicationUsage = ::shouldUseCommunicationAudioUsage,
            isTtsAudibleProvider = { ttsPlayer.isAudibleOutputActive },
            audioOutputCoordinator = audioOutputCoordinator,
            mlKitTtsSpeaker = ttsPlayer.mlKitTtsSpeaker,
            systemTtsSpeaker = ttsPlayer.systemTtsSpeaker,
            voiceUxLogger = voiceUxLogger,
            sessionKeyProvider = { activeSessionKey() },
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
            onPlayWarningCue = { cuePlayer.play(CueEvent.MAX_DURATION_WARNING) },
            onMaxCapAutoStage = { stageOnMaxCap() },
            onPartialTranscript = { partial -> _lastTranscript.value = partial },
            onStatusUpdate = { msg -> _statusText.value = msg },
            prepareCaptureTransport = { _ -> prepareCaptureTransportForBurst() },
            onPlayDeferredStartCue = {
                if (::cuePlayer.isInitialized) {
                    cuePlayer.play(CueEvent.REC_START)
                }
            },
            onCaptureRouteResolved = { source, deviceType, productName ->
                AudioDebugRegistry.shared.record(
                    category = AudioDebugCategory.RECORDING_CONFIG,
                    event = "AUDIO_RECORD_ROUTED",
                    outcome = AudioDebugOutcome.STATE_CHANGE,
                    pttState = stateMachine.currentState.name,
                    details = buildMap {
                        put("source", audioSourceLabel(source))
                        deviceType?.let { put("deviceType", audioDeviceTypeLabel(it)) }
                        productName?.takeIf { it.isNotBlank() }?.let { put("deviceName", it) }
                    },
                )
            },
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
            lookupCachedSpokenSummary = { rawText ->
                val mode = tokenStore.getEffectiveTtsInputMode()
                if (mode == TtsInputMode.SUMMARIZED) {
                    val detail = tokenStore.getEffectiveSpokenDetailLevel()
                    ttsCache.getPreparedText(rawText, mode, detail)
                } else {
                    null
                }
            },
        )

        var lastEffectiveServerId = tokenStore.getActiveServer()?.id
        var lastEffectiveInputMode = tokenStore.getEffectiveTtsInputMode(lastEffectiveServerId)
        var lastEffectiveDetailLevel = tokenStore.getEffectiveSpokenDetailLevel(lastEffectiveServerId)
        var lastEffectiveCueMode = tokenStore.getEffectiveAudioCueMode(lastEffectiveServerId)
        var lastEffectivePipeline = tokenStore.getEffectiveTtsPipeline(lastEffectiveServerId)
        var lastEffectiveRoles = tokenStore.getEffectiveRoleVoiceProfiles(lastEffectiveServerId)
        var lastControlConfig = tokenStore.getControlSchemeConfig()
        serviceScope.launch {
            tokenStore.changes.collect {
                val curServerId = tokenStore.getActiveServer()?.id
                val curMode = tokenStore.getEffectiveTtsInputMode(curServerId)
                val curDetail = tokenStore.getEffectiveSpokenDetailLevel(curServerId)
                val curCueMode = tokenStore.getEffectiveAudioCueMode(curServerId)
                val curPipeline = tokenStore.getEffectiveTtsPipeline(curServerId)
                val curRoles = tokenStore.getEffectiveRoleVoiceProfiles(curServerId)
                val curControlConfig = tokenStore.getControlSchemeConfig()
                val serverChanged = curServerId != lastEffectiveServerId
                val summaryConfigChanged = serverChanged ||
                    curMode != lastEffectiveInputMode ||
                    curDetail != lastEffectiveDetailLevel
                val cueConfigChanged = serverChanged ||
                    curCueMode != lastEffectiveCueMode ||
                    curPipeline != lastEffectivePipeline ||
                    curRoles != lastEffectiveRoles
                val controlConfigChanged = curControlConfig != lastControlConfig
                val tapModeChanged = curControlConfig.headsetTapMode != lastControlConfig.headsetTapMode

                lastEffectiveServerId = curServerId
                lastEffectiveInputMode = curMode
                lastEffectiveDetailLevel = curDetail
                lastEffectiveCueMode = curCueMode
                lastEffectivePipeline = curPipeline
                lastEffectiveRoles = curRoles
                lastControlConfig = curControlConfig

                if (_pinBluetoothSco.value != curControlConfig.pinBluetoothSco) {
                    _pinBluetoothSco.value = curControlConfig.pinBluetoothSco
                }
                if (tapModeChanged && ::gestureDetector.isInitialized) {
                    gestureDetector.cancelPending()
                }
                if (summaryConfigChanged) {
                    sessionResponseController.refreshFocusedSpokenSummaryFromCache()
                }
                if (cueConfigChanged) {
                    vocalCuePackWarmer.ensureWarmedAsync()
                }
                synchronized(dispatchLock) {
                    syncMediaSessionAndVolumeControlsLocked(stateMachine.currentState)
                }
                if (::recordingMirrorController.isInitialized) {
                    recordingMirrorController.sync(
                        isRecording = stateMachine.currentState == PttState.RECORDING,
                        featureEnabled = curControlConfig.mirrorRecordingNotification,
                        sessionTitle = tokenStore.getPinnedSession()?.title,
                    )
                }
                if (controlConfigChanged && stateMachine.currentState != PttState.DISARMED) {
                    updateForegroundNotification(stateMachine.currentState)
                }
                updateDiagnosticLiveStatus()
            }
        }

        agentTurnRunner = AgentTurnRunner(
            scope = serviceScope,
            tokenStore = tokenStore,
            sessionClient = sessionClient,
            eventStreamClient = eventStreamClient,
            promptOrganizer = promptOrganizer,
            voiceControlPlane = voiceControlPlane,
            voiceUxLogger = voiceUxLogger,
            speakTts = { req ->
                ttsPlayer.speakWithResult(
                    serverUrl = req.serverUrl,
                    text = req.text,
                    role = req.role,
                    sessionLabel = req.sessionLabel,
                    inputModeOverride = req.inputModeOverride,
                    kind = req.kind,
                    notifySpokenSummary = req.notifySpokenSummary,
                    isManualRead = req.isManualRead,
                    messageId = req.messageId,
                    onAudibleStart = { msgId, durMs ->
                        sessionResponseController.notifySpeechPlaybackStarted(msgId, durMs)
                        req.onAudibleStart?.invoke(msgId, durMs)
                    },
                    onAudibleReset = { msgId ->
                        sessionResponseController.resetSpeechProgressToPreparing(msgId)
                    },
                )
            },
            stopTts = {
                if (::ttsPlayer.isInitialized) {
                    ttsPlayer.stop()
                }
            },
            withStandalonePlaybackFocus = { block ->
                val granted = bluetoothAudioRouter.acquirePlaybackFocus(
                    onAudioFocusChange = ::handlePlaybackAudioFocusChange,
                )
                if (granted) {
                    try {
                        block()
                    } finally {
                        val cur = stateMachine.currentState
                        if (cur != PttState.SPEAKING_TTS && cur != PttState.STAGED_DRAFT_SPEAKING) {
                            bluetoothAudioRouter.releasePlaybackFocus()
                        }
                    }
                }
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
            syncForPromptTurnCompletion = sessionResponseController::syncForPromptTurnCompletion,
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
            setSpeakingMessage = sessionResponseController::setSpeakingMessage,
            onPlayCue = { cue ->
                if (::cuePlayer.isInitialized) {
                    cuePlayer.play(cue)
                }
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
                AudioDebugRegistry.shared.record(
                    category = AudioDebugCategory.MEDIA_BUTTON,
                    event = "GESTURE_${gesture.name}",
                    outcome = AudioDebugOutcome.ACCEPTED,
                    pttState = stateMachine.currentState.name,
                    details = mapOf(
                        "raw" to rawInput.name,
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
            onGroupingDecision = { event, outcome, details ->
                recordComponentDiagnostic(AudioDebugCategory.MEDIA_BUTTON, event, outcome, details)
            },
        )

        powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        restoreDraftForActiveSession()
        _statusText.value = notificationBuilder.statusTextForState(stateMachine.currentState)
        initMediaSession()
        recordingVolumeKeyController = RecordingVolumeKeyController(
            mediaSession = checkNotNull(mediaSession),
            onVolumeAction = { action, direction ->
                handleVolumeKeyAction(action, direction)
            },
        ).apply {
            onDiagnosticEvent = { event, outcome, details ->
                recordComponentDiagnostic(AudioDebugCategory.VOLUME_KEY, event, outcome, details)
                updateDiagnosticLiveStatus()
            }
        }
        synchronized(dispatchLock) {
            syncMediaSessionAndVolumeControlsLocked(stateMachine.currentState)
        }
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val screenRx = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val screenOff = isScreenOffNow()
                AudioDebugRegistry.shared.record(
                    category = AudioDebugCategory.VOLUME_KEY,
                    event = if (screenOff) "SCREEN_OFF" else "SCREEN_ON",
                    outcome = AudioDebugOutcome.STATE_CHANGE,
                    pttState = stateMachine.currentState.name,
                    details = mapOf(
                        "action" to (intent?.action ?: "null"),
                        "isScreenOff" to screenOff,
                    ),
                )
                synchronized(dispatchLock) {
                    syncMediaSessionAndVolumeControlsLocked(stateMachine.currentState)
                }
                updateDiagnosticLiveStatus()
            }
        }
        ContextCompat.registerReceiver(this, screenRx, screenFilter, ContextCompat.RECEIVER_EXPORTED)
        screenStateReceiver = screenRx
        audioDiagnosticMonitor = AudioPlatformDiagnosticMonitor(
            context = applicationContext,
            buffer = AudioDebugRegistry.shared,
            onAudioBecomingNoisy = {
                dispatchInternal(
                    PttEvent.PlaybackInterruptedByTransport,
                    requiredStates = setOf(PttState.SPEAKING_TTS, PttState.STAGED_DRAFT_SPEAKING),
                    requiredPinGeneration = null,
                )
            },
        )
        audioDiagnosticMonitor.start()
        bluetoothAudioRouter.startMonitoring { drop ->
            handleUnexpectedScoDrop(drop)
        }
        updateDiagnosticLiveStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_PIN_SCO)) {
                pinBluetoothSco = intent.getBooleanExtra(EXTRA_PIN_SCO, pinBluetoothSco)
            }
            when (intent.action) {
                Intent.ACTION_MEDIA_BUTTON -> {
                    startForegroundWithState(stateMachine.currentState)
                    mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
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
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
                ACTION_STAGE_CAPTURE -> {
                    startForegroundWithState(stateMachine.currentState)
                    stageCurrentRecording()
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
                ACTION_DISCARD_CAPTURE -> {
                    startForegroundWithState(stateMachine.currentState)
                    discardCurrent()
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
                ACTION_COMMIT_DRAFT -> {
                    startForegroundWithState(stateMachine.currentState)
                    commitStagedDraft()
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
                ACTION_PULL_BACK_DRAFT -> {
                    startForegroundWithState(stateMachine.currentState)
                    pullBackToDraft()
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
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
                        startForegroundWithState(
                            pttState = cur,
                            serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                        )
                        stopForegroundAndSelf()
                    }
                }
                else -> {
                    if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        RecordingNotificationActionReceiver.onStopRecordingRequested = null
        RecordingNotificationActionReceiver.onDiagnosticEvent = null
        if (::recordingMirrorController.isInitialized) {
            recordingMirrorController.release()
        }
        if (::recordingVolumeKeyController.isInitialized) {
            recordingVolumeKeyController.release()
        }
        screenStateReceiver?.let { rx ->
            runCatching { unregisterReceiver(rx) }
            screenStateReceiver = null
        }
        if (::audioDiagnosticMonitor.isInitialized) {
            audioDiagnosticMonitor.stop()
        }
        if (::gestureDetector.isInitialized) {
            gestureDetector.cancelPending()
        }
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.setArmed(false)
        }
        cancelAllTurnJobs(stopTts = true)
        if (::telecomCaptureCoordinator.isInitialized) {
            telecomCaptureCoordinator.release()
        }
        if (::notificationBuilder.isInitialized) {
            notificationBuilder.cancelPermissionAlertNotification()
        }
        releaseWakeAndWifiLocks()
        if (::bluetoothAudioRouter.isInitialized) {
            bluetoothAudioRouter.stopMonitoring()
            bluetoothAudioRouter.release()
        }
        if (::cuePlayer.isInitialized) {
            cuePlayer.release()
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
        if (::ttsCache.isInitialized) {
            ttsCache.close()
        }
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        lastMediaSessionPlaybackState = PlaybackStateCompat.STATE_NONE
        updateDiagnosticLiveStatus()
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
            cuePlayer.play(CueEvent.ERROR)
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
            vocalCuePackWarmer.ensureWarmedAsync()
            if (transition.changed) {
                sessionResponseController.requestBackgroundSync(
                    reason = "arm",
                    triggerAutoSpeak = false,
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
        cuePlayer.play(CueEvent.CANCEL_DISCARD)
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
        cuePlayer.play(CueEvent.UNDO_TO_DRAFT)
        voiceUxLogger.log(VoiceUxLogger.CAT_DRAFT, "reverted", key)
        syncActiveBubble()
    }

    fun readVoiceUxSummary(recentLimit: Int = 25): VoiceUxSummary =
        voiceUxLogger.readSummary(recentLimit)

    fun exportVoiceUxLog(): String = voiceUxLogger.exportJsonl()

    fun clearVoiceUxLog() {
        voiceUxLogger.clear()
    }

    fun getTtsCacheStats(): TtsCacheStats =
        if (::ttsCache.isInitialized) ttsCache.stats() else TtsCacheStats(0, 0L, 0, 0L)

    fun clearTtsCache() {
        if (::ttsCache.isInitialized) {
            ttsCache.clear()
            if (::cuePlayer.isInitialized) {
                cuePlayer.clearDecodedClipCache()
            }
            if (::vocalCuePackWarmer.isInitialized) {
                vocalCuePackWarmer.resetAfterCacheClear()
            }
            if (::sessionResponseController.isInitialized) {
                sessionResponseController.refreshFocusedSpokenSummaryFromCache()
            }
        }
    }

    fun previewVoiceSample(
        role: VoiceRole,
        engine: TtsEngine,
        voiceId: String?,
        kokoroSpeakerId: Int = TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
    ) {
        if (stateMachine.currentState.isBusyWithTurn) {
            Toast.makeText(
                applicationContext,
                "Voice preview unavailable while a turn is active",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val activeServer = tokenStore.getActiveServer()
        val serverUrl = activeServer?.serverUrl.orEmpty()
        val serverId = activeServer?.id
        val needsServer = engine == TtsEngine.GEMINI_BRIDGE ||
            engine == TtsEngine.LOCAL_KOKORO ||
            engine == TtsEngine.OPENAI_CLOUD
        if (needsServer && (serverUrl.isBlank() || activeServer?.token.isNullOrBlank())) {
            Toast.makeText(
                applicationContext,
                "Connect to an OpenChamber server to preview ${engine.title}",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val sampleText = previewSampleTextForRole(role)
        previewJob?.cancel()
        var jobRef: Job? = null
        val job = serviceScope.launch {
            try {
                ttsPlayer.speakPreview(
                    serverUrl = serverUrl,
                    text = sampleText,
                    engine = engine,
                    voiceId = voiceId,
                    kokoroSpeakerId = kokoroSpeakerId,
                    role = role,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (serverId != null) {
                    handleUnauthorizedIfNeeded(serverId, t)
                }
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
                voiceUxLogger.log(
                    category = VoiceUxLogger.CAT_TTS,
                    event = "preview_failed",
                    sessionKey = activeSessionKey(),
                    metrics = mapOf(
                        "role" to role.name,
                        "engine" to engine.name,
                        "voice" to voiceId,
                        "error" to errMsg,
                    ),
                )
                Toast.makeText(
                    applicationContext,
                    "Preview failed: $errMsg",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                if (previewJob === jobRef) {
                    previewJob = null
                }
            }
        }
        jobRef = job
        previewJob = job
    }

    fun stopVoicePreview() {
        val hadPreview = previewJob?.isActive == true
        previewJob?.cancel()
        previewJob = null
        if (hadPreview && !stateMachine.currentState.isBusyWithTurn && ::ttsPlayer.isInitialized) {
            ttsPlayer.stop()
        }
    }

    fun onActiveSessionOrServerChanged() {
        stopVoicePreview()
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
        stopVoicePreview()
        val bound = resolveBoundSession()
        if (bound == null) {
            _lastError.value = "Connect to OpenChamber and select a session first"
            _statusText.value = _lastError.value.orEmpty()
            cuePlayer.play(CueEvent.ERROR)
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
        requiredTelecomGeneration: Long? = null,
        requiredCaptureSeq: Long? = null,
    ): PttTransition? {
        val transition = synchronized(dispatchLock) {
            val current = stateMachine.currentState
            if (requiredStates != null && current !in requiredStates) {
                return null
            }
            if (requiredPinGeneration != null && !bluetoothAudioRouter.isCurrentPinGeneration(requiredPinGeneration)) {
                return null
            }
            if (requiredTelecomGeneration != null &&
                (!activeBurstUseTelecom ||
                    !telecomCaptureCoordinator.isCurrentGeneration(requiredTelecomGeneration) ||
                    (activeTelecomCallGeneration != null && activeTelecomCallGeneration != requiredTelecomGeneration))
            ) {
                return null
            }
            if (requiredCaptureSeq != null && captureSeqCounter.get() != requiredCaptureSeq) {
                return null
            }
            val tr = stateMachine.dispatch(event)
            syncStateAndStatus(tr)
            applyEffects(tr)
            syncMediaSessionAndVolumeControlsLocked(tr.current)
            if (::recordingMirrorController.isInitialized) {
                recordingMirrorController.sync(
                    isRecording = tr.current == PttState.RECORDING,
                    featureEnabled = tokenStore.getMirrorRecordingNotification(),
                    sessionTitle = tokenStore.getPinnedSession()?.title,
                )
            }
            if (tr.changed) {
                updateForegroundNotification(tr.current)
            }
            _transitions.tryEmit(tr)
            tr
        }
        recordPttTransitionDiagnostic(transition)
        updateDiagnosticLiveStatus()
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
        var deferRecStartCueForBurst = false
        var playbackFocusDenied = false
        for (effect in transition.effects) {
            when (effect) {
                is PttEffect.PlayCue -> {
                    if (effect.cue == CueEvent.REC_START && deferRecStartCueForBurst) {
                        // Deferred until Telecom call & BT endpoint are active inside AudioBurstRecorder.
                    } else if (playbackFocusDenied) {
                        // Playback focus denied; avoid playing cue over active call/transport.
                    } else {
                        cuePlayer.play(effect.cue)
                    }
                }
                PttEffect.AcquireWakeAndWifiLocks -> acquireWakeAndWifiLocks()
                PttEffect.ReleaseWakeAndWifiLocks -> releaseWakeAndWifiLocks()
                PttEffect.AcquireCaptureAudio -> {
                    audioOutputCoordinator.invalidateSinkWarmth()
                    captureSeqCounter.incrementAndGet()
                    val useTelecom = pinBluetoothSco && tokenStore.getTelecomCallForRecording()
                    activeBurstUseTelecom = useTelecom
                    activeTelecomCallGeneration = null
                    deferRecStartCueForBurst = useTelecom
                    bluetoothAudioRouter.acquireCaptureAudio(
                        pinSco = pinBluetoothSco,
                        telecomManaged = useTelecom,
                        onAudioFocusChange = ::handleCaptureAudioFocusChange,
                    )
                }
                PttEffect.ReleaseCaptureAudio -> {
                    captureSeqCounter.incrementAndGet()
                    val wasTelecom = activeBurstUseTelecom ||
                        (::telecomCaptureCoordinator.isInitialized && telecomCaptureCoordinator.isCallActive)
                    activeBurstUseTelecom = false
                    activeTelecomCallGeneration = null
                    audioOutputCoordinator.invalidateSinkWarmth()
                    if (::telecomCaptureCoordinator.isInitialized) {
                        telecomCaptureCoordinator.endCaptureCall()
                    }
                    bluetoothAudioRouter.releaseCaptureAudio()
                    if (wasTelecom && transition.current != PttState.DISARMED) {
                        mediaSession?.let { session ->
                            session.isActive = true
                        }
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.TELECOM,
                            event = "MEDIA_SESSION_REASSERTED",
                            outcome = AudioDebugOutcome.STATE_CHANGE,
                            pttState = transition.current.name,
                            details = mapOf("mediaSessionActive" to (mediaSession?.isActive == true)),
                        )
                    }
                }
                PttEffect.AcquirePlaybackFocus -> {
                    val granted = bluetoothAudioRouter.acquirePlaybackFocus(
                        onAudioFocusChange = ::handlePlaybackAudioFocusChange,
                    )
                    if (!granted) {
                        playbackFocusDenied = true
                        serviceScope.launch(Dispatchers.Main) {
                            dispatchInternal(
                                PttEvent.PlaybackInterruptedByTransport,
                                requiredStates = setOf(PttState.SPEAKING_TTS, PttState.STAGED_DRAFT_SPEAKING),
                                requiredPinGeneration = null,
                            )
                        }
                    }
                }
                PttEffect.ReleasePlaybackFocus -> {
                    bluetoothAudioRouter.releasePlaybackFocus()
                }
                PttEffect.StartRecording -> {
                    stopVoicePreview()
                    if (::cuePlayer.isInitialized) {
                        cuePlayer.stopUnlessRecStart()
                    } else if (::earconPlayer.isInitialized) {
                        earconPlayer.stopUnless(com.openchamber.ptt.audio.Earcon.REC_START)
                    }
                    startBurstRecording()
                }
                PttEffect.StopRecording -> audioBurstRecorder.requestStop()
                PttEffect.DiscardRecording -> cancelBurstRecording()
                PttEffect.StopTts -> {
                    if (transition.previous == PttState.SPEAKING_TTS && ::sessionResponseController.isInitialized) {
                        when (transition.event) {
                            is PttEvent.SingleTap -> sessionResponseController.onUserBargeIn()
                            is PttEvent.PlaybackInterruptedByTransport -> sessionResponseController.onSpeechInterruptedByTransport()
                            else -> Unit
                        }
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
                PttEffect.ReadLastReply -> if (!playbackFocusDenied) agentTurnRunner.readLastReply()
                PttEffect.SpeakPendingReplies -> if (!playbackFocusDenied) agentTurnRunner.speakPendingReplies()
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
        previewJob?.cancel()
        previewJob = null
        lastTtsStopElapsedMs = SystemClock.elapsedRealtime()
        if (::agentTurnRunner.isInitialized) {
            agentTurnRunner.cancelSpeechJobs()
        }
        if (::sessionResponseController.isInitialized) {
            sessionResponseController.setSpeakingMessage(null)
        }
        if (::audioOutputCoordinator.isInitialized) {
            audioOutputCoordinator.stopAll(
                earconPlayer = if (::earconPlayer.isInitialized) earconPlayer else null,
                ttsPlayer = if (::ttsPlayer.isInitialized) ttsPlayer else null,
                cuePlayer = if (::cuePlayer.isInitialized) cuePlayer else null,
            )
        } else {
            if (::cuePlayer.isInitialized) {
                cuePlayer.stop()
            }
            if (::earconPlayer.isInitialized) {
                earconPlayer.stop()
            }
            if (::ttsPlayer.isInitialized) {
                ttsPlayer.stop()
            }
        }
    }

    private fun shouldUseCommunicationAudioUsage(): Boolean =
        (::bluetoothAudioRouter.isInitialized && bluetoothAudioRouter.isScoPinned) ||
            (::telecomCaptureCoordinator.isInitialized && telecomCaptureCoordinator.isCallActive)

    private suspend fun prepareCaptureTransportForBurst(): CaptureTransportPrepResult {
        var earlyResult: CaptureTransportPrepResult? = null
        var entrySeq = 0L
        synchronized(dispatchLock) {
            if (!activeBurstUseTelecom) {
                earlyResult = CaptureTransportPrepResult.Ready(playStartCueAfterPrep = false)
            } else if (stateMachine.currentState != PttState.RECORDING) {
                earlyResult = CaptureTransportPrepResult.Aborted
            } else {
                entrySeq = captureSeqCounter.get()
            }
        }
        earlyResult?.let { return it }

        val startResult = telecomCaptureCoordinator.startCaptureCall()
        val prepResult = when (startResult) {
            is TelecomCallStartResult.Ready -> {
                val accepted = synchronized(dispatchLock) {
                    if (stateMachine.currentState == PttState.RECORDING &&
                        captureSeqCounter.get() == entrySeq
                    ) {
                        activeTelecomCallGeneration = startResult.generation
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    telecomCaptureCoordinator.endCaptureCall(startResult.generation)
                    CaptureTransportPrepResult.Aborted
                } else {
                    updateDiagnosticLiveStatus()
                    CaptureTransportPrepResult.Ready(playStartCueAfterPrep = true)
                }
            }
            is TelecomCallStartResult.CancelledBeforeReady -> {
                if (startResult.byRemoteHangup) {
                    AudioDebugRegistry.shared.record(
                        category = AudioDebugCategory.TELECOM,
                        event = "PRE_READY_REMOTE_HANGUP",
                        outcome = AudioDebugOutcome.STATE_CHANGE,
                        pttState = stateMachine.currentState.name,
                        details = mapOf("generation" to startResult.generation),
                    )
                }
                CaptureTransportPrepResult.Aborted
            }
            is TelecomCallStartResult.FailedCellularOrBusy -> {
                AudioDebugRegistry.shared.record(
                    category = AudioDebugCategory.TELECOM,
                    event = "START_CAPTURE_FAILED_BUSY",
                    outcome = AudioDebugOutcome.ERROR,
                    pttState = stateMachine.currentState.name,
                    details = mapOf(
                        "errorCode" to startResult.errorCode,
                        "reason" to startResult.reason,
                    ),
                )
                dispatchInternal(
                    event = PttEvent.Error("Cellular call active — cannot start recording"),
                    requiredStates = setOf(PttState.RECORDING),
                    requiredPinGeneration = null,
                    requiredCaptureSeq = entrySeq,
                )
                CaptureTransportPrepResult.Aborted
            }
            is TelecomCallStartResult.FailedUnavailable -> {
                AudioDebugRegistry.shared.record(
                    category = AudioDebugCategory.TELECOM,
                    event = "TELECOM_FALLBACK_TO_SCO",
                    outcome = AudioDebugOutcome.ERROR,
                    pttState = stateMachine.currentState.name,
                    details = mapOf("reason" to startResult.reason),
                )
                val fallbackAccepted = synchronized(dispatchLock) {
                    if (stateMachine.currentState != PttState.RECORDING ||
                        captureSeqCounter.get() != entrySeq
                    ) {
                        false
                    } else {
                        activeBurstUseTelecom = false
                        bluetoothAudioRouter.acquireCaptureAudio(
                            pinSco = pinBluetoothSco,
                            telecomManaged = false,
                            onAudioFocusChange = ::handleCaptureAudioFocusChange,
                        )
                        true
                    }
                }
                if (!fallbackAccepted) {
                    CaptureTransportPrepResult.Aborted
                } else {
                    updateDiagnosticLiveStatus()
                    CaptureTransportPrepResult.Ready(playStartCueAfterPrep = true)
                }
            }
        }
        if (prepResult is CaptureTransportPrepResult.Aborted) {
            val shouldDiscard = synchronized(dispatchLock) {
                stateMachine.currentState == PttState.RECORDING && captureSeqCounter.get() == entrySeq
            }
            if (shouldDiscard) {
                dispatchInternal(
                    PttEvent.DiscardCapture,
                    requiredStates = setOf(PttState.RECORDING),
                    requiredPinGeneration = null,
                    requiredCaptureSeq = entrySeq,
                )
            }
        }
        return prepResult
    }

    private fun handleCaptureAudioFocusChange(focusChange: Int) {
        AudioDebugRegistry.shared.record(
            category = AudioDebugCategory.AUDIO_FOCUS,
            event = "CAPTURE_${focusChangeName(focusChange)}",
            outcome = AudioDebugOutcome.STATE_CHANGE,
            pttState = stateMachine.currentState.name,
            details = mapOf("focusChange" to focusChangeName(focusChange)),
        )
    }

    private fun handlePlaybackAudioFocusChange(focusChange: Int) {
        val current = stateMachine.currentState
        AudioDebugRegistry.shared.record(
            category = AudioDebugCategory.AUDIO_FOCUS,
            event = "PLAYBACK_${focusChangeName(focusChange)}",
            outcome = AudioDebugOutcome.STATE_CHANGE,
            pttState = current.name,
            details = mapOf("focusChange" to focusChangeName(focusChange)),
        )
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            if (stateMachine.currentState == PttState.PERMISSION_BLOCKED) {
                if (::agentTurnRunner.isInitialized) {
                    agentTurnRunner.cancelSpeechJobs()
                }
                stopTtsWithBargeInCooldown()
            } else {
                dispatchInternal(
                    PttEvent.PlaybackInterruptedByTransport,
                    requiredStates = setOf(PttState.SPEAKING_TTS, PttState.STAGED_DRAFT_SPEAKING),
                    requiredPinGeneration = null,
                )
            }
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
        previewJob?.cancel()
        previewJob = null
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
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                        if (stateMachine.currentState == PttState.DISARMED) {
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "onMediaButtonEvent",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = PttState.DISARMED.name,
                                details = mapOf("reason" to "disarmed"),
                            )
                            return false
                        }
                        val keyEvent = IntentCompat.getParcelableExtra(
                            mediaButtonEvent,
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java,
                        )
                        if (keyEvent == null) {
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "onMediaButtonEvent",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = stateMachine.currentState.name,
                                details = mapOf(
                                    "reason" to "missing_key_event",
                                    "action" to (mediaButtonEvent.action ?: "null"),
                                ),
                            )
                            return super.onMediaButtonEvent(mediaButtonEvent)
                        }
                        val keyCodeName = KeyEvent.keyCodeToString(keyEvent.keyCode)
                        val actionName = keyActionName(keyEvent.action)
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
                        if (rawInput == null) {
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "onMediaButtonEvent($keyCodeName)",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = stateMachine.currentState.name,
                                details = mapOf(
                                    "reason" to "unhandled_keycode",
                                    "keyCode" to keyCodeName,
                                    "action" to actionName,
                                    "repeatCount" to keyEvent.repeatCount,
                                    "source" to (keyEvent.device?.name ?: "unknown"),
                                ),
                            )
                            return super.onMediaButtonEvent(mediaButtonEvent)
                        }
                        if (keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount != 0) {
                            val ignoreReason = if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                                "non_down_action"
                            } else {
                                "repeat_event"
                            }
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "onMediaButtonEvent($keyCodeName)",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = stateMachine.currentState.name,
                                details = mapOf(
                                    "reason" to ignoreReason,
                                    "keyCode" to keyCodeName,
                                    "action" to actionName,
                                    "repeatCount" to keyEvent.repeatCount,
                                    "rawInput" to rawInput.name,
                                ),
                            )
                            return true
                        }
                        if ((rawInput == RawMediaInput.NEXT || rawInput == RawMediaInput.PREVIOUS) &&
                            !isAvrcpTrackControlsEnabled()
                        ) {
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "onMediaButtonEvent($keyCodeName)",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = stateMachine.currentState.name,
                                details = mapOf(
                                    "reason" to "avrcp_track_controls_disabled",
                                    "keyCode" to keyCodeName,
                                    "rawInput" to rawInput.name,
                                ),
                            )
                            return true
                        }
                        val mode = currentGroupingMode()
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.MEDIA_BUTTON,
                            event = "onMediaButtonEvent($keyCodeName)",
                            outcome = AudioDebugOutcome.ACCEPTED,
                            pttState = stateMachine.currentState.name,
                            details = mapOf(
                                "keyCode" to keyCodeName,
                                "action" to actionName,
                                "rawInput" to rawInput.name,
                                "groupingMode" to mode.name,
                                "source" to (keyEvent.device?.name ?: "unknown"),
                            ),
                        )
                        gestureDetector.onInput(rawInput, mode)
                        return true
                    }

                    override fun onPlay() {
                        if (stateMachine.currentState == PttState.DISARMED) return
                        val mode = currentGroupingMode()
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.MEDIA_BUTTON,
                            event = "MediaSession.onPlay",
                            outcome = AudioDebugOutcome.ACCEPTED,
                            pttState = stateMachine.currentState.name,
                            details = mapOf("rawInput" to RawMediaInput.PRIMARY_TAP.name, "groupingMode" to mode.name),
                        )
                        gestureDetector.onInput(RawMediaInput.PRIMARY_TAP, mode)
                    }

                    override fun onPause() {
                        if (stateMachine.currentState == PttState.DISARMED) return
                        val mode = currentGroupingMode()
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.MEDIA_BUTTON,
                            event = "MediaSession.onPause",
                            outcome = AudioDebugOutcome.ACCEPTED,
                            pttState = stateMachine.currentState.name,
                            details = mapOf("rawInput" to RawMediaInput.PRIMARY_TAP.name, "groupingMode" to mode.name),
                        )
                        gestureDetector.onInput(RawMediaInput.PRIMARY_TAP, mode)
                    }

                    override fun onSkipToNext() {
                        if (stateMachine.currentState == PttState.DISARMED) return
                        if (!isAvrcpTrackControlsEnabled()) {
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "MediaSession.onSkipToNext",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = stateMachine.currentState.name,
                                details = mapOf(
                                    "reason" to "avrcp_track_controls_disabled",
                                    "rawInput" to RawMediaInput.NEXT.name,
                                ),
                            )
                            return
                        }
                        val mode = currentGroupingMode()
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.MEDIA_BUTTON,
                            event = "MediaSession.onSkipToNext",
                            outcome = AudioDebugOutcome.ACCEPTED,
                            pttState = stateMachine.currentState.name,
                            details = mapOf("rawInput" to RawMediaInput.NEXT.name, "groupingMode" to mode.name),
                        )
                        gestureDetector.onInput(RawMediaInput.NEXT, mode)
                    }

                    override fun onSkipToPrevious() {
                        if (stateMachine.currentState == PttState.DISARMED) return
                        if (!isAvrcpTrackControlsEnabled()) {
                            AudioDebugRegistry.shared.record(
                                category = AudioDebugCategory.MEDIA_BUTTON,
                                event = "MediaSession.onSkipToPrevious",
                                outcome = AudioDebugOutcome.IGNORED,
                                pttState = stateMachine.currentState.name,
                                details = mapOf(
                                    "reason" to "avrcp_track_controls_disabled",
                                    "rawInput" to RawMediaInput.PREVIOUS.name,
                                ),
                            )
                            return
                        }
                        val mode = currentGroupingMode()
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.MEDIA_BUTTON,
                            event = "MediaSession.onSkipToPrevious",
                            outcome = AudioDebugOutcome.ACCEPTED,
                            pttState = stateMachine.currentState.name,
                            details = mapOf("rawInput" to RawMediaInput.PREVIOUS.name, "groupingMode" to mode.name),
                        )
                        gestureDetector.onInput(RawMediaInput.PREVIOUS, mode)
                    }

                    override fun onStop() {
                        if (stateMachine.currentState == PttState.DISARMED) return
                        val mode = currentGroupingMode()
                        AudioDebugRegistry.shared.record(
                            category = AudioDebugCategory.MEDIA_BUTTON,
                            event = "MediaSession.onStop",
                            outcome = AudioDebugOutcome.ACCEPTED,
                            pttState = stateMachine.currentState.name,
                            details = mapOf("rawInput" to RawMediaInput.STOP.name, "groupingMode" to mode.name),
                        )
                        gestureDetector.onInput(RawMediaInput.STOP, mode)
                    }
                },
            )
        }
        mediaSession = session
        updateMediaSessionPlaybackState(stateMachine.currentState, isScreenOff = isScreenOffNow())
    }

    private fun isScreenOffNow(): Boolean =
        if (::powerManager.isInitialized) !powerManager.isInteractive else false

    private fun currentGroupingMode(): TapGroupingMode =
        MediaButtonGestureDetector.groupingModeFor(
            state = stateMachine.currentState,
            tapMode = if (::tokenStore.isInitialized) tokenStore.getHeadsetTapMode() else HeadsetTapMode.MULTI_TAP,
        )

    private fun isAvrcpTrackControlsEnabled(): Boolean =
        !::tokenStore.isInitialized || tokenStore.getAvrcpTrackControlsEnabled()

    private fun syncMediaSessionAndVolumeControlsLocked(pttState: PttState) {
        val screenOff = isScreenOffNow()
        updateMediaSessionPlaybackState(pttState, isScreenOff = screenOff)
        if (::recordingVolumeKeyController.isInitialized && ::tokenStore.isInitialized) {
            recordingVolumeKeyController.sync(
                episode = volumeEpisodeForState(pttState),
                isScreenOff = screenOff,
                recordingMode = tokenStore.getVolumeKeyRecordingMode(),
                draftMode = tokenStore.getVolumeKeyDraftMode(),
            )
        }
    }

    private fun handleVolumeKeyAction(
        action: VolumeKeyAction,
        direction: Int,
    ) {
        gestureDetector.cancelPending()
        val (event, requiredStates, uxEventName) = when (action) {
            VolumeKeyAction.STOP_QUICK_SEND ->
                Triple(PttEvent.SingleTap, setOf(PttState.RECORDING), "volume_key_stop_send")
            VolumeKeyAction.STOP_STAGE_DRAFT ->
                Triple(PttEvent.StageCapture, setOf(PttState.RECORDING), "volume_key_stop_stage")
            VolumeKeyAction.COMMIT_DRAFT ->
                Triple(PttEvent.CommitDraft, setOf(PttState.STAGED_DRAFT, PttState.STAGED_DRAFT_SPEAKING), "volume_key_draft_send")
            VolumeKeyAction.DISCARD_DRAFT ->
                Triple(PttEvent.DiscardCapture, setOf(PttState.STAGED_DRAFT, PttState.STAGED_DRAFT_SPEAKING), "volume_key_draft_discard")
        }
        val tr = dispatchInternal(
            event,
            requiredStates = requiredStates,
            requiredPinGeneration = null,
        )
        if (tr != null) {
            voiceUxLogger.log(
                category = VoiceUxLogger.CAT_GESTURE,
                event = uxEventName,
                sessionKey = activeSessionKey(),
                metrics = mapOf(
                    "direction" to direction,
                    "action" to action.name,
                    "state" to tr.previous.name,
                ),
            )
            AudioDebugRegistry.shared.record(
                category = AudioDebugCategory.VOLUME_KEY,
                event = "VOLUME_KEY_${action.name}",
                outcome = AudioDebugOutcome.ACCEPTED,
                pttState = tr.current.name,
                details = mapOf(
                    "direction" to direction,
                    "action" to action.name,
                    "previousState" to tr.previous.name,
                ),
            )
        } else {
            AudioDebugRegistry.shared.record(
                category = AudioDebugCategory.VOLUME_KEY,
                event = "VOLUME_KEY_${action.name}",
                outcome = AudioDebugOutcome.IGNORED,
                pttState = stateMachine.currentState.name,
                details = mapOf(
                    "direction" to direction,
                    "action" to action.name,
                    "reason" to "state_mismatch",
                ),
            )
        }
    }

    private fun updateMediaSessionPlaybackState(
        pttState: PttState,
        isScreenOff: Boolean = isScreenOffNow(),
    ) {
        val session = mediaSession ?: return
        val shouldBeActive = pttState != PttState.DISARMED
        if (session.isActive != shouldBeActive) {
            session.isActive = shouldBeActive
        }
        val draftVolumeEnabled = ::tokenStore.isInitialized && tokenStore.getVolumeKeyDraftMode().isEnabled
        val playbackStateCode = resolveMediaSessionPlaybackState(
            pttState = pttState,
            isScreenOff = isScreenOff,
            draftVolumeEnabled = draftVolumeEnabled,
        )
        lastMediaSessionPlaybackState = playbackStateCode
        val stateObj = PlaybackStateCompat.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(playbackStateCode, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        session.setPlaybackState(stateObj)
    }

    private fun recordComponentDiagnostic(
        category: AudioDebugCategory,
        event: String,
        outcome: String,
        details: Map<String, String>,
    ) {
        AudioDebugRegistry.shared.record(
            category = category,
            event = event,
            outcome = parseDiagnosticOutcome(outcome),
            pttState = stateMachine.currentState.name,
            details = details,
        )
    }

    private fun recordPttTransitionDiagnostic(transition: PttTransition) {
        if (!transition.changed && transition.effects.isEmpty()) return
        val effectsSummary = transition.effects.joinToString(",") { it::class.simpleName.orEmpty() }
        AudioDebugRegistry.shared.record(
            category = AudioDebugCategory.PTT_STATE,
            event = "${transition.previous.name} -> ${transition.current.name}",
            outcome = AudioDebugOutcome.STATE_CHANGE,
            pttState = transition.current.name,
            details = buildMap {
                put("event", transition.event::class.simpleName ?: transition.event.toString())
                if (effectsSummary.isNotEmpty()) {
                    put("effects", effectsSummary)
                }
            },
        )
    }

    private fun updateDiagnosticLiveStatus() {
        val currentState = stateMachine.currentState
        val scoPinned = ::bluetoothAudioRouter.isInitialized && bluetoothAudioRouter.isScoPinned
        val telecomActive = ::telecomCaptureCoordinator.isInitialized && telecomCaptureCoordinator.isCallActive
        val transportLabel = when {
            telecomActive -> "TELECOM_CALL"
            activeBurstUseTelecom && currentState == PttState.RECORDING -> "TELECOM_STARTING"
            scoPinned -> "CLASSIC_BT_SCO"
            currentState == PttState.RECORDING && pinBluetoothSco -> "PIN_SCO_REQUESTED"
            currentState == PttState.RECORDING -> "SYSTEM_DEFAULT_MIC"
            pinBluetoothSco && ::tokenStore.isInitialized && tokenStore.getTelecomCallForRecording() ->
                "IDLE (armed: TELECOM)"
            pinBluetoothSco -> "IDLE (armed: BT_SCO)"
            else -> "SYSTEM_DEFAULT"
        }
        val telecomLabel = when {
            telecomActive -> "ACTIVE"
            activeBurstUseTelecom && currentState == PttState.RECORDING -> "STARTING"
            else -> "IDLE"
        }
        val sessionActive = mediaSession?.isActive == true
        val playbackStateLabel = playbackStateName(lastMediaSessionPlaybackState)
        val screenOff = isScreenOffNow()
        val volMode = if (::recordingVolumeKeyController.isInitialized && recordingVolumeKeyController.isAttached) {
            "REMOTE (${recordingVolumeKeyController.activeEpisode.name}, SCREEN_OFF)"
        } else if (!screenOff) {
            "LOCAL (SCREEN_ON)"
        } else {
            "LOCAL (STREAM_MUSIC)"
        }
        AudioDebugRegistry.shared.updateLiveStatus { current ->
            current.copy(
                pttState = currentState.name,
                captureTransport = transportLabel,
                isScoPinned = scoPinned,
                scoConfirmedActive = scoPinned && diagnosticScoConfirmed,
                telecomState = telecomLabel,
                mediaSessionActive = sessionActive,
                mediaSessionPlaybackState = playbackStateLabel,
                volumeHandlingMode = volMode,
            )
        }
        if (::audioDiagnosticMonitor.isInitialized) {
            audioDiagnosticMonitor.refreshAudioManagerSnapshot()
        }
    }

    private fun startForegroundWithState(
        pttState: PttState,
        serviceType: Int = resolveForegroundServiceType(),
    ) {
        val statusOverride = if (::tokenStore.isInitialized) {
            ControlHintFormatter.formatNotificationStatus(tokenStore.getControlSchemeConfig(), pttState)
        } else {
            null
        }
        val notification = notificationBuilder.buildNotification(pttState, promotedForStandaloneTts, statusOverride)
        ServiceCompat.startForeground(this, PttNotificationBuilder.NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateForegroundNotification(pttState: PttState) {
        if (pttState == PttState.DISARMED) {
            return
        }
        val statusOverride = if (::tokenStore.isInitialized) {
            ControlHintFormatter.formatNotificationStatus(tokenStore.getControlSchemeConfig(), pttState)
        } else {
            null
        }
        if (pttState == PttState.RECORDING && hasRecordAudioPermission()) {
            val notification = notificationBuilder.buildNotification(pttState, promotedForStandaloneTts, statusOverride)
            ServiceCompat.startForeground(
                this,
                PttNotificationBuilder.NOTIFICATION_ID,
                notification,
                resolveForegroundServiceType(),
            )
        } else {
            notificationBuilder.notifyServiceState(pttState, promotedForStandaloneTts, statusOverride)
        }
    }

    private fun resolveForegroundServiceType(): Int =
        resolveForegroundServiceType(
            state = stateMachine.currentState,
            promotedForStandaloneTts = promotedForStandaloneTts,
            hasRecordAudioPermission = hasRecordAudioPermission(),
        )

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

        internal fun eventForTelecomHangupAction(action: com.openchamber.ptt.audio.TelecomHangupAction): PttEvent =
            when (action) {
                com.openchamber.ptt.audio.TelecomHangupAction.QUICK_SEND -> PttEvent.SingleTap
                com.openchamber.ptt.audio.TelecomHangupAction.SEND_IMMEDIATELY -> PttEvent.SendCaptureImmediately
                com.openchamber.ptt.audio.TelecomHangupAction.STAGE_DRAFT -> PttEvent.StageCapture
            }

        internal fun volumeEpisodeForState(state: PttState): VolumeKeyEpisode = when (state) {
            PttState.RECORDING -> VolumeKeyEpisode.RECORDING
            PttState.STAGED_DRAFT,
            PttState.STAGED_DRAFT_SPEAKING,
            -> VolumeKeyEpisode.DRAFT
            else -> VolumeKeyEpisode.NONE
        }

        internal fun resolveMediaSessionPlaybackState(
            pttState: PttState,
            isScreenOff: Boolean,
            draftVolumeEnabled: Boolean,
        ): Int = when (pttState) {
            PttState.RECORDING,
            PttState.TRANSCRIBING_SEND,
            PttState.TRANSCRIBING_STAGE,
            PttState.SEND_GRACE,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            PttState.STAGED_DRAFT_SPEAKING,
            -> PlaybackStateCompat.STATE_PLAYING

            PttState.STAGED_DRAFT -> if (isScreenOff && draftVolumeEnabled) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }

            PttState.ARMED_IDLE -> PlaybackStateCompat.STATE_PAUSED

            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            PttState.DISARMED,
            -> PlaybackStateCompat.STATE_STOPPED
        }

        internal fun shouldCancelTurnOnIdleReturn(transition: PttTransition): Boolean {
            if (transition.current != PttState.ARMED_IDLE && transition.current != PttState.STAGED_DRAFT) {
                return false
            }
            return (transition.previous == PttState.PERMISSION_BLOCKED && transition.event is PttEvent.Arm) ||
                (transition.previous == PttState.SPEAKING_TTS && transition.event is PttEvent.SingleTap)
        }

        internal fun resolveForegroundServiceType(
            state: PttState,
            promotedForStandaloneTts: Boolean,
            hasRecordAudioPermission: Boolean,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): Int {
            if (promotedForStandaloneTts || state == PttState.DISARMED) {
                return ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (sdkInt >= Build.VERSION_CODES.R && hasRecordAudioPermission) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            return type
        }

        internal fun shouldCheckAutoSpeakAfterIdleReturn(transition: PttTransition): Boolean {
            if (transition.current != PttState.ARMED_IDLE && transition.current != PttState.STAGED_DRAFT) {
                return false
            }
            if (transition.previous == PttState.SPEAKING_TTS && transition.event is PttEvent.SingleTap) {
                return false
            }
            if (transition.event is PttEvent.PlaybackInterruptedByTransport) {
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

        internal fun previewSampleTextForRole(role: VoiceRole): String = when (role) {
            VoiceRole.AGENT -> "Build succeeded cleanly. Ready for your next prompt."
            VoiceRole.OPERATOR -> "Switched to Spoken mode and Balanced detail."
            VoiceRole.CUE -> "Summarizing."
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
                -> ScoDropAction.RELEASE_ONLY
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

        private fun parseDiagnosticOutcome(outcome: String): AudioDebugOutcome = when (outcome.uppercase()) {
            "ACCEPTED" -> AudioDebugOutcome.ACCEPTED
            "IGNORED" -> AudioDebugOutcome.IGNORED
            "DROPPED" -> AudioDebugOutcome.DROPPED
            "ERROR" -> AudioDebugOutcome.ERROR
            else -> AudioDebugOutcome.STATE_CHANGE
        }

        private fun keyActionName(action: Int): String = when (action) {
            KeyEvent.ACTION_DOWN -> "ACTION_DOWN"
            KeyEvent.ACTION_UP -> "ACTION_UP"
            else -> "ACTION_$action"
        }

        private fun focusChangeName(focusChange: Int): String = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> focusChange.toString()
        }

        private fun playbackStateName(state: Int): String = when (state) {
            PlaybackStateCompat.STATE_PLAYING -> "STATE_PLAYING"
            PlaybackStateCompat.STATE_PAUSED -> "STATE_PAUSED"
            PlaybackStateCompat.STATE_STOPPED -> "STATE_STOPPED"
            PlaybackStateCompat.STATE_NONE -> "NONE"
            else -> "STATE($state)"
        }

        private fun audioSourceLabel(source: Int): String = when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            else -> "SOURCE($source)"
        }

        private fun audioDeviceTypeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            else -> "DEV($type)"
        }
    }
}
