package com.openchamber.ptt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.auth.AuthClient
import com.openchamber.ptt.bubble.BubbleAvailability
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.logging.VoiceUxLogger
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.service.PttForegroundService
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.session.SessionRepository
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.ui.HeroPttControl
import com.openchamber.ptt.ui.PermissionCheckRow
import com.openchamber.ptt.ui.PermissionStatusSnapshot
import com.openchamber.ptt.ui.PermissionWarningBanner
import com.openchamber.ptt.ui.ServerConnectionFields
import com.openchamber.ptt.ui.SessionPill
import com.openchamber.ptt.ui.SettingsBottomSheet
import com.openchamber.ptt.ui.SpokenDetailSlider
import com.openchamber.ptt.ui.StagedDraftCard
import com.openchamber.ptt.ui.StatusPill
import com.openchamber.ptt.ui.TurnTranscriptPanel
import com.openchamber.ptt.ui.VoiceCockpitSheets
import com.openchamber.ptt.ui.VoiceUxLogSheet
import com.openchamber.ptt.ui.launchBatteryOptimizationSettings
import com.openchamber.ptt.ui.rememberVoiceCockpitState
import com.openchamber.ptt.ui.theme.OpenChamberPttTheme
import com.openchamber.ptt.voice.MlKitDownloadEvent
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var boundService by mutableStateOf<PttForegroundService?>(null)
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PttForegroundService.PttBinder
            boundService = binder?.service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContext = applicationContext
        val tokenStore = TokenStore(appContext)
        val httpClient = OpenChamberHttp.createClient(
            tokenForUrlProvider = { url -> tokenStore.getTokenForUrl(url) },
        )
        val authClient = AuthClient(httpClient)
        val sessionRepository = SessionRepository(httpClient)
        val ttsClient = TtsClient(httpClient)

        setContent {
            OpenChamberPttTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    OpenChamberPttScreen(
                        tokenStore = tokenStore,
                        authClient = authClient,
                        sessionRepository = sessionRepository,
                        ttsClient = ttsClient,
                        boundService = boundService,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PttForegroundService::class.java)
        isServiceBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (isServiceBound) {
            runCatching { unbindService(serviceConnection) }
            isServiceBound = false
        }
        boundService = null
        super.onStop()
    }

    companion object {
        const val DEFAULT_SERVER_URL = ""
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OpenChamberPttScreen(
    tokenStore: TokenStore,
    authClient: AuthClient,
    sessionRepository: SessionRepository,
    ttsClient: TtsClient,
    boundService: PttForegroundService?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var authError by remember { mutableStateOf<String?>(null) }
    val cockpit = rememberVoiceCockpitState(
        tokenStore = tokenStore,
        sessionRepository = sessionRepository,
        ttsClient = ttsClient,
        boundService = boundService,
        defaultStatusText = "Ready",
        requireMicForAutoLoad = false,
        onSessionExpiredAuthError = { authError = it },
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        cockpit.refreshPermissionSnapshot()
    }

    var serverUrl by rememberSaveable {
        mutableStateOf(cockpit.activeServer?.serverUrl ?: MainActivity.DEFAULT_SERVER_URL)
    }
    var serverLabelInput by rememberSaveable { mutableStateOf(cockpit.activeServer?.label.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var isAuthenticating by remember { mutableStateOf(false) }

    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var openAddServerInSettings by rememberSaveable { mutableStateOf(false) }
    var showTelemetrySheet by rememberSaveable { mutableStateOf(false) }
    val fallbackUxLogger = remember(context) { VoiceUxLogger(context) }
    DisposableEffect(fallbackUxLogger) {
        onDispose { fallbackUxLogger.close() }
    }
    var uxSummary by remember { mutableStateOf(VoiceUxLogger.EMPTY_SUMMARY) }

    fun refreshTelemetrySummary() {
        scope.launch(Dispatchers.IO) {
            val next = boundService?.readVoiceUxSummary() ?: fallbackUxLogger.readSummary()
            withContext(Dispatchers.Main) {
                uxSummary = next
            }
        }
    }

    val mlKitClient = remember { MlKitSttClient() }
    var mlKitAdvancedStatus by remember { mutableStateOf<MlKitStatusReport?>(null) }
    var mlKitBasicStatus by remember { mutableStateOf<MlKitStatusReport?>(null) }
    var mlKitDownloadStatus by remember { mutableStateOf<String?>(null) }
    var isDownloadingMlKit by remember { mutableStateOf(false) }

    LaunchedEffect(cockpit.activeServer?.id, cockpit.activeServer?.serverUrl) {
        val active = cockpit.activeServer
        if (active != null) {
            serverUrl = active.serverUrl
        }
    }

    fun refreshMlKitSttStatuses() {
        scope.launch {
            mlKitAdvancedStatus = mlKitClient.checkAvailability(SttEngine.MLKIT_GENAI_ADVANCED, context)
            mlKitBasicStatus = mlKitClient.checkAvailability(SttEngine.MLKIT_GENAI_BASIC, context)
        }
    }

    LaunchedEffect(showSettingsSheet) {
        if (showSettingsSheet) {
            refreshMlKitSttStatuses()
            cockpit.refreshMlKitTtsStatus()
        }
    }

    fun performConnect() {
        val normalizedUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        if (normalizedUrl.isBlank()) {
            authError = "Server URL is required"
            return
        }
        if (password.isBlank()) {
            authError = "Password is required"
            return
        }
        scope.launch {
            isAuthenticating = true
            authError = null
            try {
                val token = authClient.authenticate(
                    serverUrl = normalizedUrl,
                    password = password,
                    dedupeKey = "mobile:android:ptt:${tokenStore.getOrCreateInstallId()}",
                )
                val prevActiveId = tokenStore.getActiveServer()?.id
                val profile = tokenStore.upsertServer(
                    serverUrl = normalizedUrl,
                    label = serverLabelInput.takeIf { it.isNotBlank() },
                    token = token,
                    makeActive = true,
                )
                serverUrl = profile.serverUrl
                serverLabelInput = ""
                password = ""
                openAddServerInSettings = false
                cockpit.syncServerStateFromStore()
                if (prevActiveId != profile.id) {
                    cockpit.clearVoicesCache()
                    boundService?.onActiveSessionOrServerChanged()
                }
                cockpit.refreshSessions()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                authError = t.message ?: "Authentication failed"
            } finally {
                isAuthenticating = false
            }
        }
    }

    fun performDisconnectActiveServer() {
        val target = tokenStore.getActiveServer()
        if (target != null) {
            boundService?.disarm()
            tokenStore.clearAuth(target.id)
        }
        BubbleHelper.dismissBubble(context)
        cockpit.syncServerStateFromStore()
        if (!cockpit.isConnected) {
            cockpit.clearServerGroups()
        } else {
            cockpit.refreshSessions()
        }
        authError = null
        cockpit.clearSessionsError()
    }

    fun launchBubble() {
        cockpit.refreshPermissionSnapshot()
        val snapshot = cockpit.permissionSnapshot
        when (snapshot.bubbleAvailability) {
            BubbleAvailability.AVAILABLE -> {
                val focused = cockpit.focusedResponse
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
                val bubblePrompt = cockpit.lastTranscript?.takeIf { it.isNotBlank() } ?: triggerPrompt
                val rawReply = cockpit.lastReply?.takeIf { it.isNotBlank() }
                    ?: focused?.assistantText?.takeIf { it.isNotBlank() }
                val unreadCount = cockpit.unreadCount
                val bubbleReply = when {
                    unreadCount > 0 && rawReply != null -> "$unreadCount unread · $rawReply"
                    unreadCount > 0 -> "$unreadCount unread"
                    else -> rawReply
                }
                val ok = BubbleHelper.showOrUpdateBubble(
                    context = context,
                    sessionTitle = cockpit.pinnedSession?.title ?: "OpenChamber Voice",
                    statusSummary = cockpit.statusText,
                    userPrompt = bubblePrompt,
                    agentReply = bubbleReply,
                    autoExpand = true,
                )
                if (!ok) {
                    scope.launch {
                        val res = snackbarHostState.showSnackbar(
                            message = "Could not open floating bubble — check notification bubble settings",
                            actionLabel = "Settings",
                            duration = SnackbarDuration.Long,
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            BubbleHelper.openBubbleSettings(context)
                        }
                    }
                }
            }

            BubbleAvailability.DISABLED_BY_USER -> {
                scope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = "Allow Bubbles for OpenChamber PTT in Android settings",
                        actionLabel = "Settings",
                        duration = SnackbarDuration.Long,
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        BubbleHelper.openBubbleSettings(context)
                    }
                }
            }

            BubbleAvailability.NOTIFICATIONS_DISABLED -> {
                val missing = snapshot.missingPermissions
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                } else {
                    BubbleHelper.openBubbleSettings(context)
                }
            }
        }
    }

    val isOperateReady = cockpit.isOperateReady

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "OpenChamber",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (isOperateReady) {
                        IconButton(onClick = { launchBubble() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_bubble),
                                contentDescription = "Float voice bubble",
                            )
                        }
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Box {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = if (cockpit.permissionSnapshot.needsAttention) {
                                    "Settings, permissions or sign-in attention needed"
                                } else {
                                    "Settings"
                                },
                            )
                            if (cockpit.permissionSnapshot.needsAttention) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .background(
                                            color = MaterialTheme.colorScheme.error,
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            val isWideLayout = (maxWidth >= 600.dp || (maxWidth >= 540.dp && maxWidth > maxHeight)) &&
                isOperateReady

            @Composable
            fun renderPrimaryControls() {
                SessionPill(
                    pinnedSession = cockpit.pinnedSession,
                    isConnected = cockpit.isConnected,
                    isLoading = cockpit.isLoadingSessions,
                    serverLabel = cockpit.activeServer?.displayLabel,
                    showServerLabel = cockpit.servers.size > 1,
                    onClick = { cockpit.openSessionPicker() },
                )

                if (!cockpit.sessionsError.isNullOrBlank()) {
                    Text(
                        text = cockpit.sessionsError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (cockpit.pttState == PttState.PERMISSION_BLOCKED) {
                    PermissionWarningBanner(
                        toolName = cockpit.permissionPrompt ?: "tool",
                        canReArm = cockpit.canArm,
                        onOpenOpenChamber = {
                            cockpit.openInOfficialApp(fallbackServerUrl = serverUrl)
                        },
                        onReArm = { cockpit.armPtt() },
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusPill(pttState = cockpit.pttState)
                }

                HeroPttControl(
                    pttState = cockpit.pttState,
                    statusText = cockpit.statusText,
                    recordingStartedAtElapsedMs = cockpit.recordingStartedAt,
                    canArm = cockpit.canArm,
                    compact = isWideLayout,
                    onArm = { cockpit.armPtt() },
                    onTapPtt = { cockpit.tapPtt() },
                    onStageCapture = { cockpit.stageCurrentRecording() },
                    onDiscard = { cockpit.discardCurrent() },
                    onPullBackToDraft = { cockpit.pullBackToDraft() },
                    sendGraceRemainingMs = cockpit.sendGraceRemainingMs,
                    pendingQuickTranscript = cockpit.pendingQuickTranscript,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = cockpit.pinSco,
                        onClick = { cockpit.togglePinSco() },
                        label = { Text("Headset mic") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_bluetooth_audio),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )

                    AssistChip(
                        onClick = { cockpit.openVoicePicker() },
                        label = {
                            Text(
                                text = cockpit.activeVoiceChipLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_graphic_eq),
                                contentDescription = "Voice",
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )

                    if (cockpit.pttState != PttState.DISARMED) {
                        AssistChip(
                            onClick = { cockpit.disarmPtt() },
                            label = { Text("Disarm") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_power),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }

            @Composable
            fun renderSecondaryContent() {
                if (cockpit.ttsInputMode == TtsInputMode.SUMMARIZED) {
                    SpokenDetailSlider(
                        detailLevel = cockpit.spokenDetailLevel,
                        onSelectLevel = { cockpit.selectSpokenDetailLevel(it) },
                    )
                }

                val activeDraft = cockpit.activeDraft
                if (activeDraft != null && cockpit.pttState != PttState.WAITING_AGENT) {
                    StagedDraftCard(
                        draft = activeDraft,
                        pttState = cockpit.pttState,
                        isOrganizing = cockpit.isOrganizingDraft,
                        onUpdateDraftText = { cockpit.updateDraftText(it) },
                        onCommitDraft = { cockpit.commitStagedDraft() },
                        onReorganizeDraft = { cockpit.reorganizeCurrentDraft() },
                        onRevertDraft = { cockpit.revertDraftToPrevious() },
                        onDiscardDraft = { cockpit.discardStagedDraft(activeDraft) },
                    )
                }

                TurnTranscriptPanel(
                    lastTranscript = cockpit.lastTranscript,
                    lastReply = cockpit.lastReply,
                    lastSpokenSummary = cockpit.lastSpokenSummary,
                    lastTtsInfo = cockpit.lastTtsInfo,
                    serviceError = cockpit.serviceError,
                    pttState = cockpit.pttState,
                    responseFeed = cockpit.responseFeed,
                    showQueueStrip = true,
                    isSpeaking = cockpit.pttState == PttState.SPEAKING_TTS,
                    canReadAloud = cockpit.canReadAloud,
                    onReadAloudClick = { cockpit.readPreviousMessage() },
                    onStepResponse = { delta -> cockpit.stepResponse(delta) },
                    onSelectResponse = { messageId -> cockpit.selectResponse(messageId) },
                    onMarkAllRead = { cockpit.markAllResponsesRead() },
                    onDismissDirectAnswer = { cockpit.dismissDirectAnswer() },
                    onOpenInOpenChamber = if (cockpit.pinnedSession != null || serverUrl.isNotBlank()) {
                        { cockpit.openInOfficialApp(fallbackServerUrl = serverUrl) }
                    } else {
                        null
                    },
                )
            }

            if (!isOperateReady) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SetupFlowContent(
                        snapshot = cockpit.permissionSnapshot,
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it },
                        password = password,
                        onPasswordChange = { password = it },
                        showPassword = showPassword,
                        onToggleShowPassword = { showPassword = !showPassword },
                        isConnected = cockpit.isConnected,
                        isAuthenticating = isAuthenticating,
                        authError = authError,
                        onRequestPermissions = {
                            val missing = cockpit.permissionSnapshot.missingPermissions
                            if (missing.isNotEmpty()) {
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        },
                        onRequestBatteryExemption = {
                            launchBatteryOptimizationSettings(context)
                        },
                        onConnect = { performConnect() },
                    )
                }
            } else if (isWideLayout) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        renderPrimaryControls()
                    }
                    Column(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        renderSecondaryContent()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 560.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        renderPrimaryControls()
                        renderSecondaryContent()
                    }
                }
            }
        }
    }

    VoiceCockpitSheets(
        state = cockpit,
        onSignInServer = { targetServer ->
            serverUrl = targetServer.serverUrl
            serverLabelInput = targetServer.label
            password = ""
            authError = null
            openAddServerInSettings = true
            showSettingsSheet = true
        },
        onAddServer = {
            serverUrl = ""
            serverLabelInput = ""
            password = ""
            authError = null
            openAddServerInSettings = true
            showSettingsSheet = true
        },
        onOpenSettings = { showSettingsSheet = true },
    )

    if (showSettingsSheet) {
        SettingsBottomSheet(
            snapshot = cockpit.permissionSnapshot,
            servers = cockpit.servers,
            activeServer = cockpit.activeServer,
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            serverLabel = serverLabelInput,
            onServerLabelChange = { serverLabelInput = it },
            password = password,
            onPasswordChange = { password = it },
            showPassword = showPassword,
            onToggleShowPassword = { showPassword = !showPassword },
            isConnected = cockpit.isConnected,
            isAuthenticating = isAuthenticating,
            authError = authError,
            startWithAddServerOpen = openAddServerInSettings,
            onSelectServer = { targetServer ->
                if (targetServer.isSignedIn) {
                    cockpit.switchToSignedInServer(targetServer)
                    serverUrl = targetServer.serverUrl
                } else {
                    serverUrl = targetServer.serverUrl
                    serverLabelInput = targetServer.label
                    password = ""
                    authError = null
                }
            },
            onRemoveServer = { targetServer ->
                cockpit.removeServer(targetServer)
            },
            isUsingCustomServerVoice = cockpit.customServerId != null,
            onToggleCustomServerVoice = { useCustom ->
                cockpit.toggleCustomServerVoice(useCustom)
            },
            ttsPipeline = cockpit.ttsPipeline,
            onUpdateTtsPipeline = { updated ->
                cockpit.updateTtsPipeline(updated)
            },
            ttsInputMode = cockpit.ttsInputMode,
            onSelectTtsInputMode = { mode ->
                cockpit.selectTtsInputMode(mode)
            },
            spokenDetailLevel = cockpit.spokenDetailLevel,
            onSelectSpokenDetailLevel = { level ->
                cockpit.selectSpokenDetailLevel(level)
            },
            selectedGeminiVoice = cockpit.selectedVoice,
            selectedOpenAiVoice = cockpit.selectedOpenAiVoice,
            selectedSystemVoiceLabel = cockpit.selectedSystemVoiceLabel,
            onOpenVoicePickerFor = { targetEngine ->
                showSettingsSheet = false
                cockpit.openVoicePickerFor(targetEngine)
            },
            sttEngine = cockpit.sttEngine,
            onSelectSttEngine = { engine ->
                cockpit.selectSttEngine(engine)
            },
            sttFallbackToParakeet = cockpit.sttFallbackToParakeet,
            onToggleSttFallback = { enabled ->
                cockpit.toggleSttFallback(enabled)
            },
            mlKitAdvancedStatus = mlKitAdvancedStatus,
            mlKitBasicStatus = mlKitBasicStatus,
            mlKitDownloadStatus = mlKitDownloadStatus,
            isDownloadingMlKit = isDownloadingMlKit,
            mlKitTtsStatus = cockpit.mlKitTtsStatus,
            mlKitTtsVoicesCount = cockpit.mlKitTtsVoices.size,
            selectedMlKitTtsVoice = cockpit.selectedMlKitTtsVoice,
            onDownloadMlKitModel = { targetEngine ->
                scope.launch {
                    isDownloadingMlKit = true
                    mlKitDownloadStatus = "Starting download…"
                    try {
                        mlKitClient.downloadModel(targetEngine).collect { ev ->
                            when (ev) {
                                is MlKitDownloadEvent.Progress -> {
                                    val mb = ev.bytesDownloaded / (1024.0 * 1024.0)
                                    mlKitDownloadStatus = "Downloading %.1f MB…".format(mb)
                                }
                                MlKitDownloadEvent.Completed -> {
                                    mlKitDownloadStatus = "Model downloaded"
                                    refreshMlKitSttStatuses()
                                    cockpit.refreshMlKitTtsStatus()
                                }
                                is MlKitDownloadEvent.Failed -> {
                                    mlKitDownloadStatus = "Download failed: ${ev.message}"
                                }
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        mlKitDownloadStatus = "Download failed: ${t.message}"
                    } finally {
                        isDownloadingMlKit = false
                    }
                }
            },
            onRequestPermissions = {
                val missing = cockpit.permissionSnapshot.missingPermissions
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            onRequestBatteryExemption = {
                launchBatteryOptimizationSettings(context)
            },
            onOpenBubbleSettings = {
                BubbleHelper.openBubbleSettings(context)
            },
            onOpenTelemetryLog = {
                showSettingsSheet = false
                showTelemetrySheet = true
                refreshTelemetrySummary()
            },
            onConnect = { performConnect() },
            onDisconnect = {
                performDisconnectActiveServer()
                if (!cockpit.isConnected) {
                    showSettingsSheet = false
                }
            },
            onDismiss = {
                openAddServerInSettings = false
                showSettingsSheet = false
            },
        )
    }

    LaunchedEffect(showTelemetrySheet) {
        if (showTelemetrySheet) {
            refreshTelemetrySummary()
        }
    }

    if (showTelemetrySheet) {
        @Suppress("DEPRECATION")
        val clipboardManager = LocalClipboardManager.current
        VoiceUxLogSheet(
            summary = uxSummary,
            onRefresh = { refreshTelemetrySummary() },
            onCopyJsonl = {
                scope.launch(Dispatchers.IO) {
                    val jsonl = boundService?.exportVoiceUxLog() ?: fallbackUxLogger.exportJsonl()
                    withContext(Dispatchers.Main) {
                        clipboardManager.setText(AnnotatedString(jsonl))
                        Toast.makeText(context, "Copied Voice UX JSONL log", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onClear = {
                scope.launch(Dispatchers.IO) {
                    boundService?.clearVoiceUxLog() ?: fallbackUxLogger.clear()
                    val next = boundService?.readVoiceUxSummary() ?: fallbackUxLogger.readSummary()
                    withContext(Dispatchers.Main) {
                        uxSummary = next
                    }
                }
            },
            onDismiss = { showTelemetrySheet = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupFlowContent(
    snapshot: PermissionStatusSnapshot,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    isConnected: Boolean,
    isAuthenticating: Boolean,
    authError: String?,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onConnect: () -> Unit,
) {
    val showServerFirst = snapshot.hasRecordAudio
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (showServerFirst) "Connect to OpenChamber" else "Set up voice mode",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (showServerFirst) {
                    "Sign in to load your sessions and arm push-to-talk."
                } else {
                    "Grant microphone access and connect to your OpenChamber server once."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val serverCard: @Composable () -> Unit = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "OpenChamber Server",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (isConnected) {
                            Text(
                                text = "Connected",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    ServerConnectionFields(
                        serverUrl = serverUrl,
                        onServerUrlChange = onServerUrlChange,
                        password = password,
                        onPasswordChange = onPasswordChange,
                        showPassword = showPassword,
                        onToggleShowPassword = onToggleShowPassword,
                        isConnected = isConnected,
                        isAuthenticating = isAuthenticating,
                        authError = authError,
                        onConnect = onConnect,
                    )
                }
            }
        }

        val permissionsCard: @Composable () -> Unit = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    PermissionCheckRow(label = "Microphone (voice capture)", granted = snapshot.hasRecordAudio)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PermissionCheckRow(label = "Nearby devices (Bluetooth headset)", granted = snapshot.hasBluetoothConnect)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionCheckRow(label = "Notifications (Bubbles & status)", granted = snapshot.hasPostNotifications)
                    }
                    PermissionCheckRow(
                        label = "Unrestricted battery (optional)",
                        granted = snapshot.isIgnoringBatteryOptimizations,
                    )

                    if (!snapshot.allRuntimeGranted || !snapshot.isIgnoringBatteryOptimizations) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!snapshot.allRuntimeGranted) {
                                Button(onClick = onRequestPermissions) {
                                    Text("Grant Permissions")
                                }
                            }
                            if (!snapshot.isIgnoringBatteryOptimizations) {
                                OutlinedButton(onClick = onRequestBatteryExemption) {
                                    Text("Battery Exempt")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showServerFirst) {
            serverCard()
            if (!snapshot.allRuntimeGranted || !snapshot.isIgnoringBatteryOptimizations) {
                permissionsCard()
            }
        } else {
            permissionsCard()
            serverCard()
        }
    }
}
