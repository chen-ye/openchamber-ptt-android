package com.openchamber.ptt.ui

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.R
import com.openchamber.ptt.service.PttState
import com.openchamber.ptt.storage.PinnedSession
import com.openchamber.ptt.storage.StagedDraft
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.voice.ResponseFeedState
import com.openchamber.ptt.voice.TurnTriggerKind
import kotlinx.coroutines.delay
import java.util.Locale

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
        PttState.TRANSCRIBING_SEND -> PttStateVisuals(
            label = "Transcribing",
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            dotColor = scheme.secondary,
        )
        PttState.TRANSCRIBING_STAGE -> PttStateVisuals(
            label = "Organizing draft",
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            dotColor = scheme.secondary,
        )
        PttState.SEND_GRACE -> PttStateVisuals(
            label = "Sending soon",
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
            dotColor = scheme.tertiary,
        )
        PttState.STAGED_DRAFT -> PttStateVisuals(
            label = "Draft staged",
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            dotColor = scheme.primary,
        )
        PttState.STAGED_DRAFT_SPEAKING -> PttStateVisuals(
            label = "Reading draft",
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
            dotColor = scheme.tertiary,
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
        modifier = modifier.semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeroPttControl(
    pttState: PttState,
    statusText: String,
    recordingStartedAtElapsedMs: Long?,
    canArm: Boolean,
    compact: Boolean,
    onArm: () -> Unit,
    onTapPtt: () -> Unit,
    onStageCapture: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    onPullBackToDraft: (() -> Unit)? = null,
    sendGraceRemainingMs: Long? = null,
    pendingQuickTranscript: String? = null,
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
        PttState.ARMED_IDLE,
        PttState.RECORDING,
        PttState.SEND_GRACE,
        PttState.STAGED_DRAFT,
        PttState.STAGED_DRAFT_SPEAKING,
        PttState.SPEAKING_TTS,
        -> true
        PttState.TRANSCRIBING_SEND, PttState.TRANSCRIBING_STAGE, PttState.WAITING_AGENT -> false
    }

    val targetContainerColor = when (pttState) {
        PttState.DISARMED -> if (canArm) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
        PttState.ARMED_IDLE, PttState.STAGED_DRAFT -> MaterialTheme.colorScheme.primaryContainer
        PttState.RECORDING -> MaterialTheme.colorScheme.error
        PttState.TRANSCRIBING_SEND,
        PttState.TRANSCRIBING_STAGE,
        PttState.WAITING_AGENT,
        -> MaterialTheme.colorScheme.secondaryContainer
        PttState.SEND_GRACE,
        PttState.STAGED_DRAFT_SPEAKING,
        PttState.SPEAKING_TTS,
        -> MaterialTheme.colorScheme.tertiaryContainer
        PttState.PERMISSION_BLOCKED, PttState.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val targetContentColor = when (pttState) {
        PttState.DISARMED -> if (canArm) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        PttState.ARMED_IDLE, PttState.STAGED_DRAFT -> MaterialTheme.colorScheme.onPrimaryContainer
        PttState.RECORDING -> MaterialTheme.colorScheme.onError
        PttState.TRANSCRIBING_SEND,
        PttState.TRANSCRIBING_STAGE,
        PttState.WAITING_AGENT,
        -> MaterialTheme.colorScheme.onSecondaryContainer
        PttState.SEND_GRACE,
        PttState.STAGED_DRAFT_SPEAKING,
        PttState.SPEAKING_TTS,
        -> MaterialTheme.colorScheme.onTertiaryContainer
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

    val isPulsing = pttState == PttState.RECORDING ||
        pttState == PttState.SPEAKING_TTS ||
        pttState == PttState.STAGED_DRAFT_SPEAKING ||
        pttState == PttState.SEND_GRACE
    val pulseScale = if (animationsEnabled && isPulsing) {
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
        PttState.ARMED_IDLE, PttState.STAGED_DRAFT -> R.drawable.ic_ptt_mic
        PttState.RECORDING -> R.drawable.ic_stop
        PttState.SEND_GRACE -> R.drawable.ic_undo
        PttState.TRANSCRIBING_SEND, PttState.TRANSCRIBING_STAGE, PttState.WAITING_AGENT -> R.drawable.ic_hourglass_top
        PttState.SPEAKING_TTS, PttState.STAGED_DRAFT_SPEAKING -> R.drawable.ic_volume_off
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
        PttState.TRANSCRIBING_SEND -> "Transcribing…"
        PttState.TRANSCRIBING_STAGE -> "Organizing Draft…"
        PttState.SEND_GRACE -> {
            val secsText = sendGraceRemainingMs?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "2.5s"
            "Tap to Undo · $secsText"
        }
        PttState.STAGED_DRAFT -> "Append Voice"
        PttState.STAGED_DRAFT_SPEAKING -> "Stop Preview"
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
        PttState.ARMED_IDLE -> "1x headset tap: talk · 2x/Next: read latest reply"
        PttState.RECORDING -> "1x: quick send · 2x/Next: stage & organize · 3x/Prev: discard"
        PttState.TRANSCRIBING_SEND, PttState.TRANSCRIBING_STAGE, PttState.WAITING_AGENT -> statusText
        PttState.SEND_GRACE -> pendingQuickTranscript?.takeIf { it.isNotBlank() }
            ?: "Sending shortly — tap anywhere to pull into staged draft"
        PttState.STAGED_DRAFT -> "1x: append voice · 2x/Next: send draft · 3x/Prev: discard"
        PttState.STAGED_DRAFT_SPEAKING -> "1x: pause preview · 2x/Next: send now · 3x/Prev: discard"
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
            if (isPulsing) {
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
                        PttState.SEND_GRACE -> (onPullBackToDraft ?: onTapPtt)()
                        PttState.ARMED_IDLE,
                        PttState.RECORDING,
                        PttState.STAGED_DRAFT,
                        PttState.STAGED_DRAFT_SPEAKING,
                        PttState.SPEAKING_TTS,
                        -> onTapPtt()
                        PttState.TRANSCRIBING_SEND, PttState.TRANSCRIBING_STAGE, PttState.WAITING_AGENT -> Unit
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
                    if (pttState.isTranscribing || pttState == PttState.WAITING_AGENT) {
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
            maxLines = 2,
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
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        if (pttState == PttState.RECORDING && (onStageCapture != null || onDiscard != null)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onStageCapture != null) {
                    FilledTonalButton(
                        onClick = onStageCapture,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pause),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stage Draft")
                    }
                }
                if (onDiscard != null) {
                    OutlinedButton(
                        onClick = onDiscard,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Discard")
                    }
                }
            }
        } else if (pttState == PttState.SEND_GRACE && onDiscard != null) {
            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Discard")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StagedDraftCard(
    draft: StagedDraft,
    pttState: PttState,
    isOrganizing: Boolean,
    onUpdateDraftText: (String) -> Unit,
    onCommitDraft: () -> Unit,
    onReorganizeDraft: () -> Unit,
    onRevertDraft: () -> Unit,
    onDiscardDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editedText by remember(draft.sessionKey, draft.organizedText) {
        mutableStateOf(draft.organizedText)
    }
    val hasUnsavedEdits = editedText.trim() != draft.organizedText.trim()
    val canCommit = pttState == PttState.STAGED_DRAFT || pttState == PttState.STAGED_DRAFT_SPEAKING
    val canEditOrDiscard = pttState != PttState.RECORDING && !pttState.isTranscribing

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (pttState == PttState.DISARMED) {
                            "Saved Draft (Arm PTT to append or send)"
                        } else {
                            "Staged Draft"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${draft.rawBursts.size} spoken burst${if (draft.rawBursts.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isOrganizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }

            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                enabled = canEditOrDiscard && !isOrganizing,
                label = { Text("Prompt draft") },
                minLines = 2,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasUnsavedEdits) {
                    FilledTonalButton(
                        onClick = { onUpdateDraftText(editedText) },
                        enabled = canEditOrDiscard && editedText.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Save Edit")
                    }
                }
                Button(
                    onClick = {
                        if (hasUnsavedEdits && editedText.isNotBlank()) {
                            onUpdateDraftText(editedText)
                        }
                        onCommitDraft()
                    },
                    enabled = canCommit && !isOrganizing && editedText.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send")
                }
                FilledTonalButton(
                    onClick = {
                        if (hasUnsavedEdits && editedText.isNotBlank()) {
                            onUpdateDraftText(editedText)
                        }
                        onReorganizeDraft()
                    },
                    enabled = canEditOrDiscard && !isOrganizing && editedText.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Re-organize")
                }
                if (!draft.previousOrganizedText.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = onRevertDraft,
                        enabled = canEditOrDiscard && !isOrganizing,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_undo),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Undo Edit")
                    }
                }
                OutlinedButton(
                    onClick = onDiscardDraft,
                    enabled = canEditOrDiscard && !isOrganizing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Discard")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenOpenChamber,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TurnTranscriptPanel(
    lastTranscript: String?,
    lastReply: String?,
    lastSpokenSummary: String? = null,
    lastTtsInfo: String?,
    serviceError: String?,
    pttState: PttState = PttState.DISARMED,
    responseFeed: ResponseFeedState = ResponseFeedState(),
    showQueueStrip: Boolean = true,
    isSpeaking: Boolean = false,
    canReadAloud: Boolean = false,
    onReadAloudClick: (() -> Unit)? = null,
    onStepResponse: ((Int) -> Unit)? = null,
    onSelectResponse: ((String) -> Unit)? = null,
    onMarkAllRead: (() -> Unit)? = null,
    onDismissDirectAnswer: (() -> Unit)? = null,
    onOpenInOpenChamber: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val inspectable = responseFeed.inspectableResponses
    val unreadQueue = responseFeed.unreadQueue
    val focused = responseFeed.focusedResponse
    val focusedIdx = focused
        ?.let { f -> inspectable.indexOfFirst { it.messageId == f.messageId } }
        ?.takeIf { it >= 0 }
        ?: 0
    val isCapturingOrThinking = pttState == PttState.RECORDING ||
        pttState.isTranscribing ||
        pttState == PttState.SEND_GRACE ||
        pttState == PttState.WAITING_AGENT
    val replyText = focused?.assistantText?.takeIf { it.isNotBlank() }
        ?: lastReply?.takeIf { it.isNotBlank() }
    val activeTurnCount = responseFeed.activeTurn?.responses?.size ?: 0

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

            val rawDirectAnswer = responseFeed.directAnswer
            val directAnswer = rawDirectAnswer?.takeIf { ans ->
                ans.targetMessageId == null ||
                    focused == null ||
                    ans.targetMessageId == focused.messageId
            }
            if (directAnswer != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 12.dp,
                                top = if (onDismissDirectAnswer != null) 4.dp else 10.dp,
                                end = if (onDismissDirectAnswer != null) 4.dp else 12.dp,
                                bottom = 12.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_graphic_eq),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Quick answer",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (onDismissDirectAnswer != null) {
                                IconButton(onClick = onDismissDirectAnswer) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "Dismiss quick answer",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        if (directAnswer.question.isNotBlank()) {
                            Text(
                                text = directAnswer.question,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    end = if (onDismissDirectAnswer != null) 8.dp else 0.dp,
                                ),
                            )
                        }
                        Text(
                            text = directAnswer.spokenAnswer,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(
                                end = if (onDismissDirectAnswer != null) 8.dp else 0.dp,
                            ),
                        )
                    }
                }
            }

            if (isCapturingOrThinking && !lastTranscript.isNullOrBlank()) {
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
            } else if (focused != null) {
                val triggerLabel = when (focused.triggerKind) {
                    TurnTriggerKind.USER_PROMPT -> "You"
                    TurnTriggerKind.ASYNC_SESSION_RESULT ->
                        "Async result${focused.triggerLabel?.let { " · $it" }.orEmpty()}"
                    TurnTriggerKind.SUBAGENT_REPORT ->
                        "Subagent${focused.triggerLabel?.let { " · $it" }.orEmpty()}"
                }
                val triggerColor = when (focused.triggerKind) {
                    TurnTriggerKind.USER_PROMPT -> MaterialTheme.colorScheme.primary
                    TurnTriggerKind.ASYNC_SESSION_RESULT,
                    TurnTriggerKind.SUBAGENT_REPORT,
                    -> MaterialTheme.colorScheme.tertiary
                }
                val isFocusedUnread = unreadQueue.any { it.messageId == focused.messageId }
                val triggerBody = focused.triggerText?.takeIf { it.isNotBlank() }

                if (triggerBody != null || isFocusedUnread || focused.triggerKind != TurnTriggerKind.USER_PROMPT) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = triggerLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = triggerColor,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isFocusedUnread) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    )
                                    Text(
                                        text = "Unread",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        if (triggerBody != null) {
                            Text(
                                text = triggerBody,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else if (!lastTranscript.isNullOrBlank()) {
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
                val panelContext = LocalContext.current
                @Suppress("DEPRECATION")
                val clipboardManager = LocalClipboardManager.current

                if (inspectable.size <= 1 && unreadQueue.isEmpty()) {
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
                            modifier = Modifier.weight(1f),
                        )
                        if (replyText != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(replyText))
                                        Toast.makeText(panelContext, "Copied reply", Toast.LENGTH_SHORT).show()
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_content_copy),
                                        contentDescription = "Copy reply",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                if (onReadAloudClick != null) {
                                    FilledTonalButton(
                                        onClick = onReadAloudClick,
                                        enabled = canReadAloud,
                                        modifier = Modifier.heightIn(min = 48.dp),
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
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val headerBase = if (inspectable.size > 1) {
                            "Response ${focusedIdx + 1} of ${inspectable.size}"
                        } else {
                            "Latest reply"
                        }
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                                    append(headerBase)
                                }
                                if (unreadQueue.isNotEmpty()) {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                        append(" · ${unreadQueue.size} unread")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (inspectable.size > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onStepResponse?.invoke(-1) },
                                    enabled = onStepResponse != null && focusedIdx > 0,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_left),
                                        contentDescription = "Previous response",
                                    )
                                }
                                IconButton(
                                    onClick = { onStepResponse?.invoke(1) },
                                    enabled = onStepResponse != null && focusedIdx < inspectable.lastIndex,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = "Next response",
                                    )
                                }
                            }
                        }
                    }

                    if (replyText != null || unreadQueue.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (unreadQueue.isNotEmpty() && onMarkAllRead != null) {
                                TextButton(
                                    onClick = onMarkAllRead,
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .heightIn(min = 48.dp),
                                ) {
                                    Text("Mark read")
                                }
                            }
                            if (replyText != null) {
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(replyText))
                                        Toast.makeText(panelContext, "Copied reply", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_content_copy),
                                        contentDescription = "Copy reply",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (onReadAloudClick != null && (replyText != null || unreadQueue.isNotEmpty())) {
                                val readLabel = when {
                                    isSpeaking -> "Stop"
                                    unreadQueue.isNotEmpty() -> "Read all (${unreadQueue.size})"
                                    activeTurnCount > 1 -> "Read turn ($activeTurnCount)"
                                    else -> "Read aloud"
                                }
                                FilledTonalButton(
                                    onClick = onReadAloudClick,
                                    enabled = canReadAloud,
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .heightIn(min = 48.dp),
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
                                        text = readLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                if (replyText != null) {
                    MarkdownText(
                        markdown = replyText,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        baseStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "No assistant message in this session yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val scopedSpokenSummary = if (focused != null) {
                    responseFeed.focusedSpokenSummary
                } else {
                    lastSpokenSummary
                }
                val spokenReadout = scopedSpokenSummary?.takeIf { it.isNotBlank() }
                if (replyText != null && spokenReadout != null) {
                    var readoutExpanded by remember(replyText, spokenReadout) { mutableStateOf(false) }
                    val isSummarized = spokenReadout.trim() != replyText.trim()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .clickable { readoutExpanded = !readoutExpanded }
                                    .semantics(mergeDescendants = true) {
                                        role = Role.Button
                                        stateDescription = if (readoutExpanded) "Expanded" else "Collapsed"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_graphic_eq),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (isSummarized) {
                                            "Spoken summary · ${spokenReadout.length} chars"
                                        } else {
                                            "Spoken readout · ${spokenReadout.length} chars"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    painter = painterResource(
                                        if (readoutExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
                                    ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            AnimatedVisibility(visible = readoutExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                ) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                    Text(
                                        text = spokenReadout,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (showQueueStrip && unreadQueue.isNotEmpty()) {
                    var queueExpanded by remember(unreadQueue.isEmpty()) { mutableStateOf(false) }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .clickable { queueExpanded = !queueExpanded }
                                    .semantics(mergeDescendants = true) {
                                        role = Role.Button
                                        stateDescription = if (queueExpanded) "Expanded" else "Collapsed"
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    )
                                    Text(
                                        text = "Up next · ${unreadQueue.size} unread",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    painter = painterResource(
                                        if (queueExpanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
                                    ),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            AnimatedVisibility(visible = queueExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                ) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                    unreadQueue.forEach { item ->
                                        val isItemSpeaking = responseFeed.speakingMessageId == item.messageId
                                        val isItemFocused = focused?.messageId == item.messageId
                                        val sourceLabel = when (item.triggerKind) {
                                            TurnTriggerKind.USER_PROMPT -> "You"
                                            TurnTriggerKind.ASYNC_SESSION_RESULT ->
                                                "Async result${item.triggerLabel?.let { " · $it" }.orEmpty()}"
                                            TurnTriggerKind.SUBAGENT_REPORT ->
                                                "Subagent${item.triggerLabel?.let { " · $it" }.orEmpty()}"
                                        }
                                        val statusTag = when {
                                            isItemSpeaking -> " · Speaking"
                                            isItemFocused -> " · Viewing"
                                            else -> ""
                                        }
                                        val itemLabelColor = when {
                                            isItemSpeaking -> MaterialTheme.colorScheme.tertiary
                                            isItemFocused -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        val preview = item.assistantText
                                            .replace('\n', ' ')
                                            .replace(COLLAPSE_WHITESPACE_REGEX, " ")
                                            .trim()

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clickable(
                                                    enabled = onSelectResponse != null,
                                                    role = Role.Button,
                                                ) {
                                                    onSelectResponse?.invoke(item.messageId)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier.size(16.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (isItemSpeaking) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_graphic_eq),
                                                        contentDescription = "Speaking",
                                                        tint = MaterialTheme.colorScheme.tertiary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .background(
                                                                color = if (isItemFocused) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.outline
                                                                },
                                                                shape = CircleShape,
                                                            ),
                                                    )
                                                }
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Text(
                                                    text = "$sourceLabel$statusTag",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = itemLabelColor,
                                                    fontWeight = if (isItemSpeaking || isItemFocused) {
                                                        FontWeight.SemiBold
                                                    } else {
                                                        FontWeight.Medium
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = preview,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!lastTtsInfo.isNullOrBlank() || onOpenInOpenChamber != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!lastTtsInfo.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
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
                        }

                        if (onOpenInOpenChamber != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
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
}

@Composable
internal fun SpokenDetailSlider(
    detailLevel: SpokenDetailLevel,
    onSelectLevel: (SpokenDetailLevel) -> Unit,
    subtitle: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier: Modifier = Modifier,
) {
    val entries = SpokenDetailLevel.entries
    val selectedIndex = entries.indexOf(detailLevel).coerceIn(0, entries.lastIndex)

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Spoken detail",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detailLevel.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { raw ->
                    val snapped = kotlin.math.round(raw).toInt().coerceIn(0, entries.lastIndex)
                    onSelectLevel(entries[snapped])
                },
                valueRange = 0f..entries.lastIndex.toFloat(),
                steps = (entries.size - 2).coerceAtLeast(0),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Spoken detail level: ${detailLevel.title}"
                    },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEachIndexed { idx, level ->
                    val isSelected = idx == selectedIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelectLevel(level) },
                            ),
                        contentAlignment = when (idx) {
                            0 -> Alignment.CenterStart
                            entries.lastIndex -> Alignment.CenterEnd
                            else -> Alignment.Center
                        },
                    ) {
                        Text(
                            text = level.title,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private val COLLAPSE_WHITESPACE_REGEX = Regex("\\s+")
