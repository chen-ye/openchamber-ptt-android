package com.openchamber.ptt

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.service.PttForegroundService
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.session.SessionRepository
import com.openchamber.ptt.storage.TokenStore
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.ui.HeroPttControl
import com.openchamber.ptt.ui.PermissionWarningBanner
import com.openchamber.ptt.ui.SessionPill
import com.openchamber.ptt.ui.SpokenDetailSlider
import com.openchamber.ptt.ui.StagedDraftCard
import com.openchamber.ptt.ui.StatusPill
import com.openchamber.ptt.ui.TurnTranscriptPanel
import com.openchamber.ptt.ui.VoiceCockpitSheets
import com.openchamber.ptt.ui.rememberVoiceCockpitState
import com.openchamber.ptt.ui.theme.OpenChamberPttTheme

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
        val ttsClient = TtsClient(httpClient)

        setContent {
            OpenChamberPttTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    BubbleCockpitScreen(
                        tokenStore = tokenStore,
                        sessionRepository = sessionRepository,
                        ttsClient = ttsClient,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleCockpitScreen(
    tokenStore: TokenStore,
    sessionRepository: SessionRepository,
    ttsClient: TtsClient,
    boundService: PttForegroundService?,
    onOpenFullApp: () -> Unit,
) {
    val cockpit = rememberVoiceCockpitState(
        tokenStore = tokenStore,
        sessionRepository = sessionRepository,
        ttsClient = ttsClient,
        boundService = boundService,
        defaultStatusText = "Connecting…",
        requireMicForAutoLoad = true,
    )
    val isReadyForVoice = cockpit.isOperateReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusPill(pttState = cockpit.pttState)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cockpit.pttState != PttState.DISARMED) {
                    TextButton(
                        onClick = { cockpit.disarmPtt() },
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
                pinnedSession = cockpit.pinnedSession,
                isConnected = true,
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
                    onOpenOpenChamber = { cockpit.openInOfficialApp() },
                    onReArm = { cockpit.armPtt() },
                )
            }

            HeroPttControl(
                pttState = cockpit.pttState,
                statusText = cockpit.statusText,
                recordingStartedAtElapsedMs = cockpit.recordingStartedAt,
                canArm = cockpit.canArm,
                compact = true,
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
            }

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
                showQueueStrip = false,
                isSpeaking = cockpit.pttState == PttState.SPEAKING_TTS,
                canReadAloud = cockpit.canReadAloud,
                onReadAloudClick = { cockpit.readPreviousMessage() },
                onStepResponse = { delta -> cockpit.stepResponse(delta) },
                onSelectResponse = { messageId -> cockpit.selectResponse(messageId) },
                onMarkAllRead = { cockpit.markAllResponsesRead() },
                onDismissDirectAnswer = { cockpit.dismissDirectAnswer() },
                onOpenInOpenChamber = if (cockpit.pinnedSession != null || cockpit.activeServerUrl.isNotBlank()) {
                    { cockpit.openInOfficialApp() }
                } else {
                    null
                },
            )
        }
    }

    VoiceCockpitSheets(
        state = cockpit,
        onSignInServer = { onOpenFullApp() },
        onAddServer = onOpenFullApp,
        onOpenSettings = onOpenFullApp,
    )
}
