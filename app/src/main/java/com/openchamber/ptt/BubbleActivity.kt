package com.openchamber.ptt

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.network.OpenChamberLauncher
import com.openchamber.ptt.service.PttForegroundService
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.session.ServerSessionsResult
import com.openchamber.ptt.session.SessionRepository
import com.openchamber.ptt.session.SessionSummary
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.ui.HeroPttControl
import com.openchamber.ptt.ui.PermissionWarningBanner
import com.openchamber.ptt.ui.SessionPickerSheet
import com.openchamber.ptt.ui.SessionPill
import com.openchamber.ptt.ui.StatusPill
import com.openchamber.ptt.ui.TurnTranscriptPanel
import com.openchamber.ptt.ui.theme.OpenChamberPttTheme
import kotlinx.coroutines.launch

class BubbleActivity : ComponentActivity() {
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
        val sessionRepository = SessionRepository(httpClient)

        setContent {
            OpenChamberPttTheme {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    BubbleCockpitScreen(
                        tokenStore = tokenStore,
                        sessionRepository = sessionRepository,
                        boundService = boundService,
                        onOpenFullApp = {
                            val launchIntent = Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(launchIntent)
                        },
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
}

@Composable
private fun BubbleCockpitScreen(
    tokenStore: TokenStore,
    sessionRepository: SessionRepository,
    boundService: PttForegroundService?,
    onOpenFullApp: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(tokenStore.getServers()) }
    var activeServer by remember { mutableStateOf(tokenStore.getActiveServer()) }
    val serverUrl = activeServer?.serverUrl.orEmpty()
    val hasActiveToken = !activeServer?.token.isNullOrBlank()
    val isConnected = servers.any { it.isSignedIn }
    val hasMic = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val isReadyForVoice = hasMic && isConnected

    var pinnedSession by remember { mutableStateOf(tokenStore.getPinnedSession()) }
    var serverGroups by remember { mutableStateOf<List<ServerSessionsResult>>(emptyList()) }
    var isLoadingSessions by remember { mutableStateOf(false) }
    var sessionsError by remember { mutableStateOf<String?>(null) }
    var showSessionPicker by remember { mutableStateOf(false) }

    var pinSco by remember { mutableStateOf(boundService?.pinBluetoothSco ?: false) }

    val pttState = boundService?.state?.collectAsState()?.value ?: PttState.DISARMED
    val statusText = boundService?.statusText?.collectAsState()?.value ?: "Connecting…"
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

    fun syncServerStateFromStore() {
        val list = tokenStore.getServers()
        val active = tokenStore.getActiveServer()
        servers = list
        activeServer = active
        pinnedSession = active?.lastPinnedSession
    }

    DisposableEffect(lifecycleOwner, boundService) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
        servers = currentServers
        activeServer = tokenStore.getActiveServer()
        if (currentServers.none { it.isSignedIn }) return
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
                syncServerStateFromStore()
                serverGroups = results.map { res ->
                    val freshServer = updatedServers.firstOrNull { it.id == res.server.id } ?: res.server
                    res.copy(server = freshServer)
                }
            } catch (t: Throwable) {
                sessionsError = t.message ?: "Failed to load sessions"
            } finally {
                isLoadingSessions = false
            }
        }
    }

    LaunchedEffect(isReadyForVoice) {
        if (isReadyForVoice) {
            refreshSessions()
        }
    }

    LaunchedEffect(boundService, isReadyForVoice, pinnedSession?.sessionId) {
        if (boundService != null && isReadyForVoice && pinnedSession != null) {
            boundService.refreshLatestTurn()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(pttState = pttState)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pttState != PttState.DISARMED) {
                    TextButton(
                        onClick = { boundService?.disarm() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_power),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disarm")
                    }
                }
                IconButton(onClick = onOpenFullApp) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = "Open full app",
                    )
                }
            }
        }

        if (!isReadyForVoice) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Finish setup in OpenChamber PTT",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Grant microphone access and connect to your OpenChamber server first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenFullApp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Setup")
                    }
                }
            }
        } else {
            SessionPill(
                pinnedSession = pinnedSession,
                isConnected = true,
                isLoading = isLoadingSessions,
                serverLabel = activeServer?.displayLabel,
                showServerLabel = servers.size > 1,
                onClick = {
                    if (serverGroups.isEmpty()) refreshSessions()
                    showSessionPicker = true
                },
            )

            if (pttState == PttState.PERMISSION_BLOCKED) {
                PermissionWarningBanner(
                    toolName = permissionPrompt ?: "tool",
                    canReArm = pinnedSession != null && hasActiveToken,
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

            HeroPttControl(
                pttState = pttState,
                statusText = statusText,
                recordingStartedAtElapsedMs = recordingStartedAt,
                canArm = pinnedSession != null && hasActiveToken,
                compact = true,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
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
            }

            val canReadAloud = pinnedSession != null &&
                hasActiveToken &&
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
                tokenStore.setActiveServer(targetServer.id)
                tokenStore.updatePinnedSession(targetServer.id, updated)
                servers = tokenStore.getServers()
                activeServer = tokenStore.getActiveServer()
                pinnedSession = updated
                boundService?.onActiveSessionOrServerChanged()
                showSessionPicker = false
            },
            onDismiss = { showSessionPicker = false },
        )
    }
}
