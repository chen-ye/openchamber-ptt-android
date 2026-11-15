package com.openchamber.ptt.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media.session.MediaButtonReceiver
import com.openchamber.ptt.MainActivity
import com.openchamber.ptt.R
import com.openchamber.ptt.audio.BluetoothAudioRouter
import com.openchamber.ptt.audio.Earcon
import com.openchamber.ptt.audio.EarconPlayer
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.session.SessionApiException
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsException
import com.openchamber.ptt.tts.TtsPlayer
import com.openchamber.ptt.voice.DictationClient
import com.openchamber.ptt.voice.DictationException
import com.openchamber.ptt.voice.EventStreamClient
import com.openchamber.ptt.voice.MlKitSttClient
import com.openchamber.ptt.voice.SessionClient
import com.openchamber.ptt.voice.TurnEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

class PttForegroundService : Service() {
    interface Listener {
        fun onStartRecordingRequested()
        fun onStopRecordingRequested()
        fun onStopTtsRequested()
    }

    inner class PttBinder : Binder() {
        val service: PttForegroundService
            get() = this@PttForegroundService
    }

    private val binder = PttBinder()
    private val dispatchLock = Any()
    private val turnLock = Any()
    private val stateMachine = PttStateMachine()
    private val _transitions = MutableSharedFlow<PttTransition>(extraBufferCapacity = 32)
    private val _statusText = MutableStateFlow("")
    private val _lastTranscript = MutableStateFlow<String?>(null)
    private val _lastReply = MutableStateFlow<String?>(null)
    private val _lastTtsInfo = MutableStateFlow<String?>(null)
    private val _lastError = MutableStateFlow<String?>(null)
    private val _pinBluetoothSco = MutableStateFlow(false)
    private val _recordingStartedAtElapsedMs = MutableStateFlow<Long?>(null)

    val state: StateFlow<PttState> = stateMachine.state
    val statusText: StateFlow<String> = _statusText.asStateFlow()
    val lastTranscript: StateFlow<String?> = _lastTranscript.asStateFlow()
    val lastReply: StateFlow<String?> = _lastReply.asStateFlow()
    val lastTtsInfo: StateFlow<String?> = _lastTtsInfo.asStateFlow()
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    val permissionPrompt: StateFlow<String?> = stateMachine.permissionPrompt
    val pinBluetoothScoFlow: StateFlow<Boolean> = _pinBluetoothSco.asStateFlow()
    val recordingStartedAtElapsedMs: StateFlow<Long?> = _recordingStartedAtElapsedMs.asStateFlow()
    val transitions: SharedFlow<PttTransition> = _transitions.asSharedFlow()

    var pinBluetoothSco: Boolean
        get() = _pinBluetoothSco.value
        set(value) {
            _pinBluetoothSco.value = value
        }

    @Volatile
    var listener: Listener? = null

    private lateinit var earconPlayer: EarconPlayer
    private lateinit var bluetoothAudioRouter: BluetoothAudioRouter
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    private lateinit var tokenStore: TokenStore
    private lateinit var httpClient: OkHttpClient
    private lateinit var dictationClient: DictationClient
    private lateinit var mlKitSttClient: MlKitSttClient
    private lateinit var sessionClient: SessionClient
    private lateinit var eventStreamClient: EventStreamClient
    private lateinit var ttsClient: TtsClient
    private lateinit var ttsPlayer: TtsPlayer
    private lateinit var serviceScope: CoroutineScope

    private var activeTurnJob: Job? = null
    private var activeCaptureController: CaptureController? = null
    private var preloadJob: Job? = null
    private var loadedSessionKey: String? = null
    @Volatile
    private var promotedForStandaloneTts: Boolean = false

    @Volatile
    private var lastTtsStopElapsedMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        earconPlayer = EarconPlayer()
        bluetoothAudioRouter = BluetoothAudioRouter(this)
        notificationManager = getSystemService(NotificationManager::class.java)

