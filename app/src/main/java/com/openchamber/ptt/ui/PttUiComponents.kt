package com.openchamber.ptt.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.R
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.session.ServerSessionsResult
import com.openchamber.ptt.session.SessionSummary
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.ServerProfile
import com.openchamber.ptt.tts.MlKitTtsVoiceSummary
import com.openchamber.ptt.tts.SystemTtsVoiceSummary
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsVoice
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient
import kotlinx.coroutines.delay

internal data class PttStateVisuals(
    val label: String,
    val containerColor: Color,
    val contentColor: Color,
    val dotColor: Color,
)

@Composable
internal fun rememberStateVisuals(pttState: PttState): PttStateVisuals {
    val scheme = MaterialTheme.colorScheme
    return when (pttState) {
        PttState.DISARMED -> PttStateVisuals(
            label = "Disarmed",
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurfaceVariant,
            dotColor = scheme.outline,
        )
        PttState.ARMED_IDLE -> PttStateVisuals(
            label = "Armed",
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            dotColor = scheme.primary,
        )
        PttState.RECORDING -> PttStateVisuals(
            label = "Listening",
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
            dotColor = scheme.error,
        )
        PttState.TRANSCRIBING -> PttStateVisuals(
            label = "Transcribing",
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            dotColor = scheme.secondary,
        )
        PttState.WAITING_AGENT -> PttStateVisuals(
            label = "Thinking",
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            dotColor = scheme.secondary,
        )
        PttState.SPEAKING_TTS -> PttStateVisuals(
            label = "Speaking",
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
            dotColor = scheme.tertiary,
        )
        PttState.PERMISSION_BLOCKED -> PttStateVisuals(
            label = "Approval needed",
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
            dotColor = scheme.error,
        )
        PttState.ERROR -> PttStateVisuals(
            label = "Error",
            containerColor = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
            dotColor = scheme.error,
        )
    }
}

internal fun shouldAnimateMotion(context: Context): Boolean {
    return runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }.getOrDefault(true)
}

