package com.openchamber.ptt

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openchamber.ptt.auth.AuthClient
import com.openchamber.ptt.bubble.BubbleAvailability
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.network.OpenChamberLauncher
import com.openchamber.ptt.service.PttForegroundService
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.session.ServerSessionsResult
import com.openchamber.ptt.session.SessionApiException
import com.openchamber.ptt.session.SessionRepository
import com.openchamber.ptt.session.SessionSummary
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import com.openchamber.ptt.storage.ServerVoiceOverride
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.ui.HeroPttControl
import com.openchamber.ptt.ui.PermissionWarningBanner
import com.openchamber.ptt.ui.SessionPickerSheet
import com.openchamber.ptt.ui.SessionPill
import com.openchamber.ptt.ui.StatusPill
import com.openchamber.ptt.ui.TurnTranscriptPanel
import com.openchamber.ptt.ui.VoicePickerSheet
import com.openchamber.ptt.ui.theme.OpenChamberPttTheme
import com.openchamber.ptt.tts.MlKitTtsSpeaker
import com.openchamber.ptt.tts.MlKitTtsVoiceSummary
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.SystemTtsVoiceSummary
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsException
import com.openchamber.ptt.tts.TtsPlayer
import com.openchamber.ptt.tts.TtsVoice
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitDownloadEvent
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient
import kotlinx.coroutines.launch

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

