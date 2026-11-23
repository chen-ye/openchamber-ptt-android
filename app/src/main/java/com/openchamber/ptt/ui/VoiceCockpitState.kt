package com.openchamber.ptt.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openchamber.ptt.audio.CuePackWarmState
import com.openchamber.ptt.audio.TelecomHangupAction
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.network.OpenChamberLauncher
import com.openchamber.ptt.service.BluetoothCaptureMode
import com.openchamber.ptt.service.ControlPreset
import com.openchamber.ptt.service.ControlSchemeConfig
import com.openchamber.ptt.service.HeadsetTapMode
import com.openchamber.ptt.service.PttForegroundService
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.service.VolumeKeyDraftMode
import com.openchamber.ptt.service.VolumeKeyRecordingMode
import com.openchamber.ptt.session.ServerSessionsResult
import com.openchamber.ptt.session.SessionRepository
import com.openchamber.ptt.session.SessionSummary
import com.openchamber.ptt.session.mergeRefreshedServerGroups
import com.openchamber.ptt.session.resolveAutoPinCandidate
import com.openchamber.ptt.session.resolvePinnedSession
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import com.openchamber.ptt.storage.ServerVoiceOverride
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.storage.toServerCloudOverride
import com.openchamber.ptt.tts.AudioCueMode
import com.openchamber.ptt.tts.MlKitTtsSpeaker
import com.openchamber.ptt.tts.MlKitTtsVoiceSummary
import com.openchamber.ptt.tts.RoleVoiceProfileMap
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.SystemTtsVoiceSummary
import com.openchamber.ptt.tts.TtsCache
import com.openchamber.ptt.tts.TtsCacheStats
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsException
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.TtsPlayer
import com.openchamber.ptt.tts.TtsVoice
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.tts.VoiceRoleResolver
import com.openchamber.ptt.voice.AssistantResponseItem
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitDownloadEvent
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.ResponseFeedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Stable
internal class VoiceCockpitState(
    val tokenStore: TokenStore,
    private val sessionRepository: SessionRepository,
    private val ttsClient: TtsClient,
    private val context: Context,
    private val scope: CoroutineScope,
    private val boundServiceState: State<PttForegroundService?>,
    private val pttStateState: State<PttState>,
    private val statusTextState: State<String>,
    private val lastTranscriptState: State<String?>,
    private val lastReplyState: State<String?>,
    private val lastSpokenSummaryState: State<String?>,
    private val lastTtsInfoState: State<String?>,
    private val serviceErrorState: State<String?>,
    private val permissionPromptState: State<String?>,
    private val recordingStartedAtState: State<Long?>,
    private val activeDraftState: State<StagedDraft?>,
    private val pendingQuickTranscriptState: State<String?>,
    private val sendGraceRemainingMsState: State<Long?>,
    private val isOrganizingDraftState: State<Boolean>,
    private val responseFeedState: State<ResponseFeedState>,
    private val cuePackWarmStateState: State<CuePackWarmState>,
    showSessionPickerState: MutableState<Boolean>,
    showVoicePickerState: MutableState<Boolean>,
    voicePickerInitialEngineState: MutableState<TtsEngine>,
    voicePickerInitialRoleState: MutableState<VoiceRole>,
    val mlKitTtsSpeaker: MlKitTtsSpeaker,
    initialPinSco: Boolean,
    private val onSessionExpiredAuthErrorState: State<(String) -> Unit>,
) {
    var permissionSnapshot by mutableStateOf(readPermissionSnapshot(context))
        private set

    var servers by mutableStateOf(tokenStore.getServers())
        private set
    var activeServer by mutableStateOf(tokenStore.getActiveServer())
        private set
    var pinnedSession by mutableStateOf(tokenStore.getPinnedSession())
        private set
    var serverGroups by mutableStateOf<List<ServerSessionsResult>>(emptyList())
        private set
    var isLoadingSessions by mutableStateOf(false)
        private set
    var sessionsError by mutableStateOf<String?>(null)
        private set
    private var refreshSessionsJob: Job? = null

    var showSessionPicker by showSessionPickerState
    var showVoicePicker by showVoicePickerState
    var voicePickerInitialEngine by voicePickerInitialEngineState
    var voicePickerInitialRole by voicePickerInitialRoleState

    var roleVoiceProfiles by mutableStateOf(tokenStore.getEffectiveRoleVoiceProfiles())
        private set
    var ttsCacheStats by mutableStateOf(TtsCacheStats(0, 0L, 0, 0L))
        private set

    var selectedVoice by mutableStateOf(tokenStore.getEffectiveTtsVoice())
        private set
    var voices by mutableStateOf<List<TtsVoice>>(emptyList())
        private set
    var isLoadingVoices by mutableStateOf(false)
        private set
    var voicesError by mutableStateOf<String?>(null)
        private set

    var selectedOpenAiVoice by mutableStateOf(tokenStore.getEffectiveOpenAiTtsVoice())
        private set
    var selectedSystemVoice by mutableStateOf(tokenStore.getSystemTtsVoice())
        private set
    var systemVoices by mutableStateOf<List<SystemTtsVoiceSummary>>(emptyList())
        private set
    var isLoadingSystemVoices by mutableStateOf(false)
        private set

    var ttsPipeline by mutableStateOf(tokenStore.getEffectiveTtsPipeline())
        private set
    var ttsInputMode by mutableStateOf(tokenStore.getEffectiveTtsInputMode())
        private set
    var spokenDetailLevel by mutableStateOf(tokenStore.getEffectiveSpokenDetailLevel())
        private set
    var sttEngine by mutableStateOf(tokenStore.getEffectiveSttEngine())
        private set
    var sttFallbackToParakeet by mutableStateOf(tokenStore.getEffectiveSttFallbackToParakeet())
        private set
    var quickTurnDirectAnswer by mutableStateOf(tokenStore.getEffectiveQuickTurnDirectAnswer())
        private set
    var quickTranscriptCleanup by mutableStateOf(tokenStore.getEffectiveQuickTranscriptCleanup())
        private set
    var audioCueMode by mutableStateOf(tokenStore.getEffectiveAudioCueMode())
        private set

    var volumeKeyStopRecording by mutableStateOf(tokenStore.getVolumeKeyStopRecording())
        private set
    var telecomCallForRecording by mutableStateOf(tokenStore.getTelecomCallForRecording())
        private set
    var bluetoothCaptureMode by mutableStateOf(tokenStore.getBluetoothCaptureMode())
        private set
    var telecomHangupAction by mutableStateOf(tokenStore.getTelecomHangupAction())
        private set
    var headsetTapMode by mutableStateOf(tokenStore.getHeadsetTapMode())
        private set
    var avrcpTrackControlsEnabled by mutableStateOf(tokenStore.getAvrcpTrackControlsEnabled())
        private set
    var volumeKeyRecordingMode by mutableStateOf(tokenStore.getVolumeKeyRecordingMode())
        private set
    var volumeKeyDraftMode by mutableStateOf(tokenStore.getVolumeKeyDraftMode())
        private set
    var mirrorRecordingNotification by mutableStateOf(tokenStore.getMirrorRecordingNotification())
        private set

    var mlKitTtsStatus by mutableStateOf<MlKitStatusReport?>(null)
        private set
    var mlKitTtsVoices by mutableStateOf<List<MlKitTtsVoiceSummary>>(emptyList())
        private set
    var selectedMlKitTtsVoice by mutableStateOf(tokenStore.getMlKitTtsVoice())
        private set
    var mlKitTtsDownloadStatus by mutableStateOf<String?>(null)
        private set
    var isDownloadingMlKitTts by mutableStateOf(false)
        private set

    var pinSco by mutableStateOf(initialPinSco)
        internal set
    var storeChangeVersion by mutableLongStateOf(tokenStore.changes.value)
        private set

    val controlSchemeConfig: ControlSchemeConfig
        get() = ControlSchemeConfig(
            pinBluetoothSco = pinSco,
            bluetoothCaptureMode = bluetoothCaptureMode,
            telecomHangupAction = telecomHangupAction,
            headsetTapMode = headsetTapMode,
            avrcpTrackControlsEnabled = avrcpTrackControlsEnabled,
            volumeKeyRecordingMode = volumeKeyRecordingMode,
            volumeKeyDraftMode = volumeKeyDraftMode,
            mirrorRecordingNotification = mirrorRecordingNotification,
        )

    val boundService: PttForegroundService?
        get() = boundServiceState.value
    val pttState: PttState
        get() = pttStateState.value
    val statusText: String
        get() = statusTextState.value
    val lastTranscript: String?
        get() = lastTranscriptState.value
    val lastReply: String?
        get() = lastReplyState.value
    val lastSpokenSummary: String?
        get() = lastSpokenSummaryState.value
    val lastTtsInfo: String?
        get() = lastTtsInfoState.value
    val serviceError: String?
        get() = serviceErrorState.value
    val permissionPrompt: String?
        get() = permissionPromptState.value
    val recordingStartedAt: Long?
        get() = recordingStartedAtState.value
    val activeDraft: StagedDraft?
        get() = activeDraftState.value
    val pendingQuickTranscript: String?
        get() = pendingQuickTranscriptState.value
    val sendGraceRemainingMs: Long?
        get() = sendGraceRemainingMsState.value
    val isOrganizingDraft: Boolean
        get() = isOrganizingDraftState.value
    val responseFeed: ResponseFeedState
        get() = responseFeedState.value
    val cuePackWarmState: CuePackWarmState
        get() = cuePackWarmStateState.value
    val unreadCount: Int
        get() = responseFeed.unreadQueue.size
    val inspectableResponses: List<AssistantResponseItem>
        get() = responseFeed.inspectableResponses
    val focusedResponse: AssistantResponseItem?
        get() = responseFeed.focusedResponse

    val isConnected: Boolean
        get() = servers.any { it.isSignedIn }
    val hasActiveToken: Boolean
        get() = !activeServer?.token.isNullOrBlank()
    val activeServerUrl: String
        get() = activeServer?.serverUrl.orEmpty()
    val customServerId: String?
        get() = activeServer?.takeIf { it.voiceOverride != null }?.id
    val isOperateReady: Boolean
        get() = permissionSnapshot.hasRecordAudio && isConnected
    val canArm: Boolean
        get() = pinnedSession != null && hasActiveToken
    val canReadAloud: Boolean
        get() = pinnedSession != null &&
            hasActiveToken &&
            (pttState == PttState.DISARMED ||
                pttState == PttState.ARMED_IDLE ||
                pttState == PttState.STAGED_DRAFT ||
                pttState == PttState.SPEAKING_TTS)

    val selectedSystemVoiceLabel: String?
        get() = selectedSystemVoice?.let { id ->
            systemVoices.firstOrNull { it.name == id }?.label
                ?: id.substringAfterLast('-').ifBlank { id }
        }

    val systemVoiceLabels: Map<String, String>
        get() = systemVoices.associate { it.name to it.label }

    val availableGeminiVoiceIds: List<String>
        get() = voices.map { it.id }

    val availableMlKitVoiceNames: List<String>
        get() = mlKitTtsVoices
            .filter { it.availability == MlKitAvailability.AVAILABLE }
            .map { it.name }
            .ifEmpty { mlKitTtsVoices.map { it.name } }

    val activeVoiceChipLabel: String
        get() = VoicePipelineDefaults.resolveActiveVoiceChipLabel(
            pipeline = VoiceRoleResolver.resolvePipelineForRole(ttsPipeline, roleVoiceProfiles.agent),
            geminiVoice = selectedVoice,
            mlKitVoice = selectedMlKitTtsVoice,
            openAiVoice = selectedOpenAiVoice,
            systemVoiceLabel = selectedSystemVoiceLabel,
        )

    fun refreshPermissionSnapshot() {
        permissionSnapshot = readPermissionSnapshot(context)
    }

    fun syncEffectiveVoiceState(serverId: String? = tokenStore.getActiveServer()?.id) {
        roleVoiceProfiles = tokenStore.getEffectiveRoleVoiceProfiles(serverId)
        selectedVoice = tokenStore.getEffectiveTtsVoice(serverId)
        selectedOpenAiVoice = tokenStore.getEffectiveOpenAiTtsVoice(serverId)
        selectedMlKitTtsVoice = tokenStore.getMlKitTtsVoice()
        selectedSystemVoice = tokenStore.getSystemTtsVoice()
        ttsPipeline = tokenStore.getEffectiveTtsPipeline(serverId)
        ttsInputMode = tokenStore.getEffectiveTtsInputMode(serverId)
        spokenDetailLevel = tokenStore.getEffectiveSpokenDetailLevel(serverId)
        sttEngine = tokenStore.getEffectiveSttEngine(serverId)
        sttFallbackToParakeet = tokenStore.getEffectiveSttFallbackToParakeet(serverId)
        quickTurnDirectAnswer = tokenStore.getEffectiveQuickTurnDirectAnswer(serverId)
        quickTranscriptCleanup = tokenStore.getEffectiveQuickTranscriptCleanup(serverId)
        audioCueMode = tokenStore.getEffectiveAudioCueMode(serverId)
    }

    fun refreshTtsCacheStats() {
        scope.launch(Dispatchers.IO) {
            val stats = boundService?.getTtsCacheStats() ?: run {
                val fallback = TtsCache(context.applicationContext)
                try {
                    fallback.stats()
                } finally {
                    fallback.close()
                }
            }
            withContext(Dispatchers.Main) {
                ttsCacheStats = stats
            }
        }
    }

    fun clearTtsCache() {
        scope.launch(Dispatchers.IO) {
            val service = boundService
            val next = if (service != null) {
                service.clearTtsCache()
                service.getTtsCacheStats()
            } else {
                val fallback = TtsCache(context.applicationContext)
                try {
                    fallback.clear()
                    fallback.stats()
                } finally {
                    fallback.close()
                }
            }
            withContext(Dispatchers.Main) {
                ttsCacheStats = next
            }
        }
    }

    fun syncServerStateFromStore() {
        val list = tokenStore.getServers()
        val active = tokenStore.getActiveServer()
        servers = list
        activeServer = active
        pinnedSession = active?.lastPinnedSession
        storeChangeVersion = tokenStore.changes.value
        syncEffectiveVoiceState(active?.id)
        pinSco = boundService?.pinBluetoothSco ?: tokenStore.getPinBluetoothSco()
        volumeKeyStopRecording = tokenStore.getVolumeKeyStopRecording()
        telecomCallForRecording = tokenStore.getTelecomCallForRecording()
        bluetoothCaptureMode = tokenStore.getBluetoothCaptureMode()
        telecomHangupAction = tokenStore.getTelecomHangupAction()
        headsetTapMode = tokenStore.getHeadsetTapMode()
        avrcpTrackControlsEnabled = tokenStore.getAvrcpTrackControlsEnabled()
        volumeKeyRecordingMode = tokenStore.getVolumeKeyRecordingMode()
        volumeKeyDraftMode = tokenStore.getVolumeKeyDraftMode()
        mirrorRecordingNotification = tokenStore.getMirrorRecordingNotification()
    }

    private fun cancelSessionRefresh() {
        refreshSessionsJob?.cancel()
        refreshSessionsJob = null
        isLoadingSessions = false
    }

    fun refreshSessions() {
        val currentServers = tokenStore.getServers()
        if (currentServers.none { it.isSignedIn }) {
            cancelSessionRefresh()
            syncServerStateFromStore()
            return
        }
        refreshSessionsJob?.cancel()
        isLoadingSessions = true
        sessionsError = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val results = sessionRepository.listAllServerSessions(currentServers)
                results.filter { it.isUnauthorized && it.server.isSignedIn }.forEach { failed ->
                    if (failed.server.id == tokenStore.getActiveServer()?.id) {
                        boundService?.disarm()
                    }
                    tokenStore.clearAuth(failed.server.id)
                }
                syncServerStateFromStore()
                val updatedServers = servers
                val curActive = activeServer

                val refreshedResults = mergeRefreshedServerGroups(
                    previousGroups = serverGroups,
                    freshResults = results,
                    updatedServers = updatedServers,
                )
                serverGroups = refreshedResults

                val activeResult = refreshedResults.firstOrNull { it.server.id == curActive?.id }
                if (activeResult != null && activeResult.roots.isNotEmpty()) {
                    val currentPinned = curActive?.lastPinnedSession
                    if (currentPinned != null) {
                        val updated = resolvePinnedSession(activeResult.roots, currentPinned)
                        if (updated != null && updated != currentPinned) {
                            val oldKey = TokenStore.sessionKeyFor(activeResult.server.id, currentPinned)
                            val newKey = TokenStore.sessionKeyFor(activeResult.server.id, updated)
                            val (migratedDraft, migratedCursor) = if (oldKey != newKey) {
                                tokenStore.migrateStagedDraft(oldKey, newKey) to
                                    tokenStore.migrateReplyCursor(oldKey, newKey)
                            } else {
                                false to false
                            }
                            pinnedSession = updated
                            tokenStore.updatePinnedSession(activeResult.server.id, updated)
                            if (migratedDraft || migratedCursor || updated.directory != currentPinned.directory) {
                                boundService?.refreshLatestTurn(forceClearOnSessionChange = false)
                            }
                        }
                    } else {
                        val autoCandidate = resolveAutoPinCandidate(activeResult.roots)
                        if (autoCandidate != null) {
                            val autoPinned = PinnedSession(
                                sessionId = autoCandidate.id,
                                directory = autoCandidate.directory,
                                title = autoCandidate.title,
                            )
                            pinnedSession = autoPinned
                            tokenStore.updatePinnedSession(activeResult.server.id, autoPinned)
                        }
                    }
                } else if (!isConnected) {
                    onSessionExpiredAuthErrorState.value("Session expired — please sign in again")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                sessionsError = t.message ?: "Failed to load sessions"
            } finally {
                if (refreshSessionsJob === coroutineContext[Job]) {
                    isLoadingSessions = false
                }
            }
        }
        refreshSessionsJob = job
        job.start()
    }

    fun refreshVoices() {
        val active = tokenStore.getActiveServer()
        val serverId = active?.id
        val normalizedUrl = active?.serverUrl?.let { OpenChamberHttp.normalizeBaseUrl(it) }.orEmpty()
        if (serverId == null || normalizedUrl.isBlank() || active.token.isNullOrBlank()) {
            return
        }
        scope.launch {
            isLoadingVoices = true
            voicesError = null
            try {
                val catalog = ttsClient.fetchVoices(normalizedUrl)
                voices = catalog.voices
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if ((t as? TtsException)?.statusCode == 401) {
                    if (tokenStore.getActiveServer()?.id == serverId) {
                        boundService?.disarm()
                    }
                    tokenStore.clearAuth(serverId)
                    syncServerStateFromStore()
                    voicesError = "Session expired — please sign in again"
                } else {
                    voicesError = t.message ?: "Failed to load voices"
                }
            } finally {
                isLoadingVoices = false
            }
        }
    }

    fun refreshSystemVoices() {
        scope.launch {
            isLoadingSystemVoices = true
            try {
                systemVoices = TtsPlayer.querySystemTtsVoices(context)
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                isLoadingSystemVoices = false
            }
        }
    }

    fun refreshMlKitTtsStatus() {
        scope.launch {
            val (ttsAvail, ttsVoices) = mlKitTtsSpeaker.inspectStatus(context)
            mlKitTtsStatus = ttsAvail
            mlKitTtsVoices = ttsVoices
            if (selectedMlKitTtsVoice == null && ttsVoices.isNotEmpty()) {
                val pick = ttsVoices.firstOrNull { it.availability == MlKitAvailability.AVAILABLE }?.name
                    ?: ttsVoices.first().name
                selectedMlKitTtsVoice = pick
                tokenStore.setMlKitTtsVoice(pick)
            }
        }
    }

    fun downloadMlKitTtsVoice(targetVoiceName: String?) {
        scope.launch {
            isDownloadingMlKitTts = true
            mlKitTtsDownloadStatus = "Starting ML Kit TTS download…"
            try {
                mlKitTtsSpeaker.downloadVoice(targetVoiceName).collect { ev ->
                    when (ev) {
                        is MlKitDownloadEvent.Progress -> {
                            val mb = ev.bytesDownloaded / (1024.0 * 1024.0)
                            mlKitTtsDownloadStatus = "Downloading %.1f MB…".format(mb)
                        }
                        MlKitDownloadEvent.Completed -> {
                            mlKitTtsDownloadStatus = "Voice downloaded"
                            refreshMlKitTtsStatus()
                        }
                        is MlKitDownloadEvent.Failed -> {
                            mlKitTtsDownloadStatus = "Download failed: ${ev.message}"
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                mlKitTtsDownloadStatus = "Download failed: ${t.message}"
            } finally {
                isDownloadingMlKitTts = false
            }
        }
    }

    fun openSessionPicker() {
        refreshSessions()
        showSessionPicker = true
    }

    fun ensureVoiceCatalogLoaded() {
        refreshMlKitTtsStatus()
        if (systemVoices.isEmpty()) {
            refreshSystemVoices()
        }
        if (voices.isEmpty() && !isLoadingVoices) {
            refreshVoices()
        }
    }

    fun openVoicePicker() {
        val rolePipeline = VoiceRoleResolver.resolvePipelineForRole(ttsPipeline, roleVoiceProfiles.agent)
        val primary = VoicePipelineDefaults.primaryEnabledEngine(rolePipeline) ?: TtsEngine.GEMINI_BRIDGE
        openVoicePickerFor(primary, VoiceRole.AGENT)
    }

    fun openVoicePickerFor(targetEngine: TtsEngine, role: VoiceRole = VoiceRole.AGENT) {
        if (targetEngine == TtsEngine.GEMINI_BRIDGE && voices.isEmpty()) {
            refreshVoices()
        }
        voicePickerInitialEngine = targetEngine
        voicePickerInitialRole = role
        showVoicePicker = true
    }

    fun selectSession(targetServer: ServerProfile, summary: SessionSummary) {
        val updated = PinnedSession(
            sessionId = summary.id,
            directory = summary.directory,
            title = summary.title,
        )
        val serverChanged = targetServer.id != activeServer?.id
        tokenStore.setActiveServer(targetServer.id)
        tokenStore.updatePinnedSession(targetServer.id, updated)
        syncServerStateFromStore()
        boundService?.onActiveSessionOrServerChanged()
        if (serverChanged) {
            voices = emptyList()
            refreshVoices()
        }
        showSessionPicker = false
    }

    fun selectRoleGeminiVoice(role: VoiceRole, voiceId: String?) {
        tokenStore.saveRoleGeminiVoice(role, voiceId, customServerId)
        syncServerStateFromStore()
    }

    fun selectRoleMlKitVoice(role: VoiceRole, voiceName: String?) {
        tokenStore.setRoleMlKitVoice(role, voiceName)
        syncServerStateFromStore()
    }

    fun selectRoleOpenAiVoice(role: VoiceRole, voiceId: String?) {
        tokenStore.saveRoleOpenAiVoice(role, voiceId, customServerId)
        syncServerStateFromStore()
    }

    fun selectRoleSystemVoice(role: VoiceRole, voiceName: String?) {
        tokenStore.setRoleSystemVoice(role, voiceName)
        syncServerStateFromStore()
    }

    fun setRolePreferredEngine(role: VoiceRole, engine: TtsEngine?) {
        tokenStore.saveRolePreferredEngine(role, engine, customServerId)
        syncServerStateFromStore()
    }

    fun previewVoiceSample(
        role: VoiceRole,
        engine: TtsEngine,
        voiceId: String?,
        kokoroSpeakerId: Int = TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
    ) {
        val service = boundService
        if (service != null) {
            service.previewVoiceSample(
                role = role,
                engine = engine,
                voiceId = voiceId,
                kokoroSpeakerId = kokoroSpeakerId,
            )
        } else {
            Toast.makeText(
                context,
                "Voice service connecting — try preview again in a moment",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun stopVoicePreview() {
        boundService?.stopVoicePreview()
    }

    fun selectSpokenDetailLevel(level: SpokenDetailLevel) {
        spokenDetailLevel = level
        tokenStore.saveSpokenDetailLevel(level, customServerId)
        syncServerStateFromStore()
    }

    fun toggleCustomServerVoice(useCustom: Boolean) {
        val curActive = activeServer ?: return
        if (useCustom) {
            val effectiveRoles = tokenStore.getEffectiveRoleVoiceProfiles(curActive.id)
            tokenStore.setServerVoiceOverride(
                curActive.id,
                ServerVoiceOverride(
                    ttsPipeline = ttsPipeline,
                    ttsInputMode = ttsInputMode,
                    spokenDetailLevel = spokenDetailLevel,
                    geminiVoice = selectedVoice,
                    openAiVoice = selectedOpenAiVoice,
                    sttEngine = sttEngine,
                    sttFallbackToParakeet = sttFallbackToParakeet,
                    quickTurnDirectAnswer = quickTurnDirectAnswer,
                    quickTranscriptCleanup = quickTranscriptCleanup,
                    audioCueMode = audioCueMode,
                    agentRoleOverride = effectiveRoles.agent.toServerCloudOverride(),
                    operatorRoleOverride = effectiveRoles.operator.toServerCloudOverride(),
                    cueRoleOverride = effectiveRoles.cue.toServerCloudOverride(),
                ),
            )
        } else {
            tokenStore.setServerVoiceOverride(curActive.id, null)
        }
        syncServerStateFromStore()
    }

    fun updateTtsPipeline(updated: List<TtsEngineOption>) {
        ttsPipeline = updated
        tokenStore.saveTtsPipeline(updated, customServerId)
        syncServerStateFromStore()
    }

    fun selectTtsInputMode(mode: TtsInputMode) {
        ttsInputMode = mode
        tokenStore.saveTtsInputMode(mode, customServerId)
        syncServerStateFromStore()
    }

    fun selectSttEngine(engine: SttEngine) {
        sttEngine = engine
        tokenStore.saveSttEngine(engine, customServerId)
        syncServerStateFromStore()
    }

    fun toggleSttFallback(enabled: Boolean) {
        sttFallbackToParakeet = enabled
        tokenStore.saveSttFallbackToParakeet(enabled, customServerId)
        syncServerStateFromStore()
    }

    fun toggleQuickTurnDirectAnswer(enabled: Boolean) {
        quickTurnDirectAnswer = enabled
        tokenStore.saveQuickTurnDirectAnswer(enabled, customServerId)
        syncServerStateFromStore()
    }

    fun toggleQuickTranscriptCleanup(enabled: Boolean) {
        quickTranscriptCleanup = enabled
        tokenStore.saveQuickTranscriptCleanup(enabled, customServerId)
        syncServerStateFromStore()
    }

    fun selectAudioCueMode(mode: AudioCueMode) {
        audioCueMode = mode
        tokenStore.saveAudioCueMode(mode, customServerId)
        syncServerStateFromStore()
    }

    fun setVolumeKeyStop(enabled: Boolean) {
        volumeKeyStopRecording = enabled
        tokenStore.setVolumeKeyStopRecording(enabled)
        syncServerStateFromStore()
    }

    fun setTelecomCallMode(enabled: Boolean) {
        telecomCallForRecording = enabled
        tokenStore.setTelecomCallForRecording(enabled)
        syncServerStateFromStore()
    }

    fun selectBluetoothCaptureMode(mode: BluetoothCaptureMode) {
        bluetoothCaptureMode = mode
        telecomCallForRecording = mode == BluetoothCaptureMode.TELECOM_HFP
        tokenStore.setBluetoothCaptureMode(mode)
        syncServerStateFromStore()
    }

    fun selectTelecomHangupAction(action: TelecomHangupAction) {
        telecomHangupAction = action
        tokenStore.setTelecomHangupAction(action)
        syncServerStateFromStore()
    }

    fun selectHeadsetTapMode(mode: HeadsetTapMode) {
        headsetTapMode = mode
        tokenStore.setHeadsetTapMode(mode)
        syncServerStateFromStore()
    }

    fun toggleAvrcpTrackControls(enabled: Boolean) {
        avrcpTrackControlsEnabled = enabled
        tokenStore.setAvrcpTrackControlsEnabled(enabled)
        syncServerStateFromStore()
    }

    fun selectVolumeKeyRecordingMode(mode: VolumeKeyRecordingMode) {
        volumeKeyRecordingMode = mode
        volumeKeyStopRecording = mode.isEnabled
        tokenStore.setVolumeKeyRecordingMode(mode)
        syncServerStateFromStore()
    }

    fun selectVolumeKeyDraftMode(mode: VolumeKeyDraftMode) {
        volumeKeyDraftMode = mode
        tokenStore.setVolumeKeyDraftMode(mode)
        syncServerStateFromStore()
    }

    fun applyControlPreset(preset: ControlPreset) {
        tokenStore.applyControlPreset(preset)
        syncServerStateFromStore()
    }

    fun setMirrorRecordingNotif(enabled: Boolean) {
        mirrorRecordingNotification = enabled
        tokenStore.setMirrorRecordingNotification(enabled)
        syncServerStateFromStore()
    }

    fun switchToSignedInServer(targetServer: ServerProfile) {
        val changed = targetServer.id != activeServer?.id
        tokenStore.setActiveServer(targetServer.id)
        syncServerStateFromStore()
        if (changed) {
            voices = emptyList()
            refreshVoices()
            boundService?.onActiveSessionOrServerChanged()
        }
    }

    fun removeServer(targetServer: ServerProfile) {
        val wasActive = targetServer.id == activeServer?.id
        if (wasActive) {
            boundService?.disarm()
        }
        tokenStore.removeServer(targetServer.id)
        syncServerStateFromStore()
        if (wasActive) {
            voices = emptyList()
            if (isConnected) refreshVoices()
            boundService?.onActiveSessionOrServerChanged()
        }
        if (isConnected) {
            refreshSessions()
        } else {
            cancelSessionRefresh()
            serverGroups = emptyList()
        }
    }

    fun clearVoicesCache() {
        voices = emptyList()
    }

    fun clearServerGroups() {
        cancelSessionRefresh()
        serverGroups = emptyList()
    }

    fun clearSessionsError() {
        sessionsError = null
    }

    fun setPinSco(enabled: Boolean) {
        pinSco = enabled
        tokenStore.setPinBluetoothSco(enabled)
        boundService?.pinBluetoothSco = enabled
    }

    fun togglePinSco() {
        setPinSco(!pinSco)
    }

    fun armPtt() {
        val intent = PttForegroundService.startIntent(
            context = context,
            action = PttForegroundService.ACTION_ARM,
            pinSco = pinSco,
        )
        ContextCompat.startForegroundService(context, intent)
    }

    fun tapPtt() {
        val service = boundService
        if (service != null) {
            service.onSingleTap()
        } else {
            val intent = PttForegroundService.startIntent(
                context = context,
                action = PttForegroundService.ACTION_TOGGLE_PTT,
            )
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun disarmPtt() {
        val service = boundService
        if (service != null) {
            service.disarm()
        } else {
            val intent = PttForegroundService.startIntent(
                context = context,
                action = PttForegroundService.ACTION_DISARM,
            )
            context.startService(intent)
        }
    }

    fun stageCurrentRecording() {
        boundService?.stageCurrentRecording()
    }

    fun discardCurrent() {
        boundService?.discardCurrent()
    }

    fun pullBackToDraft() {
        boundService?.pullBackToDraft()
    }

    fun updateDraftText(text: String) {
        boundService?.updateDraftText(text)
    }

    fun commitStagedDraft() {
        boundService?.commitStagedDraft()
    }

    fun reorganizeCurrentDraft() {
        boundService?.reorganizeCurrentDraft()
    }

    fun revertDraftToPrevious() {
        boundService?.revertDraftToPrevious()
    }

    fun discardStagedDraft(draft: StagedDraft) {
        val service = boundService
        if (service != null) {
            service.discardStagedDraft()
        } else {
            tokenStore.clearStagedDraft(draft.sessionKey)
            syncServerStateFromStore()
        }
    }

    fun readPreviousMessage() {
        boundService?.readPreviousMessage()
    }

    fun selectResponse(messageId: String) {
        boundService?.selectResponse(messageId)
    }

    fun stepResponse(delta: Int) {
        boundService?.stepResponse(delta)
    }

    fun markAllResponsesRead() {
        boundService?.markAllResponsesRead()
    }

    fun dismissDirectAnswer() {
        boundService?.dismissDirectAnswer()
    }

    fun openInOfficialApp(fallbackServerUrl: String = activeServerUrl) {
        OpenChamberLauncher.openInOfficialApp(
            context = context,
            sessionId = pinnedSession?.sessionId,
            directory = pinnedSession?.directory,
            fallbackServerUrl = fallbackServerUrl,
        )
    }
}

@Composable
internal fun rememberVoiceCockpitState(
    tokenStore: TokenStore,
    sessionRepository: SessionRepository,
    ttsClient: TtsClient,
    boundService: PttForegroundService?,
    defaultStatusText: String = "Ready",
    requireMicForAutoLoad: Boolean = false,
    onSessionExpiredAuthError: (String) -> Unit = {},
): VoiceCockpitState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val showSessionPickerState = rememberSaveable { mutableStateOf(false) }
    val showVoicePickerState = rememberSaveable { mutableStateOf(false) }
    val voicePickerInitialEngineState = rememberSaveable {
        mutableStateOf(
            VoicePipelineDefaults.primaryEnabledEngine(tokenStore.getEffectiveTtsPipeline())
                ?: TtsEngine.GEMINI_BRIDGE,
        )
    }
    val voicePickerInitialRoleState = rememberSaveable { mutableStateOf(VoiceRole.AGENT) }

    val mlKitTtsSpeaker = remember {
        MlKitTtsSpeaker()
    }
    DisposableEffect(mlKitTtsSpeaker) {
        onDispose { mlKitTtsSpeaker.shutdown() }
    }

    val boundServiceState = rememberUpdatedState(boundService)
    val pttStateState = rememberUpdatedState(boundService?.state?.collectAsState()?.value ?: PttState.DISARMED)
    val statusTextState = rememberUpdatedState(boundService?.statusText?.collectAsState()?.value ?: defaultStatusText)
    val lastTranscriptState = rememberUpdatedState(boundService?.lastTranscript?.collectAsState()?.value)
    val lastReplyState = rememberUpdatedState(boundService?.lastReply?.collectAsState()?.value)
    val lastSpokenSummaryState = rememberUpdatedState(boundService?.lastSpokenSummary?.collectAsState()?.value)
    val lastTtsInfoState = rememberUpdatedState(boundService?.lastTtsInfo?.collectAsState()?.value)
    val serviceErrorState = rememberUpdatedState(boundService?.lastError?.collectAsState()?.value)
    val permissionPromptState = rememberUpdatedState(boundService?.permissionPrompt?.collectAsState()?.value)
    val recordingStartedAtState = rememberUpdatedState(boundService?.recordingStartedAtElapsedMs?.collectAsState()?.value)
    val serviceStagedDraft = boundService?.stagedDraft?.collectAsState()?.value
    val pendingQuickTranscriptState = rememberUpdatedState(boundService?.pendingQuickTranscript?.collectAsState()?.value)
    val sendGraceRemainingMsState = rememberUpdatedState(boundService?.sendGraceRemainingMs?.collectAsState()?.value)
    val isOrganizingDraftState = rememberUpdatedState(boundService?.isOrganizingDraft?.collectAsState()?.value ?: false)
    val responseFeedState = rememberUpdatedState(
        boundService?.responseFeed?.collectAsState()?.value ?: ResponseFeedState(),
    )
    val cuePackWarmStateState = rememberUpdatedState(
        boundService?.cuePackWarmState?.collectAsState()?.value ?: CuePackWarmState(),
    )
    val onSessionExpiredAuthErrorState = rememberUpdatedState(onSessionExpiredAuthError)

    val activeDraftHolder = remember { mutableStateOf<StagedDraft?>(null) }

    val state = remember(tokenStore, sessionRepository, ttsClient, context) {
        VoiceCockpitState(
            tokenStore = tokenStore,
            sessionRepository = sessionRepository,
            ttsClient = ttsClient,
            context = context,
            scope = scope,
            boundServiceState = boundServiceState,
            pttStateState = pttStateState,
            statusTextState = statusTextState,
            lastTranscriptState = lastTranscriptState,
            lastReplyState = lastReplyState,
            lastSpokenSummaryState = lastSpokenSummaryState,
            lastTtsInfoState = lastTtsInfoState,
            serviceErrorState = serviceErrorState,
            permissionPromptState = permissionPromptState,
            recordingStartedAtState = recordingStartedAtState,
            activeDraftState = activeDraftHolder,
            pendingQuickTranscriptState = pendingQuickTranscriptState,
            sendGraceRemainingMsState = sendGraceRemainingMsState,
            isOrganizingDraftState = isOrganizingDraftState,
            responseFeedState = responseFeedState,
            cuePackWarmStateState = cuePackWarmStateState,
            showSessionPickerState = showSessionPickerState,
            showVoicePickerState = showVoicePickerState,
            voicePickerInitialEngineState = voicePickerInitialEngineState,
            voicePickerInitialRoleState = voicePickerInitialRoleState,
            mlKitTtsSpeaker = mlKitTtsSpeaker,
            initialPinSco = boundService?.pinBluetoothSco ?: tokenStore.getPinBluetoothSco(),
            onSessionExpiredAuthErrorState = onSessionExpiredAuthErrorState,
        )
    }

    val persistedDraftFallback = remember(
        state.activeServer?.id,
        state.pinnedSession?.sessionId,
        state.pinnedSession?.directory,
        state.storeChangeVersion,
    ) {
        val srv = state.activeServer
        val ses = state.pinnedSession
        if (srv != null && ses != null) {
            tokenStore.getStagedDraft(TokenStore.sessionKeyFor(srv.id, ses))
        } else {
            null
        }
    }
    activeDraftHolder.value = serviceStagedDraft ?: persistedDraftFallback

    val servicePinSco = boundService?.pinBluetoothScoFlow?.collectAsState()?.value
    LaunchedEffect(servicePinSco) {
        if (servicePinSco != null) {
            state.pinSco = servicePinSco
        }
    }

    LaunchedEffect(state.showVoicePicker) {
        if (state.showVoicePicker) {
            state.ensureVoiceCatalogLoaded()
        }
    }

    LaunchedEffect(tokenStore, state) {
        tokenStore.changes.collect {
            state.syncServerStateFromStore()
        }
    }

    DisposableEffect(lifecycleOwner, boundService, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.refreshPermissionSnapshot()
                state.syncServerStateFromStore()
                boundService?.refreshLatestTurn(forceClearOnSessionChange = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shouldAutoLoad = if (requireMicForAutoLoad) {
        state.isOperateReady
    } else {
        state.isConnected
    }

    LaunchedEffect(shouldAutoLoad, state) {
        if (shouldAutoLoad) {
            state.refreshSessions()
        }
    }

    LaunchedEffect(boundService, shouldAutoLoad, state.pinnedSession?.sessionId) {
        if (boundService != null && shouldAutoLoad && state.pinnedSession != null) {
            boundService.refreshLatestTurn()
        }
    }

    return state
}

@Composable
internal fun VoiceCockpitSheets(
    state: VoiceCockpitState,
    onSignInServer: (ServerProfile) -> Unit,
    onAddServer: () -> Unit,
    onOpenSettings: () -> Unit,
    includeVoicePickerSheet: Boolean = true,
) {
    if (state.showSessionPicker) {
        SessionPickerSheet(
            serverGroups = state.serverGroups,
            activeServerId = state.activeServer?.id,
            pinnedSession = state.pinnedSession,
            isLoading = state.isLoadingSessions,
            error = state.sessionsError,
            onRefresh = { state.refreshSessions() },
            onSelect = { targetServer, summary ->
                state.selectSession(targetServer, summary)
            },
            onSignInServer = onSignInServer,
            onAddServer = onAddServer,
            onDismiss = { state.showSessionPicker = false },
        )
    }

    if (includeVoicePickerSheet && state.showVoicePicker) {
        VoicePickerSheet(
            ttsPipeline = state.ttsPipeline,
            roleProfiles = state.roleVoiceProfiles,
            initialRole = state.voicePickerInitialRole,
            geminiVoices = state.voices,
            isLoadingGemini = state.isLoadingVoices,
            geminiError = state.voicesError,
            onRefreshGemini = { state.refreshVoices() },
            onSelectGeminiVoice = { role, voiceId -> state.selectRoleGeminiVoice(role, voiceId) },
            mlKitStatus = state.mlKitTtsStatus,
            mlKitVoices = state.mlKitTtsVoices,
            mlKitDownloadStatus = state.mlKitTtsDownloadStatus,
            isDownloadingMlKit = state.isDownloadingMlKitTts,
            onRefreshMlKit = { state.refreshMlKitTtsStatus() },
            onSelectMlKitVoice = { role, voiceName -> state.selectRoleMlKitVoice(role, voiceName) },
            onDownloadMlKitVoice = { voiceName ->
                state.downloadMlKitTtsVoice(voiceName ?: state.selectedMlKitTtsVoice)
            },
            onSelectOpenAiVoice = { role, voiceId -> state.selectRoleOpenAiVoice(role, voiceId) },
            systemVoices = state.systemVoices,
            isLoadingSystemVoices = state.isLoadingSystemVoices,
            onRefreshSystemVoices = { state.refreshSystemVoices() },
            onSelectSystemVoice = { role, voiceName -> state.selectRoleSystemVoice(role, voiceName) },
            onSetRolePreferredEngine = { role, engine -> state.setRolePreferredEngine(role, engine) },
            onPreviewVoice = { role, engine, voiceId ->
                state.previewVoiceSample(
                    role = role,
                    engine = engine,
                    voiceId = voiceId,
                    kokoroSpeakerId = state.roleVoiceProfiles[role].kokoroSpeakerId
                        ?: TtsClient.DEFAULT_KOKORO_SPEAKER_ID,
                )
            },
            onOpenSettings = onOpenSettings,
            onDismiss = {
                state.stopVoicePreview()
                state.showVoicePicker = false
            },
        )
    }
}