@Composable
internal fun StatusPill(
    pttState: PttState,
    modifier: Modifier = Modifier,
) {
    val visuals = rememberStateVisuals(pttState)
    Surface(
        color = visuals.containerColor,
        contentColor = visuals.contentColor,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(visuals.dotColor, CircleShape),
            )
            Text(
                text = visuals.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SessionPill(
    pinnedSession: PinnedSession?,
    isConnected: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    serverLabel: String? = null,
    showServerLabel: Boolean = false,
) {
    val title = when {
        !isConnected -> "Connect to OpenChamber"
        pinnedSession != null -> pinnedSession.title.ifBlank { "Untitled session" }
        else -> "Choose a session"
    }
    val sessionSub = when {
        !isConnected -> "Open settings to sign in"
        pinnedSession != null && pinnedSession.directory.isNotBlank() ->
            pinnedSession.directory.trimEnd('/').substringAfterLast('/').ifBlank { pinnedSession.directory }
        pinnedSession != null -> pinnedSession.sessionId
        else -> "Tap to select an OpenCode session"
    }
    val subtitle = if (isConnected && showServerLabel && !serverLabel.isNullOrBlank()) {
        "$serverLabel · $sessionSub"
    } else {
        sessionSub
    }

    Surface(
        onClick = onClick,
        enabled = isConnected,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_swap_horiz),
                    contentDescription = "Switch session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun HeroPttControl(
    pttState: PttState,
    statusText: String,
    recordingStartedAtElapsedMs: Long?,
    canArm: Boolean,
    compact: Boolean,
    onArm: () -> Unit,
    onTapPtt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) { shouldAnimateMotion(context) }

    var elapsedSeconds by remember(recordingStartedAtElapsedMs) { mutableLongStateOf(0L) }
    LaunchedEffect(recordingStartedAtElapsedMs) {
        if (recordingStartedAtElapsedMs == null) {
            elapsedSeconds = 0L
        } else {
            while (true) {
                elapsedSeconds = ((SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs) / 1000L)
                    .coerceAtLeast(0L)
                delay(250L)
            }
        }
    }

    val isActionable = when (pttState) {
        PttState.DISARMED, PttState.ERROR, PttState.PERMISSION_BLOCKED -> canArm
        PttState.ARMED_IDLE, PttState.RECORDING, PttState.SPEAKING_TTS -> true
        PttState.TRANSCRIBING, PttState.WAITING_AGENT -> false
    }

    val targetContainerColor = when (pttState) {
        PttState.DISARMED -> if (canArm) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
        PttState.ARMED_IDLE -> MaterialTheme.colorScheme.primaryContainer
        PttState.RECORDING -> MaterialTheme.colorScheme.error
        PttState.TRANSCRIBING, PttState.WAITING_AGENT -> MaterialTheme.colorScheme.secondaryContainer
        PttState.SPEAKING_TTS -> MaterialTheme.colorScheme.tertiaryContainer
        PttState.PERMISSION_BLOCKED, PttState.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val targetContentColor = when (pttState) {
        PttState.DISARMED -> if (canArm) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        PttState.ARMED_IDLE -> MaterialTheme.colorScheme.onPrimaryContainer
        PttState.RECORDING -> MaterialTheme.colorScheme.onError
        PttState.TRANSCRIBING, PttState.WAITING_AGENT -> MaterialTheme.colorScheme.onSecondaryContainer
        PttState.SPEAKING_TTS -> MaterialTheme.colorScheme.onTertiaryContainer
        PttState.PERMISSION_BLOCKED, PttState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(durationMillis = if (animationsEnabled) 180 else 0),
        label = "pttButtonBg",
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(durationMillis = if (animationsEnabled) 180 else 0),
        label = "pttButtonFg",
    )

    val pulseScale = if (animationsEnabled && (pttState == PttState.RECORDING || pttState == PttState.SPEAKING_TTS)) {
        val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 750),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseScale",
        )
        animatedScale
    } else {
        1.0f
    }

    val buttonSize = if (compact) 76.dp else 104.dp
    val iconSize = if (compact) 32.dp else 40.dp

    @DrawableRes val iconRes: Int = when (pttState) {
        PttState.DISARMED -> R.drawable.ic_headset_mic
        PttState.ARMED_IDLE -> R.drawable.ic_ptt_mic
        PttState.RECORDING -> R.drawable.ic_stop
        PttState.TRANSCRIBING, PttState.WAITING_AGENT -> R.drawable.ic_hourglass_top
        PttState.SPEAKING_TTS -> R.drawable.ic_volume_off
        PttState.PERMISSION_BLOCKED -> R.drawable.ic_warning_amber
        PttState.ERROR -> R.drawable.ic_error_outline
    }

    val primaryLabel = when (pttState) {
        PttState.DISARMED -> "Arm PTT"
        PttState.ARMED_IDLE -> "Tap to Talk"
        PttState.RECORDING -> {
            val mins = elapsedSeconds / 60L
            val secs = elapsedSeconds % 60L
            "Stop & Send · $mins:${secs.toString().padStart(2, '0')}"
        }
        PttState.TRANSCRIBING -> "Transcribing…"
        PttState.WAITING_AGENT -> "Agent Thinking…"
        PttState.SPEAKING_TTS -> "Tap to Interrupt"
        PttState.PERMISSION_BLOCKED -> "Tap to Re-Arm"
        PttState.ERROR -> "Tap to Re-Arm"
    }

    val helperText = when (pttState) {
        PttState.DISARMED -> if (canArm) {
            "Starts foreground audio for screen-off & headset PTT"
        } else {
            "Select a pinned session above to arm"
        }
        PttState.ARMED_IDLE -> "Headset play/pause button also toggles talk"
        PttState.RECORDING -> "Speak clearly, then tap again or press headset button"
        PttState.TRANSCRIBING, PttState.WAITING_AGENT -> statusText
        PttState.SPEAKING_TTS -> "Tap anytime to barge in and interrupt speech"
        PttState.PERMISSION_BLOCKED -> statusText
        PttState.ERROR -> statusText
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 2.dp else 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(buttonSize + 14.dp),
        ) {
            if (pttState == PttState.RECORDING || pttState == PttState.SPEAKING_TTS) {
                Box(
                    modifier = Modifier
                        .size(buttonSize + 14.dp)
                        .scale(pulseScale)
                        .background(
                            color = containerColor.copy(alpha = 0.20f),
                            shape = CircleShape,
                        ),
                )
            }

            Surface(
                onClick = {
                    when (pttState) {
                        PttState.DISARMED, PttState.ERROR, PttState.PERMISSION_BLOCKED -> onArm()
                        PttState.ARMED_IDLE, PttState.RECORDING, PttState.SPEAKING_TTS -> onTapPtt()
                        PttState.TRANSCRIBING, PttState.WAITING_AGENT -> Unit
                    }
                },
                enabled = isActionable,
                shape = CircleShape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = if (isActionable) 3.dp else 0.dp,
                shadowElevation = if (pttState == PttState.RECORDING) 6.dp else 2.dp,
                modifier = Modifier
                    .size(buttonSize)
                    .semantics { contentDescription = primaryLabel },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (pttState == PttState.TRANSCRIBING || pttState == PttState.WAITING_AGENT) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(iconSize),
                            strokeWidth = 3.dp,
                            color = contentColor,
                        )
                    } else {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
        }

        Text(
            text = primaryLabel,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = if (pttState == PttState.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PermissionWarningBanner(
    toolName: String,
    canReArm: Boolean,
    onOpenOpenChamber: () -> Unit,
    onReArm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning_amber),
                    contentDescription = null,
                )
                Text(
                    text = "Tool approval required for $toolName",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Approve or reject this tool in OpenChamber, then tap Re-Arm to resume voice mode.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenOpenChamber,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open OpenChamber")
                }
                OutlinedButton(
                    onClick = onReArm,
                    enabled = canReArm,
                ) {
                    Text("Re-Arm")
                }
            }
        }
    }
}