private data class PermissionStatusSnapshot(
    val hasRecordAudio: Boolean,
    val hasBluetoothConnect: Boolean,
    val hasPostNotifications: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val bubbleAvailability: BubbleAvailability,
) {
    val missingPermissions: List<String>
        get() = buildList {
            if (!hasRecordAudio) add(Manifest.permission.RECORD_AUDIO)
            if (!hasBluetoothConnect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!hasPostNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    val allRuntimeGranted: Boolean
        get() = hasRecordAudio && hasBluetoothConnect && hasPostNotifications

    val needsAttention: Boolean
        get() = !allRuntimeGranted || !isIgnoringBatteryOptimizations
}

private fun readPermissionSnapshot(context: Context): PermissionStatusSnapshot {
    val hasMic = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    val hasBt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    val powerManager = context.getSystemService(PowerManager::class.java)
    val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    return PermissionStatusSnapshot(
        hasRecordAudio = hasMic,
        hasBluetoothConnect = hasBt,
        hasPostNotifications = hasNotif,
        isIgnoringBatteryOptimizations = batteryExempt,
        bubbleAvailability = BubbleHelper.checkAvailability(context),
    )
}

@SuppressLint("BatteryLife")
private fun launchBatteryOptimizationSettings(context: Context) {
    val requestIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    val launched = runCatching {
        context.startActivity(requestIntent)
    }.isSuccess
    if (!launched) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var permissionSnapshot by remember { mutableStateOf(readPermissionSnapshot(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionSnapshot = readPermissionSnapshot(context)
    }

    var servers by remember { mutableStateOf(tokenStore.getServers()) }
    var activeServer by remember { mutableStateOf(tokenStore.getActiveServer()) }
    var serverUrl by remember {
        mutableStateOf(activeServer?.serverUrl ?: MainActivity.DEFAULT_SERVER_URL)
    }
    var serverLabelInput by remember { mutableStateOf(activeServer?.label.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(servers.any { it.isSignedIn }) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    var serverGroups by remember { mutableStateOf<List<ServerSessionsResult>>(emptyList()) }
    var pinnedSession by remember { mutableStateOf(tokenStore.getPinnedSession()) }
    var isLoadingSessions by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }

    var showSessionPicker by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var voicePickerInitialEngine by remember {
        mutableStateOf(VoicePipelineDefaults.primaryEnabledEngine(tokenStore.getEffectiveTtsPipeline()) ?: TtsEngine.GEMINI_BRIDGE)
    }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var openAddServerInSettings by remember { mutableStateOf(false) }

    var selectedVoice by remember { mutableStateOf(tokenStore.getEffectiveTtsVoice()) }
    var voices by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }
    var isLoadingVoices by remember { mutableStateOf(false) }
    var voicesError by remember { mutableStateOf<String?>(null) }

    var selectedOpenAiVoice by remember { mutableStateOf(tokenStore.getEffectiveOpenAiTtsVoice()) }
    var selectedSystemVoice by remember { mutableStateOf(tokenStore.getSystemTtsVoice()) }
    var systemVoices by remember { mutableStateOf<List<SystemTtsVoiceSummary>>(emptyList()) }
    var isLoadingSystemVoices by remember { mutableStateOf(false) }

    var ttsPipeline by remember { mutableStateOf(tokenStore.getEffectiveTtsPipeline()) }
    var sttEngine by remember { mutableStateOf(tokenStore.getEffectiveSttEngine()) }
    var sttFallbackToParakeet by remember { mutableStateOf(tokenStore.getEffectiveSttFallbackToParakeet()) }
    val mlKitClient = remember { MlKitSttClient() }
    var mlKitAdvancedStatus by remember { mutableStateOf<MlKitStatusReport?>(null) }
    var mlKitBasicStatus by remember { mutableStateOf<MlKitStatusReport?>(null) }
    var mlKitDownloadStatus by remember { mutableStateOf<String?>(null) }
    var isDownloadingMlKit by remember { mutableStateOf(false) }

    val mlKitTtsSpeaker = remember { MlKitTtsSpeaker(preferredVoiceNameProvider = { tokenStore.getMlKitTtsVoice() }) }
    DisposableEffect(mlKitTtsSpeaker) {
        onDispose { mlKitTtsSpeaker.shutdown() }
    }
    var mlKitTtsStatus by remember { mutableStateOf<MlKitStatusReport?>(null) }
    var mlKitTtsVoices by remember { mutableStateOf<List<MlKitTtsVoiceSummary>>(emptyList()) }
    var selectedMlKitTtsVoice by remember { mutableStateOf(tokenStore.getMlKitTtsVoice()) }
    var mlKitTtsDownloadStatus by remember { mutableStateOf<String?>(null) }
    var isDownloadingMlKitTts by remember { mutableStateOf(false) }

    fun refreshSystemVoices() {
        scope.launch {
            isLoadingSystemVoices = true
            try {
                systemVoices = TtsPlayer.querySystemTtsVoices(context)
            } finally {
                isLoadingSystemVoices = false
            }
        }
    }

    fun refreshMlKitStatuses() {
        scope.launch {
            mlKitAdvancedStatus = mlKitClient.checkAvailability(SttEngine.MLKIT_GENAI_ADVANCED, context)
            mlKitBasicStatus = mlKitClient.checkAvailability(SttEngine.MLKIT_GENAI_BASIC, context)
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

    LaunchedEffect(showSettingsSheet, showVoicePicker) {
        if (showSettingsSheet || showVoicePicker) {
            refreshMlKitStatuses()
        }
        if (showVoicePicker && systemVoices.isEmpty()) {
            refreshSystemVoices()
        }
    }

    var pinSco by remember { mutableStateOf(boundService?.pinBluetoothSco ?: false) }

    val pttState = boundService?.state?.collectAsState()?.value ?: PttState.DISARMED
    val statusText = boundService?.statusText?.collectAsState()?.value ?: "Ready"
    val lastTranscript = boundService?.lastTranscript?.collectAsState()?.value
    val lastReply = boundService?.lastReply?.collectAsState()?.value
    val lastTtsInfo = boundService?.lastTtsInfo?.collectAsState()?.value
    val serviceError = boundService?.lastError?.collectAsState()?.value
    val permissionPrompt = boundService?.permissionPrompt?.collectAsState()?.value
    val recordingStartedAt = boundService?.recordingStartedAtElapsedMs?.collectAsState()?.value
    val servicePinSco = boundService?.pinBluetoothScoFlow?.collectAsState()?.value

    LaunchedEffect(servicePinSco) {
        if (servicePinSco != null) {
            pinSco = servicePinSco
        }
    }

    fun syncEffectiveVoiceState(serverId: String? = tokenStore.getActiveServer()?.id) {
        selectedVoice = tokenStore.getEffectiveTtsVoice(serverId)
        selectedOpenAiVoice = tokenStore.getEffectiveOpenAiTtsVoice(serverId)
        ttsPipeline = tokenStore.getEffectiveTtsPipeline(serverId)
        sttEngine = tokenStore.getEffectiveSttEngine(serverId)
        sttFallbackToParakeet = tokenStore.getEffectiveSttFallbackToParakeet(serverId)
    }

    fun syncServerStateFromStore() {
        val list = tokenStore.getServers()
        val active = tokenStore.getActiveServer()
        servers = list
        activeServer = active
        if (active != null) {
            serverUrl = active.serverUrl
        }
        pinnedSession = active?.lastPinnedSession
        isConnected = list.any { it.isSignedIn }
        syncEffectiveVoiceState(active?.id)
    }

    DisposableEffect(lifecycleOwner, boundService) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionSnapshot = readPermissionSnapshot(context)
                syncServerStateFromStore()
                boundService?.refreshLatestTurn(forceClearOnSessionChange = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun refreshSessions() {
        val currentServers = tokenStore.getServers()
        if (currentServers.none { it.isSignedIn }) {
            servers = currentServers
            activeServer = tokenStore.getActiveServer()
            isConnected = false
            return
        }
        scope.launch {
            isLoadingSessions = true
            sessionsError = null
            try {
                val results = sessionRepository.listAllServerSessions(currentServers)
                results.filter { it.isUnauthorized && it.server.isSignedIn }.forEach { failed ->
                    if (failed.server.id == tokenStore.getActiveServer()?.id) {
                        boundService?.disarm()
                    }
                    tokenStore.clearAuth(failed.server.id)
                }
                val updatedServers = tokenStore.getServers()
                servers = updatedServers
                val curActive = tokenStore.getActiveServer()
                activeServer = curActive
                isConnected = updatedServers.any { it.isSignedIn }

                val refreshedResults = results.map { res ->
                    val freshServer = updatedServers.firstOrNull { it.id == res.server.id } ?: res.server
                    res.copy(server = freshServer)
                }
                serverGroups = refreshedResults

                val activeResult = refreshedResults.firstOrNull { it.server.id == curActive?.id }
                if (activeResult != null && activeResult.sessions.isNotEmpty()) {
                    val currentPinned = curActive?.lastPinnedSession
                    if (currentPinned != null) {
                        val refreshed = activeResult.sessions.firstOrNull { it.id == currentPinned.sessionId }
                        if (refreshed != null) {
                            val updated = PinnedSession(
                                sessionId = refreshed.id,
                                directory = refreshed.directory,
                                title = refreshed.title,
                            )
                            pinnedSession = updated
                            tokenStore.updatePinnedSession(activeResult.server.id, updated)
                        }
                    } else if (activeResult.sessions.size == 1) {
                        val sole = activeResult.sessions.first()
                        val autoPinned = PinnedSession(
                            sessionId = sole.id,
                            directory = sole.directory,
                            title = sole.title,
                        )
                        pinnedSession = autoPinned
                        tokenStore.updatePinnedSession(activeResult.server.id, autoPinned)
                    }
                } else if (!isConnected) {
                    authError = "Session expired — please sign in again"
                }
            } catch (t: Throwable) {
                sessionsError = t.message ?: "Failed to load sessions"
            } finally {
                isLoadingSessions = false
            }
        }
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
                syncServerStateFromStore()
                if (prevActiveId != profile.id) {
                    voices = emptyList()
                    boundService?.onActiveSessionOrServerChanged()
                }
                refreshSessions()
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
        syncServerStateFromStore()
        if (!isConnected) {
            serverGroups = emptyList()
        } else {
            refreshSessions()
        }
        authError = null
        sessionsError = null
    }

    fun launchBubble() {
        permissionSnapshot = readPermissionSnapshot(context)
        when (permissionSnapshot.bubbleAvailability) {
            BubbleAvailability.AVAILABLE -> {
                val ok = BubbleHelper.showOrUpdateBubble(
                    context = context,
                    sessionTitle = pinnedSession?.title ?: "OpenChamber Voice",
                    statusSummary = statusText,
                    userPrompt = lastTranscript,
                    agentReply = lastReply,
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
                val missing = permissionSnapshot.missingPermissions
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                } else {
                    BubbleHelper.openBubbleSettings(context)
                }
            }
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            refreshSessions()
        }
    }

    LaunchedEffect(boundService, isConnected, pinnedSession?.sessionId) {
        if (boundService != null && isConnected && pinnedSession != null) {
            boundService.refreshLatestTurn()
        }
    }

    val isOperateReady = permissionSnapshot.hasRecordAudio && isConnected

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
                                contentDescription = "Settings",
                            )
                            if (permissionSnapshot.needsAttention) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isOperateReady) {
                SetupFlowContent(
                    snapshot = permissionSnapshot,
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it },
                    password = password,
                    onPasswordChange = { password = it },
                    showPassword = showPassword,
                    onToggleShowPassword = { showPassword = !showPassword },
                    isConnected = isConnected,
                    isAuthenticating = isAuthenticating,
                    authError = authError,
                    onRequestPermissions = {
                        val missing = permissionSnapshot.missingPermissions
                        if (missing.isNotEmpty()) {
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    },
                    onRequestBatteryExemption = {
                        launchBatteryOptimizationSettings(context)
                    },
                    onConnect = { performConnect() },
                )
            } else {
                SessionPill(
                    pinnedSession = pinnedSession,
                    isConnected = isConnected,
                    isLoading = isLoadingSessions,
                    serverLabel = activeServer?.displayLabel,
                    showServerLabel = servers.size > 1,
                    onClick = {
                        if (serverGroups.isEmpty()) refreshSessions()
                        showSessionPicker = true
                    },
                )

                if (!sessionsError.isNullOrBlank()) {
                    Text(
                        text = sessionsError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (pttState == PttState.PERMISSION_BLOCKED) {
                    PermissionWarningBanner(
                        toolName = permissionPrompt ?: "tool",
                        canReArm = pinnedSession != null,
                        onOpenOpenChamber = {
                            OpenChamberLauncher.openInOfficialApp(
                                context = context,
                                sessionId = pinnedSession?.sessionId,
                                directory = pinnedSession?.directory,
                                fallbackServerUrl = serverUrl,
                            )
                        },
                        onReArm = {
                            val intent = PttForegroundService.startIntent(
                                context = context,
                                action = PttForegroundService.ACTION_ARM,
                                pinSco = pinSco,
                            )
                            ContextCompat.startForegroundService(context, intent)
                        },
                    )
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusPill(pttState = pttState)
                }

                HeroPttControl(
                    pttState = pttState,
                    statusText = statusText,
                    recordingStartedAtElapsedMs = recordingStartedAt,
                    canArm = pinnedSession != null && activeServer?.isSignedIn == true,
                    compact = false,
                    onArm = {
                        val intent = PttForegroundService.startIntent(
                            context = context,
                            action = PttForegroundService.ACTION_ARM,
                            pinSco = pinSco,
                        )
                        ContextCompat.startForegroundService(context, intent)
                    },
                    onTapPtt = {
                        if (boundService != null) {
                            boundService.onMediaButtonTap()
                        } else {
                            val intent = PttForegroundService.startIntent(
                                context = context,
                                action = PttForegroundService.ACTION_TOGGLE_PTT,
                            )
                            ContextCompat.startForegroundService(context, intent)
                        }
                    },
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = pinSco,
                        onClick = {
                            val next = !pinSco
                            pinSco = next
                            boundService?.pinBluetoothSco = next
                        },
                        label = { Text("Headset mic") },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_bluetooth_audio),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )

                    val activeVoiceChipLabel = remember(
                        ttsPipeline,
                        selectedVoice,
                        selectedMlKitTtsVoice,
                        selectedOpenAiVoice,
                        selectedSystemVoice,
                        systemVoices,
                    ) {
                        val systemLabel = selectedSystemVoice?.let { id ->
                            systemVoices.firstOrNull { it.name == id }?.label
                                ?: id.substringAfterLast('-').ifBlank { id }
                        }
                        VoicePipelineDefaults.resolveActiveVoiceChipLabel(
                            pipeline = ttsPipeline,
                            geminiVoice = selectedVoice,
                            mlKitVoice = selectedMlKitTtsVoice,
                            openAiVoice = selectedOpenAiVoice,
                            systemVoiceLabel = systemLabel,
                        )
                    }

                    AssistChip(
                        onClick = {
                            if (voices.isEmpty()) refreshVoices()
                            voicePickerInitialEngine = VoicePipelineDefaults.primaryEnabledEngine(ttsPipeline)
                                ?: TtsEngine.GEMINI_BRIDGE
                            showVoicePicker = true
                        },
                        label = {
                            Text(
                                text = activeVoiceChipLabel,
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
                        modifier = Modifier.heightIn(min = 48.dp),
                    )

                    if (pttState != PttState.DISARMED) {
                        AssistChip(
                            onClick = {
                                if (boundService != null) {
                                    boundService.disarm()
                                } else {
                                    val intent = PttForegroundService.startIntent(
                                        context = context,
                                        action = PttForegroundService.ACTION_DISARM,
                                    )
                                    context.startService(intent)
                                }
                            },
                            label = { Text("Disarm") },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_power),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }

                val canReadAloud = pinnedSession != null &&
                    (pttState == PttState.DISARMED || pttState == PttState.ARMED_IDLE || pttState == PttState.SPEAKING_TTS)
                TurnTranscriptPanel(
                    lastTranscript = lastTranscript,
                    lastReply = lastReply,
                    lastTtsInfo = lastTtsInfo,
                    serviceError = serviceError,
                    isSpeaking = pttState == PttState.SPEAKING_TTS,
                    canReadAloud = canReadAloud,
                    onReadAloudClick = { boundService?.readPreviousMessage() },
                    onOpenInOpenChamber = if (pinnedSession != null || serverUrl.isNotBlank()) {
                        {
                            OpenChamberLauncher.openInOfficialApp(
                                context = context,
                                sessionId = pinnedSession?.sessionId,
                                directory = pinnedSession?.directory,
                                fallbackServerUrl = serverUrl,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    if (showSessionPicker) {
        SessionPickerSheet(
            serverGroups = serverGroups,
            activeServerId = activeServer?.id,
            pinnedSession = pinnedSession,
            isLoading = isLoadingSessions,
            error = sessionsError,
            onRefresh = { refreshSessions() },
            onSelect = { targetServer: ServerProfile, summary: SessionSummary ->
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
            },
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
            onDismiss = { showSessionPicker = false },
        )
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
                            refreshMlKitStatuses()
                        }
                        is MlKitDownloadEvent.Failed -> {
                            mlKitTtsDownloadStatus = "Download failed: ${ev.message}"
                        }
                    }
                }
            } catch (t: Throwable) {
                mlKitTtsDownloadStatus = "Download failed: ${t.message}"
            } finally {
                isDownloadingMlKitTts = false
            }
        }
    }

    val customServerId = activeServer?.takeIf { it.voiceOverride != null }?.id

    if (showVoicePicker) {
        VoicePickerSheet(
            ttsPipeline = ttsPipeline,
            initialEngine = voicePickerInitialEngine,
            geminiVoices = voices,
            selectedGeminiVoiceId = selectedVoice,
            isLoadingGemini = isLoadingVoices,
            geminiError = voicesError,
            onRefreshGemini = { refreshVoices() },
            onSelectGeminiVoice = { voice ->
                selectedVoice = voice.id
                tokenStore.saveTtsVoice(voice.id, customServerId)
                syncServerStateFromStore()
                showVoicePicker = false
            },
            mlKitStatus = mlKitTtsStatus,
            mlKitVoices = mlKitTtsVoices,
            selectedMlKitVoice = selectedMlKitTtsVoice,
            mlKitDownloadStatus = mlKitTtsDownloadStatus,
            isDownloadingMlKit = isDownloadingMlKitTts,
            onRefreshMlKit = { refreshMlKitStatuses() },
            onSelectMlKitVoice = { voiceName ->
                selectedMlKitTtsVoice = voiceName
                tokenStore.setMlKitTtsVoice(voiceName)
                val pickedAvail = mlKitTtsVoices
                    .firstOrNull { it.name.equals(voiceName, ignoreCase = true) }
                    ?.availability
                if (pickedAvail == MlKitAvailability.AVAILABLE) {
                    showVoicePicker = false
                }
            },
            onDownloadMlKitVoice = { voiceName ->
                downloadMlKitTtsVoice(voiceName ?: selectedMlKitTtsVoice)
            },
            selectedOpenAiVoice = selectedOpenAiVoice,
            onSelectOpenAiVoice = { voiceId ->
                selectedOpenAiVoice = voiceId
                tokenStore.saveOpenAiTtsVoice(voiceId, customServerId)
                syncServerStateFromStore()
                showVoicePicker = false
            },
            systemVoices = systemVoices,
            selectedSystemVoice = selectedSystemVoice,
            isLoadingSystemVoices = isLoadingSystemVoices,
            onRefreshSystemVoices = { refreshSystemVoices() },
            onSelectSystemVoice = { voiceName ->
                selectedSystemVoice = voiceName
                tokenStore.setSystemTtsVoice(voiceName)
                showVoicePicker = false
            },
            onOpenSettings = { showSettingsSheet = true },
            onDismiss = { showVoicePicker = false },
        )
    }

    if (showSettingsSheet) {
        SettingsBottomSheet(
            snapshot = permissionSnapshot,
            servers = servers,
            activeServer = activeServer,
            serverUrl = serverUrl,
            onServerUrlChange = { serverUrl = it },
            serverLabel = serverLabelInput,
            onServerLabelChange = { serverLabelInput = it },
            password = password,
            onPasswordChange = { password = it },
            showPassword = showPassword,
            onToggleShowPassword = { showPassword = !showPassword },
            isConnected = isConnected,
            isAuthenticating = isAuthenticating,
            authError = authError,
            startWithAddServerOpen = openAddServerInSettings,
            onSelectServer = { targetServer ->
                val changed = targetServer.id != activeServer?.id
                if (targetServer.isSignedIn) {
                    tokenStore.setActiveServer(targetServer.id)
                    syncServerStateFromStore()
                    if (changed) {
                        voices = emptyList()
                        refreshVoices()
                        boundService?.onActiveSessionOrServerChanged()
                    }
                } else {
                    serverUrl = targetServer.serverUrl
                    serverLabelInput = targetServer.label
                    password = ""
                    authError = null
                }
            },
            onRemoveServer = { targetServer ->
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
                    serverGroups = emptyList()
                }
            },
            isUsingCustomServerVoice = customServerId != null,
            onToggleCustomServerVoice = { useCustom ->
                val curActive = activeServer ?: return@SettingsBottomSheet
                if (useCustom) {
                    tokenStore.setServerVoiceOverride(
                        curActive.id,
                        ServerVoiceOverride(
                            ttsPipeline = ttsPipeline,
                            geminiVoice = selectedVoice,
                            openAiVoice = selectedOpenAiVoice,
                            sttEngine = sttEngine,
                            sttFallbackToParakeet = sttFallbackToParakeet,
                        ),
                    )
                } else {
                    tokenStore.setServerVoiceOverride(curActive.id, null)
                }
                syncServerStateFromStore()
            },
            ttsPipeline = ttsPipeline,
            onUpdateTtsPipeline = { updated ->
                ttsPipeline = updated
                tokenStore.saveTtsPipeline(updated, customServerId)
                syncServerStateFromStore()
            },
            selectedGeminiVoice = selectedVoice,
            selectedOpenAiVoice = selectedOpenAiVoice,
            selectedSystemVoiceLabel = selectedSystemVoice?.let { id ->
                systemVoices.firstOrNull { it.name == id }?.label
                    ?: id.substringAfterLast('-').ifBlank { id }
            },
            onOpenVoicePickerFor = { targetEngine ->
                if (targetEngine == TtsEngine.GEMINI_BRIDGE && voices.isEmpty()) {
                    refreshVoices()
                }
                voicePickerInitialEngine = targetEngine
                showSettingsSheet = false
                showVoicePicker = true
            },
            sttEngine = sttEngine,
            onSelectSttEngine = { engine ->
                sttEngine = engine
                tokenStore.saveSttEngine(engine, customServerId)
                syncServerStateFromStore()
            },
            sttFallbackToParakeet = sttFallbackToParakeet,
            onToggleSttFallback = { enabled ->
                sttFallbackToParakeet = enabled
                tokenStore.saveSttFallbackToParakeet(enabled, customServerId)
                syncServerStateFromStore()
            },
            mlKitAdvancedStatus = mlKitAdvancedStatus,
            mlKitBasicStatus = mlKitBasicStatus,
            mlKitDownloadStatus = mlKitDownloadStatus,
            isDownloadingMlKit = isDownloadingMlKit,
            mlKitTtsStatus = mlKitTtsStatus,
            mlKitTtsVoicesCount = mlKitTtsVoices.size,
            selectedMlKitTtsVoice = selectedMlKitTtsVoice,
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
                                    refreshMlKitStatuses()
                                }
                                is MlKitDownloadEvent.Failed -> {
                                    mlKitDownloadStatus = "Download failed: ${ev.message}"
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        mlKitDownloadStatus = "Download failed: ${t.message}"
                    } finally {
                        isDownloadingMlKit = false
                    }
                }
            },
            onRequestPermissions = {
                val missing = permissionSnapshot.missingPermissions
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
            onConnect = { performConnect() },
            onDisconnect = {
                performDisconnectActiveServer()
                if (!isConnected) {
                    showSettingsSheet = false
                }
            },
            onDismiss = {
                openAddServerInSettings = false
                showSettingsSheet = false
            },
        )
    }
}

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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!snapshot.allRuntimeGranted) {
                                Button(
                                    onClick = onRequestPermissions,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                ) {
                                    Text("Grant Permissions")
                                }
                            }
                            if (!snapshot.isIgnoringBatteryOptimizations) {
                                OutlinedButton(
                                    onClick = onRequestBatteryExemption,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                ) {
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

@Composable
private fun PermissionCheckRow(
    label: String,
    granted: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(if (granted) R.drawable.ic_check_circle else R.drawable.ic_radio_unchecked),
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerConnectionFields(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    isConnected: Boolean,
    isAuthenticating: Boolean,
    authError: String?,
    onConnect: () -> Unit,
) {
    val autofillManager = LocalAutofillManager.current

    OutlinedTextField(
        value = serverUrl,
        onValueChange = onServerUrlChange,
        label = { Text("Server URL") },
        placeholder = { Text("https://openchamber.example.ts.net:14096") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(if (isConnected) "Password (re-auth)" else "Password") },
        singleLine = true,
        visualTransformation = if (showPassword) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                autofillManager?.commit()
                onConnect()
            },
        ),
        trailingIcon = {
            IconButton(onClick = onToggleShowPassword) {
                Icon(
                    painter = painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = if (showPassword) "Hide password" else "Show password",
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentType = ContentType.Password
            },
    )

    if (!authError.isNullOrBlank()) {
        Text(
            text = authError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Button(
        onClick = {
            autofillManager?.commit()
            onConnect()
        },
        enabled = !isAuthenticating,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        if (isAuthenticating) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(if (isConnected) "Reconnect" else "Connect")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsBottomSheet(
    snapshot: PermissionStatusSnapshot,
    servers: List<ServerProfile>,
    activeServer: ServerProfile?,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    serverLabel: String,
    onServerLabelChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    isConnected: Boolean,
    isAuthenticating: Boolean,
    authError: String?,
    startWithAddServerOpen: Boolean,
    onSelectServer: (ServerProfile) -> Unit,
    onRemoveServer: (ServerProfile) -> Unit,
    isUsingCustomServerVoice: Boolean,
    onToggleCustomServerVoice: (Boolean) -> Unit,
    ttsPipeline: List<TtsEngineOption>,
    onUpdateTtsPipeline: (List<TtsEngineOption>) -> Unit,
    selectedGeminiVoice: String,
    selectedOpenAiVoice: String,
    selectedSystemVoiceLabel: String?,
    onOpenVoicePickerFor: (TtsEngine) -> Unit,
    sttEngine: SttEngine,
    onSelectSttEngine: (SttEngine) -> Unit,
    sttFallbackToParakeet: Boolean,
    onToggleSttFallback: (Boolean) -> Unit,
    mlKitAdvancedStatus: MlKitStatusReport?,
    mlKitBasicStatus: MlKitStatusReport?,
    mlKitDownloadStatus: String?,
    isDownloadingMlKit: Boolean,
    mlKitTtsStatus: MlKitStatusReport?,
    mlKitTtsVoicesCount: Int,
    selectedMlKitTtsVoice: String?,
    onDownloadMlKitModel: (SttEngine) -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenBubbleSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Device & Multitasking",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PermissionCheckRow(label = "Microphone", granted = snapshot.hasRecordAudio)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PermissionCheckRow(label = "Bluetooth Connect", granted = snapshot.hasBluetoothConnect)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionCheckRow(label = "Notifications", granted = snapshot.hasPostNotifications)
                }
                PermissionCheckRow(
                    label = "Floating Bubbles (${if (snapshot.bubbleAvailability == BubbleAvailability.AVAILABLE) "Enabled" else "Off"})",
                    granted = snapshot.bubbleAvailability == BubbleAvailability.AVAILABLE,
                )
                PermissionCheckRow(
                    label = "Unrestricted battery",
                    granted = snapshot.isIgnoringBatteryOptimizations,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!snapshot.allRuntimeGranted) {
                        FilledTonalButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Grant")
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenBubbleSettings,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Bubble Settings")
                    }
                    if (!snapshot.isIgnoringBatteryOptimizations) {
                        OutlinedButton(
                            onClick = onRequestBatteryExemption,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Battery")
                        }
                    }
                }
            }

            HorizontalDivider()

            if (servers.size > 1 && activeServer != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Voice & Speech Scope",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !isUsingCustomServerVoice,
                            onClick = { onToggleCustomServerVoice(false) },
                            label = { Text("All servers") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        FilterChip(
                            selected = isUsingCustomServerVoice,
                            onClick = { onToggleCustomServerVoice(true) },
                            label = {
                                Text(
                                    text = "${activeServer.displayLabel} only",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    Text(
                        text = if (isUsingCustomServerVoice) {
                            "STT engine, TTS order, and Gemini/OpenAI voices are saved just for ${activeServer.displayLabel}."
                        } else {
                            "Using shared global STT/TTS settings. Switch to \"${activeServer.displayLabel} only\" if this server needs its own setup."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Speech-to-Text (STT)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                SttEngine.entries.forEach { engine ->
                    val report = when (engine) {
                        SttEngine.OPENCHAMBER_PARAKEET -> null
                        SttEngine.MLKIT_GENAI_ADVANCED -> mlKitAdvancedStatus
                        SttEngine.MLKIT_GENAI_BASIC -> mlKitBasicStatus
                    }
                    val statusSuffix = when (engine) {
                        SttEngine.OPENCHAMBER_PARAKEET -> "Host"
                        else -> formatMlKitBadge(report?.availability)
                    }
                    Surface(
                        color = if (sttEngine == engine) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSttEngine(engine) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sttEngine == engine,
                                onClick = { onSelectSttEngine(engine) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${engine.title} ($statusSuffix)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = report?.detail ?: engine.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                val selectedMlKitStatus = when (sttEngine) {
                    SttEngine.MLKIT_GENAI_ADVANCED -> mlKitAdvancedStatus?.availability
                    SttEngine.MLKIT_GENAI_BASIC -> mlKitBasicStatus?.availability
                    SttEngine.OPENCHAMBER_PARAKEET -> null
                }
                if (selectedMlKitStatus == MlKitAvailability.DOWNLOADABLE) {
                    FilledTonalButton(
                        onClick = { onDownloadMlKitModel(sttEngine) },
                        enabled = !isDownloadingMlKit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(if (isDownloadingMlKit) "Downloading Model…" else "Download ML Kit Model")
                    }
                }
                if (!mlKitDownloadStatus.isNullOrBlank()) {
                    Text(
                        text = mlKitDownloadStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (sttEngine != SttEngine.OPENCHAMBER_PARAKEET) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Fallback to Parakeet",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Replay audio to host Parakeet if ML Kit fails",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = sttFallbackToParakeet,
                            onCheckedChange = onToggleSttFallback,
                        )
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var isReorderingTts by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Text-to-Speech Order",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (isReorderingTts) {
                                "Move fallback engines up or down."
                            } else {
                                "Tried top-to-bottom. Tap a row to pick its voice."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { isReorderingTts = !isReorderingTts },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (isReorderingTts) "Done" else "Reorder")
                    }
                }

                ttsPipeline.forEachIndexed { index, option ->
                    val engineSubtitle = when (option.engine) {
                        TtsEngine.GEMINI_BRIDGE -> "Voice: $selectedGeminiVoice · Cloud via :4005"
                        TtsEngine.LOCAL_KOKORO -> "Host default speaker · /api/dictation/tts/speak"
                        TtsEngine.MLKIT_GENAI -> {
                            val badge = formatMlKitBadge(mlKitTtsStatus?.availability)
                            val voicePart = selectedMlKitTtsVoice ?: "Auto"
                            val diag = mlKitTtsStatus?.detail?.takeIf {
                                mlKitTtsStatus.availability == MlKitAvailability.UNAVAILABLE
                            }?.let { "\n$it" }.orEmpty()
                            "Voice: $voicePart · $badge$diag"
                        }
                        TtsEngine.ANDROID_SYSTEM -> "Voice: ${selectedSystemVoiceLabel ?: "System default"}"
                        TtsEngine.OPENAI_CLOUD -> "Voice: $selectedOpenAiVoice · Direct /api/tts/speak"
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isReorderingTts) {
                                    Modifier.clickable { onOpenVoicePickerFor(option.engine) }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${index + 1}. ${option.engine.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = engineSubtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isReorderingTts) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val mutable = ttsPipeline.toMutableList()
                                            val item = mutable.removeAt(index)
                                            mutable.add(index - 1, item)
                                            onUpdateTtsPipeline(mutable)
                                        }
                                    },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_up),
                                        contentDescription = "Move up",
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < ttsPipeline.lastIndex) {
                                            val mutable = ttsPipeline.toMutableList()
                                            val item = mutable.removeAt(index)
                                            mutable.add(index + 1, item)
                                            onUpdateTtsPipeline(mutable)
                                        }
                                    },
                                    enabled = index < ttsPipeline.lastIndex,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_down),
                                        contentDescription = "Move down",
                                    )
                                }
                            } else {
                                Switch(
                                    checked = option.enabled,
                                    onCheckedChange = { checked ->
                                        val updated = ttsPipeline.mapIndexed { idx, cur ->
                                            if (idx == index) cur.copy(enabled = checked) else cur
                                        }
                                        onUpdateTtsPipeline(updated)
                                    },
                                )
                            }
                        }
                    }
                }

                val hasUnavailableMlKit = listOf(
                    mlKitAdvancedStatus?.availability,
                    mlKitBasicStatus?.availability,
                    mlKitTtsStatus?.availability,
                ).any { it == MlKitAvailability.UNAVAILABLE }
                if (hasUnavailableMlKit) {
                    val sheetContext = LocalContext.current
                    @Suppress("DEPRECATION")
                    val clipboardManager = LocalClipboardManager.current
                    OutlinedButton(
                        onClick = {
                            val report = buildString {
                                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, ${Build.TAGS})")
                                appendLine("Packages: ${MlKitSttClient.buildPackageDiagnostic(sheetContext)}")
                                appendLine("STT Adv: ${mlKitAdvancedStatus?.availability} | ${mlKitAdvancedStatus?.detail ?: "ok"}")
                                appendLine("STT Basic: ${mlKitBasicStatus?.availability} | ${mlKitBasicStatus?.detail ?: "ok"}")
                                append("TTS GenAI: ${mlKitTtsStatus?.availability} | ${mlKitTtsStatus?.detail ?: "ok"} (voices=$mlKitTtsVoicesCount)")
                            }
                            clipboardManager.setText(AnnotatedString(report))
                            Toast.makeText(sheetContext, "Copied ML Kit diagnostics", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Copy ML Kit diagnostics")
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var showServerForm by remember(startWithAddServerOpen, servers.size) {
                    mutableStateOf(startWithAddServerOpen || servers.isEmpty() || activeServer?.isSignedIn != true)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Servers",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = {
                            if (!showServerForm) {
                                onServerUrlChange("")
                                onServerLabelChange("")
                                onPasswordChange("")
                            }
                            showServerForm = !showServerForm
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (showServerForm) "Hide form" else "+ Add server")
                    }
                }

                servers.forEach { profile ->
                    val isActive = profile.id == activeServer?.id
                    val statusLabel = buildString {
                        if (isActive) append("Active · ")
                        append(if (profile.isSignedIn) "Signed in" else "Sign in needed")
                        if (profile.voiceOverride != null) append(" · Custom voice")
                    }
                    Surface(
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectServer(profile)
                                if (!profile.isSignedIn) {
                                    showServerForm = true
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = profile.displayLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = profile.serverUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (profile.isSignedIn) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            if (!profile.isSignedIn) {
                                TextButton(
                                    onClick = {
                                        onSelectServer(profile)
                                        showServerForm = true
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Sign in")
                                }
                            } else if (servers.size > 1) {
                                TextButton(
                                    onClick = { onRemoveServer(profile) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }

                if (showServerForm) {
                    OutlinedTextField(
                        value = serverLabel,
                        onValueChange = onServerLabelChange,
                        label = { Text("Label (optional, e.g. Workstation)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ServerConnectionFields(
                        serverUrl = serverUrl,
                        onServerUrlChange = onServerUrlChange,
                        password = password,
                        onPasswordChange = onPasswordChange,
                        showPassword = showPassword,
                        onToggleShowPassword = onToggleShowPassword,
                        isConnected = activeServer?.serverUrl.equals(
                            OpenChamberHttp.normalizeBaseUrl(serverUrl),
                            ignoreCase = true,
                        ) && activeServer?.isSignedIn == true,
                        isAuthenticating = isAuthenticating,
                        authError = authError,
                        onConnect = onConnect,
                    )
                }

                if (isConnected && activeServer?.isSignedIn == true) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !isAuthenticating,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Sign out of ${activeServer.displayLabel}")
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

private fun formatMlKitBadge(status: MlKitAvailability?): String = when (status) {
    MlKitAvailability.AVAILABLE -> "Ready"
    MlKitAvailability.DOWNLOADABLE -> "Downloadable"
    MlKitAvailability.DOWNLOADING -> "Downloading"
    MlKitAvailability.UNAVAILABLE -> "Unavailable"
    null -> "Checking…"
}