        tokenStore = TokenStore(applicationContext)
        httpClient = OpenChamberHttp.createClient(
            tokenForUrlProvider = { url -> tokenStore.getTokenForUrl(url) },
            directoryForUrlProvider = { url -> tokenStore.getDirectoryForUrl(url) },
        )
        dictationClient = DictationClient(httpClient)
        mlKitSttClient = MlKitSttClient()
        sessionClient = SessionClient(httpClient)
        eventStreamClient = EventStreamClient(httpClient)
        ttsClient = TtsClient(httpClient)
        ttsPlayer = TtsPlayer(
            context = applicationContext,
            ttsClient = ttsClient,
            useCommunicationUsage = { bluetoothAudioRouter.isScoPinned },
            pipelineProvider = { tokenStore.getEffectiveTtsPipeline() },
            mlKitVoiceNameProvider = { tokenStore.getMlKitTtsVoice() },
            openAiVoiceProvider = { tokenStore.getEffectiveOpenAiTtsVoice() },
            systemVoiceNameProvider = { tokenStore.getSystemTtsVoice() },
            onStatusEvent = { info -> _lastTtsInfo.value = info },
        )

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
        }

        _statusText.value = statusTextForState(stateMachine.currentState)
        createNotificationChannels()
        initMediaSession()
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
                    onMediaButtonTap()
                }
                ACTION_STOP_SERVICE -> {
                    disarm()
                    stopForegroundAndSelf()
                }
                ACTION_ENSURE_MEDIA_FOREGROUND -> {
                    if (stateMachine.currentState == PttState.SPEAKING_TTS) {
                        startForegroundWithState(
                            pttState = PttState.SPEAKING_TTS,
                            serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                        )
                    } else if (stateMachine.currentState == PttState.DISARMED) {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        preloadJob?.cancel()
        cancelActiveTurn(stopTts = true)
        cancelPermissionAlertNotification()
        releaseWakeAndWifiLocks()
        bluetoothAudioRouter.release()
        earconPlayer.release()
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
        val transition = dispatch(PttEvent.Arm)
        if (transition.current == PttState.ARMED_IDLE && hasRecordAudioPermission()) {
            runCatching { startForegroundWithState(transition.current) }
        }
        return transition.current == PttState.ARMED_IDLE
    }

    fun disarm() {
        promotedForStandaloneTts = false
        dispatch(PttEvent.Disarm)
        stopForegroundAndSelf()
    }

    fun onMediaButtonTap() {
        dispatch(PttEvent.MediaButtonTap)
    }

    fun onActiveSessionOrServerChanged() {
        val currentState = stateMachine.currentState
        if (currentState == PttState.RECORDING ||
            currentState == PttState.TRANSCRIBING ||
            currentState == PttState.WAITING_AGENT ||
            currentState == PttState.SPEAKING_TTS ||
            currentState == PttState.PERMISSION_BLOCKED
        ) {
            disarm()
        } else if (currentState == PttState.ARMED_IDLE) {
            updateForegroundNotification(currentState)
        }
        refreshLatestTurn(forceClearOnSessionChange = true)
    }

    fun refreshLatestTurn(forceClearOnSessionChange: Boolean = true) {
        val activeServer = tokenStore.getActiveServer()
        val serverUrl = activeServer?.serverUrl
        val token = activeServer?.token
        val pinnedSession = activeServer?.lastPinnedSession
        if (activeServer == null || serverUrl.isNullOrBlank() || token.isNullOrBlank() || pinnedSession == null) {
            if (forceClearOnSessionChange) {
                loadedSessionKey = null
                _lastTranscript.value = null
                _lastReply.value = null
                _lastTtsInfo.value = null
            }
            return
        }

        val currentState = stateMachine.currentState
        if (currentState == PttState.RECORDING ||
            currentState == PttState.TRANSCRIBING ||
            currentState == PttState.WAITING_AGENT ||
            currentState == PttState.SPEAKING_TTS ||
            currentState == PttState.PERMISSION_BLOCKED
        ) {
            return
        }

        val nextKey = "${activeServer.id}|${pinnedSession.sessionId}|${pinnedSession.directory}"
        if (forceClearOnSessionChange && loadedSessionKey != null && loadedSessionKey != nextKey) {
            _lastTranscript.value = null
            _lastReply.value = null
            _lastTtsInfo.value = null
        }
        loadedSessionKey = nextKey

        preloadJob?.cancel()
        preloadJob = serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val latest = sessionClient.fetchLatestTurn(
                    serverUrl = serverUrl,
                    sessionId = pinnedSession.sessionId,
                    directory = pinnedSession.directory,
                )
                if (loadedSessionKey == nextKey) {
                    if (!latest.userText.isNullOrBlank()) {
                        _lastTranscript.value = latest.userText
                    }
                    if (!latest.assistantText.isNullOrBlank()) {
                        _lastReply.value = latest.assistantText
                    }
                    syncActiveBubble()
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                handleUnauthorizedIfNeeded(activeServer.id, err)
            }
        }
    }

    fun readPreviousMessage() {
        if (stateMachine.currentState == PttState.SPEAKING_TTS) {
            onMediaButtonTap()
            return
        }
        val activeServer = tokenStore.getActiveServer()
        val serverId = activeServer?.id
        val serverUrl = activeServer?.serverUrl
        val token = activeServer?.token
        val pinnedSession = activeServer?.lastPinnedSession
        if (serverId == null || serverUrl.isNullOrBlank() || token.isNullOrBlank() || pinnedSession == null) {
            _lastError.value = "Connect to OpenChamber and select a session first"
            _statusText.value = _lastError.value.orEmpty()
            earconPlayer.play(Earcon.ERROR)
            return
        }
        val wasDisarmed = stateMachine.currentState == PttState.DISARMED
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
        _lastError.value = null
        synchronized(turnLock) {
            activeTurnJob?.cancel(CancellationException("Superseded by read-back"))
            activeTurnJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    val replyText = _lastReply.value?.takeIf { it.isNotBlank() }
                        ?: sessionClient.fetchLatestTurn(
                            serverUrl = serverUrl,
                            sessionId = pinnedSession.sessionId,
                            directory = pinnedSession.directory,
                        ).assistantText
                    if (replyText.isNullOrBlank()) {
                        _statusText.value = "No previous assistant reply in this session"
                        onTtsFinished()
                        return@launch
                    }
                    _lastReply.value = replyText
                    syncActiveBubble()
                    bluetoothAudioRouter.configureForPtt(pinSco = pinBluetoothSco)
                    if (stateMachine.currentState == PttState.SPEAKING_TTS) {
                        ttsPlayer.speak(
                            serverUrl = serverUrl,
                            text = replyText,
                            voice = tokenStore.getEffectiveTtsVoice(),
                        )
                        onTtsFinished()
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    handleUnauthorizedIfNeeded(serverId, t)
                    onError(t.message?.takeIf { it.isNotBlank() } ?: "Failed to read previous message")
                }
            }
        }
    }

    fun onTranscriptReady(text: String? = null) {
        dispatch(PttEvent.TranscriptReady(text))
    }

    fun onAgentCompleted(hasTts: Boolean = true) {
        dispatch(PttEvent.AgentCompleted(hasTts))
    }

    fun onPermissionAsked(reason: String? = null) {
        dispatch(PttEvent.PermissionAsked(reason))
    }

    fun onPermissionResolved() {
        dispatch(PttEvent.PermissionResolved)
    }

    fun onTtsFinished() {
        dispatch(PttEvent.TtsFinished)
    }

    fun onError(message: String? = null) {
        dispatch(PttEvent.Error(message))
    }

    fun dispatch(event: PttEvent): PttTransition = synchronized(dispatchLock) {
        val transition = stateMachine.dispatch(event)
        syncStateAndStatus(transition)
        applyEffects(transition)
        updateMediaSessionPlaybackState(transition.current)
        if (transition.changed) {
            updateForegroundNotification(transition.current)
        }
        _transitions.tryEmit(transition)
        transition
    }

    private fun syncStateAndStatus(transition: PttTransition) {
        val smError = stateMachine.lastError.value
        if (transition.event is PttEvent.Arm || transition.event is PttEvent.Disarm) {
            _lastError.value = smError
        } else if (smError != null) {
            _lastError.value = smError
        }

        if (transition.changed && transition.previous == PttState.PERMISSION_BLOCKED) {
            cancelPermissionAlertNotification()
        }

        if (transition.changed) {
            _recordingStartedAtElapsedMs.value = if (transition.current == PttState.RECORDING) {
                SystemClock.elapsedRealtime()
            } else {
                null
            }
            val nextStatus = when (transition.current) {
                PttState.ERROR -> smError?.let { "Error: $it" } ?: statusTextForState(PttState.ERROR)
                PttState.PERMISSION_BLOCKED -> {
                    val tool = stateMachine.permissionPrompt.value ?: "unknown"
                    formatPermissionAlertSpeech(tool)
                }
                else -> statusTextForState(transition.current)
            }
            _statusText.value = nextStatus
            syncActiveBubble(nextStatus)
        }
    }

    private fun syncActiveBubble(statusSummary: String = _statusText.value) {
        val title = tokenStore.getPinnedSession()?.title ?: "OpenChamber Voice"
        BubbleHelper.updateIfPosted(
            context = this,
            sessionTitle = title,
            statusSummary = statusSummary,
            userPrompt = _lastTranscript.value,
            agentReply = _lastReply.value,
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
                PttEffect.StartRecording -> {
                    startTurnRecording()
                    listener?.onStartRecordingRequested()
                }
                PttEffect.StopRecording -> {
                    stopCaptureRequested()
                    listener?.onStopRecordingRequested()
                }
                PttEffect.StopTts -> {
                    stopTtsWithBargeInCooldown()
                    listener?.onStopTtsRequested()
                }
            }
        }

        when (transition.current) {
            PttState.DISARMED,
            PttState.ERROR,
            -> {
                cancelActiveTurn(stopTts = transition.effects.none { it is PttEffect.StopTts })
                cancelPermissionAlertNotification()
                releaseWakeAndWifiLocks()
                if (promotedForStandaloneTts) {
                    promotedForStandaloneTts = false
                    stopForegroundAndSelf()
                }
            }

            PttState.ARMED_IDLE -> {
                if ((transition.previous == PttState.PERMISSION_BLOCKED && transition.event is PttEvent.Arm) ||
                    (transition.previous == PttState.SPEAKING_TTS && transition.event is PttEvent.MediaButtonTap)
                ) {
                    cancelActiveTurn(stopTts = false)
                }
                releaseWakeAndWifiLocks()
            }

            PttState.PERMISSION_BLOCKED -> releaseWakeAndWifiLocks()

            PttState.RECORDING,
            PttState.TRANSCRIBING,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            -> Unit
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
        if (::ttsPlayer.isInitialized) {
            ttsPlayer.stop()
        }
    }

    private fun startTurnRecording() {
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

        val captureController = CaptureController()
        synchronized(turnLock) {
            activeTurnJob?.cancel(CancellationException("Superseded by new voice turn"))
            activeCaptureController?.requestStop()
            activeCaptureController = captureController
            activeTurnJob = serviceScope.launch(Dispatchers.IO) {
                runVoiceTurn(
                    serverId = serverId,
                    serverUrl = serverUrl,
                    pinnedSession = pinnedSession,
                    captureController = captureController,
                )
            }
        }
    }

    private fun stopCaptureRequested() {
        synchronized(turnLock) {
            activeCaptureController?.requestStop()
        }
    }

    private fun cancelActiveTurn(stopTts: Boolean) {
        val jobToCancel: Job?
        val controllerToStop: CaptureController?
        synchronized(turnLock) {
            jobToCancel = activeTurnJob
            activeTurnJob = null
            controllerToStop = activeCaptureController
            activeCaptureController = null
        }
        controllerToStop?.requestStop()
        if (stopTts && ::ttsPlayer.isInitialized) {
            lastTtsStopElapsedMs = SystemClock.elapsedRealtime()
            ttsPlayer.stop()
        }
        jobToCancel?.cancel(CancellationException("PTT turn cancelled"))
    }

    private suspend fun runVoiceTurn(
        serverId: String,
        serverUrl: String,
        pinnedSession: PinnedSession,
        captureController: CaptureController,
    ) {
        try {
            val cooldownRemaining = remainingBargeInCooldownMs(
                lastTtsStopElapsedMs = lastTtsStopElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                cooldownMs = BARGE_IN_COOLDOWN_MS,
            )
            if (cooldownRemaining > 0L) {
                delay(cooldownRemaining)
            }

            if (captureController.isStopRequested || stateMachine.currentState != PttState.RECORDING) {
                if (stateMachine.currentState == PttState.TRANSCRIBING) {
                    onTranscriptReady("")
                }
                return
            }

            val transcript = recordAndTranscribe(serverUrl, captureController)
            if (transcript.isNullOrBlank()) {
                if (stateMachine.currentState == PttState.TRANSCRIBING) {
                    _statusText.value = "No speech detected"
                    onTranscriptReady("")
                }
                return
            }

            _lastTranscript.value = transcript
            syncActiveBubble()
            onTranscriptReady(transcript)
            if (stateMachine.currentState != PttState.WAITING_AGENT) {
                return
            }

            val baselineAssistantId = sessionClient.getLatestAssistantMessageId(
                serverUrl = serverUrl,
                sessionId = pinnedSession.sessionId,
                directory = pinnedSession.directory,
            )

            coroutineScope {
                val wsReady = CompletableDeferred<Unit>()
                val turnEvents = Channel<TurnEvent>(Channel.UNLIMITED)
                val eventCollectorJob = launch(Dispatchers.IO) {
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
                    withTimeoutOrNull(EVENT_STREAM_READY_TIMEOUT_MS) {
                        wsReady.await()
                    }

                    sessionClient.sendPrompt(
                        serverUrl = serverUrl,
                        sessionId = pinnedSession.sessionId,
                        directory = pinnedSession.directory,
                        text = formatVoicePrompt(transcript),
                    )

                    for (turnEvent in turnEvents) {
                        when (turnEvent) {
                            is TurnEvent.PermissionAsked -> {
                                permissionDelayJob?.cancel()
                                permissionDelayJob = launch(Dispatchers.IO) {
                                    delay(PERMISSION_ALERT_DELAY_MS)
                                    onPermissionAsked(turnEvent.tool)
                                    postPermissionAlertNotification(turnEvent.tool)
                                    permissionTtsJob?.cancel()
                                    permissionTtsJob = launch(Dispatchers.IO) {
                                        runCatching {
                                            ttsPlayer.speak(
                                                serverUrl = serverUrl,
                                                text = formatPermissionAlertSpeech(turnEvent.tool),
                                                voice = tokenStore.getEffectiveTtsVoice(),
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
                                cancelPermissionAlertNotification()
                                if (stateMachine.currentState == PttState.PERMISSION_BLOCKED) {
                                    onPermissionResolved()
                                }
                            }

                            TurnEvent.Completed -> {
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
                                cancelPermissionAlertNotification()

                                val replyText = sessionClient.fetchNewAssistantText(
                                    serverUrl = serverUrl,
                                    sessionId = pinnedSession.sessionId,
                                    directory = pinnedSession.directory,
                                    afterMessageId = baselineAssistantId,
                                )

                                if (!replyText.isNullOrBlank()) {
                                    _lastReply.value = replyText
                                    syncActiveBubble()
                                    onAgentCompleted(hasTts = true)
                                    if (stateMachine.currentState == PttState.SPEAKING_TTS) {
                                        ttsPlayer.speak(
                                            serverUrl = serverUrl,
                                            text = replyText,
                                            voice = tokenStore.getEffectiveTtsVoice(),
                                        )
                                        onTtsFinished()
                                    }
                                } else {
                                    onAgentCompleted(hasTts = false)
                                }
                                break
                            }

                            is TurnEvent.Failed -> {
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
                                cancelPermissionAlertNotification()
                                onError(turnEvent.message.ifBlank { "Agent execution failed" })
                                break
                            }

                            is TurnEvent.Interrupted -> {
                                permissionDelayJob?.cancel()
                                permissionTtsJob?.cancel()
                                cancelPermissionAlertNotification()
                                _statusText.value = "Turn interrupted (${turnEvent.reason})"
                                onAgentCompleted(hasTts = false)
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
            onError(t.message?.takeIf { it.isNotBlank() } ?: "Voice turn failed")
        } finally {
            synchronized(turnLock) {
                if (activeCaptureController === captureController) {
                    activeCaptureController = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAndTranscribe(
        serverUrl: String,
        captureController: CaptureController,
    ): String? = coroutineScope {
        if (!hasRecordAudioPermission()) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw IllegalStateException("AudioRecord.getMinBufferSize failed ($minBufferBytes)")
        }

        val audioSource = if (pinBluetoothSco) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val bufferSizeBytes = maxOf(minBufferBytes * 2, CHUNK_SIZE_BYTES * 2)

        val audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { audioRecord.release() }
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        if (!captureController.attach(audioRecord)) {
            runCatching { audioRecord.release() }
            return@coroutineScope null
        }

        val sttEngine = tokenStore.getEffectiveSttEngine()
        val fallbackToParakeet = tokenStore.getEffectiveSttFallbackToParakeet()
        val bufferedPcm = if (sttEngine != SttEngine.OPENCHAMBER_PARAKEET && fallbackToParakeet) {
            ByteArrayOutputStream()
        } else {
            null
        }

        val chunkChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        val captureJob = launch(Dispatchers.IO) {
            val maxCapTimer = launch {
                delay(MAX_UTTERANCE_MS)
                captureController.requestStop()
                if (stateMachine.currentState == PttState.RECORDING) {
                    onMediaButtonTap()
                }
            }

            try {
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING")
                }

                val readBuffer = ByteArray(CHUNK_SIZE_BYTES)
                val startElapsedMs = SystemClock.elapsedRealtime()
                while (isActive && !captureController.isStopRequested) {
                    if (SystemClock.elapsedRealtime() - startElapsedMs >= MAX_UTTERANCE_MS) {
                        captureController.requestStop()
                        if (stateMachine.currentState == PttState.RECORDING) {
                            onMediaButtonTap()
                        }
                        break
                    }

                    val bytesRead = audioRecord.read(
                        readBuffer,
                        0,
                        readBuffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (bytesRead > 0) {
                        val copy = readBuffer.copyOf(bytesRead)
                        bufferedPcm?.write(copy)
                        chunkChannel.trySend(copy)
                    } else if (bytesRead < 0) {
                        if (captureController.isStopRequested) {
                            break
                        }
                        throw IllegalStateException("AudioRecord.read failed with code $bytesRead")
                    }
                }
            } finally {
                maxCapTimer.cancel()
                captureController.release()
                chunkChannel.close()
            }
        }

        if (sttEngine != SttEngine.OPENCHAMBER_PARAKEET) {
            try {
                val mlKitResult = mlKitSttClient.transcribePcmStream(
                    engine = sttEngine,
                    audioChunks = chunkChannel.receiveAsFlow(),
                    onPartial = { partial ->
                        if (partial.isNotBlank()) {
                            _lastTranscript.value = partial
                        }
                    },
                )
                captureJob.join()
                return@coroutineScope mlKitResult
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val isEmptyOnly = (t as? DictationException)?.isEmptyTranscript == true
                if (isEmptyOnly || !fallbackToParakeet || bufferedPcm == null) {
                    withContext(NonCancellable) {
                        captureController.requestStop()
                        captureJob.cancel()
                        chunkChannel.close()
                    }
                    if (isEmptyOnly) return@coroutineScope null
                    throw t
                }
                // Replay the buffered PCM bytes through OpenChamber Parakeet.
                captureJob.join()
                val pcmBytes = bufferedPcm.toByteArray()
                if (pcmBytes.isEmpty()) return@coroutineScope null
                _statusText.value = "ML Kit fallback → Parakeet…"
                return@coroutineScope try {
                    dictationClient.transcribe(
                        serverUrl = serverUrl,
                        audioChunks = flowOf(pcmBytes),
                        onPartial = { partial ->
                            if (partial.isNotBlank()) {
                                _lastTranscript.value = partial
                            }
                        },
                    )
                } catch (de: DictationException) {
                    if (de.isEmptyTranscript) null else throw de
                }
            }
        }

        val dictationSession = try {
            dictationClient.startSession(
                serverUrl = serverUrl,
                onPartial = { partial ->
                    if (partial.isNotBlank()) {
                        _lastTranscript.value = partial
                    }
                },
            )
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                captureController.requestStop()
                captureJob.cancel()
                captureJob.join()
                chunkChannel.close()
            }
            throw t
        }

        try {
            for (chunk in chunkChannel) {
                dictationSession.sendChunk(chunk)
            }
            captureJob.join()
            return@coroutineScope dictationSession.finish()
        } catch (de: DictationException) {
            if (de.isEmptyTranscript) {
                return@coroutineScope null
            }
            throw de
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                captureController.requestStop()
                captureJob.cancel()
                runCatching { dictationSession.cancel() }
            }
            throw t
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
                        if (keyEvent != null &&
                            keyEvent.action == KeyEvent.ACTION_DOWN &&
                            keyEvent.repeatCount == 0
                        ) {
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_HEADSETHOOK,
                                KeyEvent.KEYCODE_MEDIA_PLAY,
                                KeyEvent.KEYCODE_MEDIA_PAUSE,
                                -> {
                                    onMediaButtonTap()
                                    return true
                                }
                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    disarm()
                                    return true
                                }
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }

                    override fun onPlay() {
                        onMediaButtonTap()
                    }

                    override fun onPause() {
                        onMediaButtonTap()
                    }

                    override fun onStop() {
                        disarm()
                    }
                },
            )
        }
        mediaSession = session
        updateMediaSessionPlaybackState(stateMachine.currentState, targetSession = session)
    }

    private fun updateMediaSessionPlaybackState(
        pttState: PttState,
        targetSession: MediaSessionCompat? = mediaSession,
    ) {
        val session = targetSession ?: return

        val playbackState = when (pttState) {
            PttState.DISARMED -> PlaybackStateCompat.STATE_STOPPED
            PttState.ARMED_IDLE -> PlaybackStateCompat.STATE_PAUSED
            PttState.RECORDING,
            PttState.SPEAKING_TTS,
            -> PlaybackStateCompat.STATE_PLAYING
            PttState.TRANSCRIBING,
            PttState.WAITING_AGENT,
            -> PlaybackStateCompat.STATE_BUFFERING
            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            -> PlaybackStateCompat.STATE_ERROR
        }

        if (pttState == PttState.ARMED_IDLE) {
            session.isActive = true
            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(MEDIA_SESSION_ACTIONS)
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                    .build(),
            )
        }

        val stateCompat = PlaybackStateCompat.Builder()
            .setActions(MEDIA_SESSION_ACTIONS)
            .setState(playbackState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()

        session.setPlaybackState(stateCompat)
        session.isActive = pttState != PttState.DISARMED
    }

    private fun createNotificationChannels() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.ptt_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.ptt_notification_channel_description)
            setShowBadge(false)
        }

        val alertChannel = NotificationChannel(
            ALERT_NOTIFICATION_CHANNEL_ID,
            getString(R.string.ptt_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.ptt_alert_channel_description)
            setShowBadge(true)
        }

        notificationManager.createNotificationChannel(serviceChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun postPermissionAlertNotification(tool: String) {
        val notification = NotificationCompat.Builder(this, ALERT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_mic)
            .setContentTitle(getString(R.string.ptt_alert_permission_title))
            .setContentText(formatPermissionAlertSpeech(tool))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()

        runCatching {
            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        }
    }

    private fun cancelPermissionAlertNotification() {
        if (::notificationManager.isInitialized) {
            runCatching { notificationManager.cancel(ALERT_NOTIFICATION_ID) }
        }
    }

    private fun startForegroundWithState(
        pttState: PttState,
        serviceType: Int = resolveForegroundServiceType(),
    ) {
        val notification = buildNotification(pttState)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateForegroundNotification(pttState: PttState) {
        if (pttState == PttState.DISARMED) {
            return
        }
        val notification = buildNotification(pttState)
        if (pttState == PttState.RECORDING && hasRecordAudioPermission()) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, resolveForegroundServiceType())
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
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

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun statusTextForState(pttState: PttState): String = when (pttState) {
        PttState.DISARMED -> getString(R.string.ptt_status_disarmed)
        PttState.ARMED_IDLE -> getString(R.string.ptt_status_armed_idle)
        PttState.RECORDING -> getString(R.string.ptt_status_recording)
        PttState.TRANSCRIBING -> getString(R.string.ptt_status_transcribing)
        PttState.WAITING_AGENT -> getString(R.string.ptt_status_waiting_agent)
        PttState.SPEAKING_TTS -> getString(R.string.ptt_status_speaking_tts)
        PttState.PERMISSION_BLOCKED -> getString(R.string.ptt_status_permission_blocked)
        PttState.ERROR -> getString(R.string.ptt_status_error)
    }

    private fun buildNotification(pttState: PttState): Notification {
        val status = statusTextForState(pttState)
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ptt_mic)
            .setContentTitle(getString(R.string.ptt_notification_title))
            .setContentText(status)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (pttState == PttState.DISARMED) {
            builder.addAction(
                0,
                getString(R.string.ptt_action_arm),
                serviceActionPendingIntent(ACTION_ARM, REQUEST_CODE_ARM),
            )
        } else {
            val toggleLabel = when (pttState) {
                PttState.RECORDING -> getString(R.string.ptt_action_send)
                PttState.SPEAKING_TTS -> "Stop"
                else -> getString(R.string.ptt_action_talk)
            }
            builder.addAction(
                0,
                toggleLabel,
                serviceActionPendingIntent(ACTION_TOGGLE_PTT, REQUEST_CODE_TOGGLE),
            )
            if (!promotedForStandaloneTts) {
                builder.addAction(
                    0,
                    getString(R.string.ptt_action_disarm),
                    serviceActionPendingIntent(ACTION_DISARM, REQUEST_CODE_DISARM),
                )
            }
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PttForegroundService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun handleUnauthorizedIfNeeded(serverId: String, t: Throwable) {
        val statusCode = when (t) {
            is SessionApiException -> t.statusCode
            is TtsException -> t.statusCode
            is DictationException -> t.statusCode
            else -> null
        }
        if (statusCode == 401) {
            tokenStore.clearAuth(serverId)
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private class CaptureController {
        private val lock = Any()
        private val stopFlag = AtomicBoolean(false)
        private var audioRecord: AudioRecord? = null
        private var released = false

        val isStopRequested: Boolean
            get() = stopFlag.get()

        fun attach(record: AudioRecord): Boolean = synchronized(lock) {
            if (stopFlag.get() || released) {
                false
            } else {
                audioRecord = record
                true
            }
        }

        fun requestStop() {
            stopFlag.set(true)
            val record = synchronized(lock) {
                if (released) null else audioRecord
            }
            if (record != null) {
                runCatching {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop()
                    }
                }
            }
        }

        fun release() {
            val recordToRelease = synchronized(lock) {
                if (released) return
                released = true
                val rec = audioRecord
                audioRecord = null
                rec
            }
            if (recordToRelease != null) {
                runCatching {
                    if (recordToRelease.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recordToRelease.stop()
                    }
                }
                runCatching { recordToRelease.release() }
            }
        }
    }

    companion object {
        const val ACTION_ARM = "com.openchamber.ptt.action.ARM"
        const val ACTION_DISARM = "com.openchamber.ptt.action.DISARM"
        const val ACTION_TOGGLE_PTT = "com.openchamber.ptt.action.TOGGLE_PTT"
        const val ACTION_STOP_SERVICE = "com.openchamber.ptt.action.STOP_SERVICE"
        const val ACTION_ENSURE_MEDIA_FOREGROUND = "com.openchamber.ptt.action.ENSURE_MEDIA_FOREGROUND"
        const val EXTRA_PIN_SCO = "com.openchamber.ptt.extra.PIN_SCO"

        const val SAMPLE_RATE_HZ = 16_000
        const val CHUNK_DURATION_MS = 200
        const val CHUNK_SIZE_BYTES = SAMPLE_RATE_HZ * 2 * CHUNK_DURATION_MS / 1000
        const val MAX_UTTERANCE_MS = 90_000L
        const val BARGE_IN_COOLDOWN_MS = 150L
        const val PERMISSION_ALERT_DELAY_MS = 4_000L
        const val VOICE_PROMPT_PREFIX = "[voice]"

        private const val EVENT_STREAM_READY_TIMEOUT_MS = 5_000L
        private const val NOTIFICATION_CHANNEL_ID = "openchamber_ptt_channel"
        private const val ALERT_NOTIFICATION_CHANNEL_ID = "openchamber_ptt_alerts_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val REQUEST_CODE_ARM = 2001
        private const val REQUEST_CODE_DISARM = 2002
        private const val REQUEST_CODE_TOGGLE = 2003

        private const val MEDIA_SESSION_TAG = "OpenChamberPttMediaSession"
        private const val WAKE_LOCK_TAG = "OpenChamberPtt::PttWakeLock"
        private const val WIFI_LOCK_TAG = "OpenChamberPtt::PttWifiLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 3L * 60L * 1000L

        private const val MEDIA_SESSION_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP

        fun startIntent(context: Context, action: String, pinSco: Boolean? = null): Intent =
            Intent(context, PttForegroundService::class.java).apply {
                this.action = action
                if (pinSco != null) {
                    putExtra(EXTRA_PIN_SCO, pinSco)
                }
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