@Composable
internal fun TurnTranscriptPanel(
    lastTranscript: String?,
    lastReply: String?,
    lastTtsInfo: String?,
    serviceError: String?,
    isSpeaking: Boolean = false,
    canReadAloud: Boolean = false,
    onReadAloudClick: (() -> Unit)? = null,
    onOpenInOpenChamber: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!serviceError.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_error_outline),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = serviceError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (!lastTranscript.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "You",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = lastTranscript,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Latest reply",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!lastReply.isNullOrBlank() && onReadAloudClick != null) {
                        FilledTonalButton(
                            onClick = onReadAloudClick,
                            enabled = canReadAloud,
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (isSpeaking) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSpeaking) "Stop" else "Read aloud",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                Text(
                    text = lastReply?.takeIf { it.isNotBlank() } ?: "No assistant message in this session yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (lastReply.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )

                if (!lastTtsInfo.isNullOrBlank() || onOpenInOpenChamber != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (!lastTtsInfo.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_graphic_eq),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = lastTtsInfo,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        if (onOpenInOpenChamber != null) {
                            TextButton(
                                onClick = onOpenInOpenChamber,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Open in OpenChamber")
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_open_in_new),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionPickerSheet(
    serverGroups: List<ServerSessionsResult>,
    activeServerId: String?,
    pinnedSession: PinnedSession?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onSelect: (ServerProfile, SessionSummary) -> Unit,
    onSignInServer: ((ServerProfile) -> Unit)? = null,
    onAddServer: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedServerFilterId by remember { mutableStateOf<String?>(null) }

    val visibleGroups = remember(serverGroups, selectedServerFilterId) {
        val filterId = selectedServerFilterId?.takeIf { id ->
            serverGroups.any { it.server.id == id }
        }
        if (filterId == null) {
            serverGroups
        } else {
            serverGroups.filter { it.server.id == filterId }
        }
    }
    val hasAnySessions = visibleGroups.any { it.sessions.isNotEmpty() || it.error != null }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Select Session",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onAddServer != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onAddServer()
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("+ Server")
                    }
                }
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = "Refresh sessions",
                        )
                    }
                }
            }

            if (serverGroups.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedServerFilterId == null,
                        onClick = { selectedServerFilterId = null },
                        label = { Text("All (${serverGroups.sumOf { it.sessions.size }})") },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    serverGroups.forEach { group ->
                        FilterChip(
                            selected = selectedServerFilterId == group.server.id,
                            onClick = {
                                selectedServerFilterId = if (selectedServerFilterId == group.server.id) {
                                    null
                                } else {
                                    group.server.id
                                }
                            },
                            label = { Text(group.server.displayLabel) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            if (!error.isNullOrBlank()) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (!hasAnySessions && !isLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No OpenCode sessions found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRefresh) {
                        Text("Reload sessions")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleGroups.forEach { group ->
                        val isGroupActiveServer = group.server.id == activeServerId
                        if (serverGroups.size > 1 || group.error != null) {
                            item(key = "hdr_${group.server.id}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = group.server.displayLabel,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (isGroupActiveServer) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = group.server.serverUrl,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (isGroupActiveServer) {
                                        Text(
                                            text = "Active",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }

                        if (!group.error.isNullOrBlank()) {
                            item(key = "err_${group.server.id}") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            text = group.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (group.isUnauthorized && onSignInServer != null) {
                                            TextButton(
                                                onClick = {
                                                    onDismiss()
                                                    onSignInServer(group.server)
                                                },
                                                modifier = Modifier.heightIn(min = 48.dp),
                                            ) {
                                                Text("Sign in")
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (group.sessions.isEmpty() && !isLoading) {
                            item(key = "empty_${group.server.id}") {
                                Text(
                                    text = "No sessions on this server",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        } else {
                            items(
                                items = group.sessions,
                                key = { session -> "${group.server.id}:${session.id}" },
                            ) { session ->
                                val selected = isGroupActiveServer && pinnedSession?.sessionId == session.id
                                Surface(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clickable { onSelect(group.server, session) },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = session.title.ifBlank { "Untitled session" },
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = session.directory.ifBlank { session.id },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (selected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePickerSheet(
    ttsPipeline: List<TtsEngineOption>,
    initialEngine: TtsEngine,
    geminiVoices: List<TtsVoice>,
    selectedGeminiVoiceId: String,
    isLoadingGemini: Boolean,
    geminiError: String?,
    onRefreshGemini: () -> Unit,
    onSelectGeminiVoice: (TtsVoice) -> Unit,
    mlKitStatus: MlKitStatusReport?,
    mlKitVoices: List<MlKitTtsVoiceSummary>,
    selectedMlKitVoice: String?,
    mlKitDownloadStatus: String?,
    isDownloadingMlKit: Boolean,
    onRefreshMlKit: () -> Unit,
    onSelectMlKitVoice: (String) -> Unit,
    onDownloadMlKitVoice: (String?) -> Unit,
    selectedOpenAiVoice: String,
    onSelectOpenAiVoice: (String) -> Unit,
    systemVoices: List<SystemTtsVoiceSummary>,
    selectedSystemVoice: String?,
    isLoadingSystemVoices: Boolean,
    onRefreshSystemVoices: () -> Unit,
    onSelectSystemVoice: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var activeTab by remember(initialEngine) { mutableStateOf(initialEngine) }
    var geminiQuery by remember { mutableStateOf("") }
    var openAiQuery by remember { mutableStateOf("") }
    var systemQuery by remember { mutableStateOf("") }

    val primaryEngine = remember(ttsPipeline) {
        VoicePipelineDefaults.primaryEnabledEngine(ttsPipeline)
    }
    val orderedEngines = remember(ttsPipeline) {
        ttsPipeline.map { it.engine }
    }
    val activeOption = remember(ttsPipeline, activeTab) {
        ttsPipeline.firstOrNull { it.engine == activeTab }
    }

    val filteredGemini = remember(geminiVoices, geminiQuery) {
        val q = geminiQuery.trim().lowercase()
        if (q.isEmpty()) {
            geminiVoices
        } else {
            geminiVoices.filter { v ->
                "${v.id} ${v.label} ${v.style} ${v.accent} ${v.gender} ${v.description}"
                    .lowercase()
                    .contains(q)
            }
        }
    }

    val filteredOpenAi = remember(openAiQuery) {
        val q = openAiQuery.trim().lowercase()
        if (q.isEmpty()) {
            VoicePipelineDefaults.openAiVoices
        } else {
            VoicePipelineDefaults.openAiVoices.filter { p ->
                "${p.id} ${p.label} ${p.description}".lowercase().contains(q)
            }
        }
    }

    val filteredSystem = remember(systemVoices, systemQuery) {
        val q = systemQuery.trim().lowercase()
        if (q.isEmpty()) {
            systemVoices
        } else {
            systemVoices.filter { v ->
                "${v.name} ${v.label} ${v.localeTag}".lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voices",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = primaryEngine?.let { "Primary engine: ${it.title}" }
                            ?: "All TTS engines are disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when (activeTab) {
                    TtsEngine.GEMINI_BRIDGE -> {
                        IconButton(onClick = onRefreshGemini, enabled = !isLoadingGemini) {
                            if (isLoadingGemini) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = "Refresh Gemini voices",
                                )
                            }
                        }
                    }
                    TtsEngine.MLKIT_GENAI -> {
                        IconButton(onClick = onRefreshMlKit) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Refresh ML Kit voices",
                            )
                        }
                    }
                    TtsEngine.ANDROID_SYSTEM -> {
                        IconButton(onClick = onRefreshSystemVoices, enabled = !isLoadingSystemVoices) {
                            if (isLoadingSystemVoices) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_refresh),
                                    contentDescription = "Refresh system voices",
                                )
                            }
                        }
                    }
                    TtsEngine.OPENAI_CLOUD, TtsEngine.LOCAL_KOKORO -> Unit
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                orderedEngines.forEach { engine ->
                    val enabled = ttsPipeline.firstOrNull { it.engine == engine }?.enabled == true
                    val labelText = if (enabled) engine.shortTitle else "${engine.shortTitle} (off)"
                    FilterChip(
                        selected = activeTab == engine,
                        onClick = { activeTab = engine },
                        label = { Text(labelText) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            if (activeOption != null && !activeOption.enabled) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${activeTab.title} is currently disabled in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                onDismiss()
                                onOpenSettings()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (activeTab) {
                TtsEngine.GEMINI_BRIDGE -> {
                    OutlinedTextField(
                        value = geminiQuery,
                        onValueChange = { geminiQuery = it },
                        label = { Text("Search Gemini voices") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!geminiError.isNullOrBlank()) {
                        Text(
                            text = geminiError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (filteredGemini.isEmpty() && !isLoadingGemini) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (geminiVoices.isEmpty()) "No Gemini voices loaded" else "No matching voices",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (geminiVoices.isEmpty()) {
                                TextButton(onClick = onRefreshGemini) {
                                    Text("Reload voices")
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(filteredGemini, key = { it.id }) { voice ->
                                val selected = selectedGeminiVoiceId.equals(voice.id, ignoreCase = true)
                                val meta = listOfNotNull(
                                    voice.style.takeIf { it.isNotBlank() },
                                    voice.gender.takeIf { it.isNotBlank() },
                                    voice.accent.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                VoiceOptionRow(
                                    title = voice.label,
                                    subtitle = meta.ifBlank { null },
                                    selected = selected,
                                    onClick = { onSelectGeminiVoice(voice) },
                                )
                            }
                        }
                    }
                }

                TtsEngine.MLKIT_GENAI -> {
                    if (mlKitVoices.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (mlKitStatus?.availability == MlKitAvailability.UNAVAILABLE) {
                                        "ML Kit GenAI TTS unavailable on this device"
                                    } else {
                                        "Checking ML Kit GenAI voices…"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val statusDetail = mlKitStatus?.detail
                                if (!statusDetail.isNullOrBlank()) {
                                    Text(
                                        text = statusDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (mlKitStatus?.availability == MlKitAvailability.UNAVAILABLE) {
                                    OutlinedButton(
                                        onClick = {
                                            val report = buildString {
                                                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, ${Build.TAGS})")
                                                appendLine("Packages: ${MlKitSttClient.buildPackageDiagnostic(context)}")
                                                append("TTS GenAI: ${mlKitStatus.availability} | ${mlKitStatus.detail ?: "ok"}")
                                            }
                                            clipboardManager.setText(AnnotatedString(report))
                                            Toast.makeText(context, "Copied ML Kit diagnostics", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp),
                                    ) {
                                        Text("Copy diagnostics")
                                    }
                                }
                            }
                        }
                    } else {
                        val activeChoice = mlKitVoices.firstOrNull {
                            it.name.equals(selectedMlKitVoice, ignoreCase = true)
                        } ?: mlKitVoices.firstOrNull()

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(mlKitVoices, key = { it.name }) { voice ->
                                val selected = selectedMlKitVoice.equals(voice.name, ignoreCase = true)
                                val stateLabel = when (voice.availability) {
                                    MlKitAvailability.AVAILABLE -> "Ready"
                                    MlKitAvailability.DOWNLOADABLE -> "Download needed"
                                    MlKitAvailability.DOWNLOADING -> "Downloading"
                                    MlKitAvailability.UNAVAILABLE -> "Unavailable"
                                }
                                val subtitle = listOfNotNull(
                                    voice.description.takeIf { it.isNotBlank() },
                                    stateLabel,
                                ).joinToString(" · ")
                                VoiceOptionRow(
                                    title = voice.name,
                                    subtitle = subtitle,
                                    selected = selected,
                                    onClick = { onSelectMlKitVoice(voice.name) },
                                )
                            }
                        }

                        if (activeChoice?.availability == MlKitAvailability.DOWNLOADABLE) {
                            FilledTonalButton(
                                onClick = { onDownloadMlKitVoice(activeChoice.name) },
                                enabled = !isDownloadingMlKit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(
                                    if (isDownloadingMlKit) {
                                        "Downloading ${activeChoice.name}…"
                                    } else {
                                        "Download ${activeChoice.name}"
                                    },
                                )
                            }
                        }

                        if (!mlKitDownloadStatus.isNullOrBlank()) {
                            Text(
                                text = mlKitDownloadStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                TtsEngine.OPENAI_CLOUD -> {
                    OutlinedTextField(
                        value = openAiQuery,
                        onValueChange = { openAiQuery = it },
                        label = { Text("Search OpenAI voices") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filteredOpenAi, key = { it.id }) { preset ->
                            val selected = selectedOpenAiVoice.equals(preset.id, ignoreCase = true)
                            VoiceOptionRow(
                                title = preset.label,
                                subtitle = preset.description,
                                selected = selected,
                                onClick = { onSelectOpenAiVoice(preset.id) },
                            )
                        }
                    }
                }

                TtsEngine.ANDROID_SYSTEM -> {
                    OutlinedTextField(
                        value = systemQuery,
                        onValueChange = { systemQuery = it },
                        label = { Text("Search system voices") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item(key = "__system_default__") {
                            VoiceOptionRow(
                                title = "System default",
                                subtitle = "Follow Android Text-to-Speech preferred voice",
                                selected = selectedSystemVoice == null,
                                onClick = { onSelectSystemVoice(null) },
                            )
                        }
                        items(filteredSystem, key = { it.name }) { voice ->
                            val selected = selectedSystemVoice == voice.name
                            val modeTag = if (voice.isNetworkRequired) "Network" else "Offline"
                            VoiceOptionRow(
                                title = voice.label,
                                subtitle = "${voice.name} · $modeTag",
                                selected = selected,
                                onClick = { onSelectSystemVoice(voice.name) },
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent("com.android.settings.TTS_SETTINGS").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Open Android TTS settings")
                    }
                }

                TtsEngine.LOCAL_KOKORO -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Host-configured voice",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Local Kokoro synthesizes on your OpenChamber host via /api/dictation/tts/speak using the server's default speaker.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun VoiceOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
