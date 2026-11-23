package com.openchamber.ptt.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openchamber.ptt.R
import com.openchamber.ptt.audio.CuePackWarmState
import com.openchamber.ptt.bubble.BubbleAvailability
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.ServerProfile
import com.openchamber.ptt.tts.AudioCueMode
import com.openchamber.ptt.tts.RoleVoiceProfileMap
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.TtsCacheStats
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.tts.VoiceRoleResolver
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient
import java.util.Locale

internal data class PermissionStatusSnapshot(
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

internal fun readPermissionSnapshot(context: Context): PermissionStatusSnapshot {
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
internal fun launchBatteryOptimizationSettings(context: Context) {
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

@Composable
internal fun PermissionCheckRow(
    label: String,
    granted: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = if (granted) "Granted" else "Not granted"
            },
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
internal fun ServerConnectionFields(
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
internal fun SettingsBottomSheet(
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
    ttsInputMode: TtsInputMode,
    onSelectTtsInputMode: (TtsInputMode) -> Unit,
    spokenDetailLevel: SpokenDetailLevel,
    onSelectSpokenDetailLevel: (SpokenDetailLevel) -> Unit,
    audioCueMode: AudioCueMode,
    onSelectAudioCueMode: (AudioCueMode) -> Unit,
    cuePackWarmState: CuePackWarmState,
    roleVoiceProfiles: RoleVoiceProfileMap,
    availableGeminiVoiceIds: List<String>,
    availableMlKitVoiceNames: List<String>,
    systemVoiceLabels: Map<String, String>,
    ttsCacheStats: TtsCacheStats,
    onClearTtsCache: () -> Unit,
    selectedGeminiVoice: String,
    selectedOpenAiVoice: String,
    selectedSystemVoiceLabel: String?,
    onOpenVoicePickerFor: (TtsEngine, VoiceRole) -> Unit,
    sttEngine: SttEngine,
    onSelectSttEngine: (SttEngine) -> Unit,
    sttFallbackToParakeet: Boolean,
    onToggleSttFallback: (Boolean) -> Unit,
    quickTurnDirectAnswer: Boolean,
    onToggleQuickTurnDirectAnswer: (Boolean) -> Unit,
    quickTranscriptCleanup: Boolean,
    onToggleQuickTranscriptCleanup: (Boolean) -> Unit,
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
    onOpenTelemetryLog: () -> Unit,
    onOpenAudioDebug: () -> Unit = {},
    pinBluetoothSco: Boolean = false,
    volumeKeyStopRecording: Boolean = false,
    onToggleVolumeKeyStopRecording: (Boolean) -> Unit = {},
    telecomCallForRecording: Boolean = false,
    onToggleTelecomCallForRecording: (Boolean) -> Unit = {},
    mirrorRecordingNotification: Boolean = true,
    onToggleMirrorRecordingNotification: (Boolean) -> Unit = {},
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isReorderingTts by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !isReorderingTts),
    ) {
        val sheetView = LocalView.current
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            DisposableEffect(sheetView, isReorderingTts) {
                if (!isReorderingTts) {
                    onDispose {}
                } else {
                    val dispatcher = sheetView.findOnBackInvokedDispatcher()
                    val callback = OnBackInvokedCallback {
                        isReorderingTts = false
                    }
                    dispatcher?.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        callback,
                    )
                    onDispose {
                        dispatcher?.unregisterOnBackInvokedCallback(callback)
                    }
                }
            }
        }
        BackHandler(enabled = isReorderingTts) {
            isReorderingTts = false
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Device & Multitasking",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    }
                }

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

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Hardware Controls & Wearables",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Device-global settings shared across all servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = mirrorRecordingNotification,
                            role = Role.Switch,
                            onValueChange = onToggleMirrorRecordingNotification,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Mirror recording control to watch",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Posts a silent notification with a 'Stop recording' action during capture for Garmin and Wear OS watches.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = mirrorRecordingNotification,
                            onCheckedChange = null,
                        )
                    }
                }

                if (mirrorRecordingNotification && !snapshot.hasPostNotifications) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_warning_amber),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Notifications permission is required on Android 13+ for smartwatch apps to see recording controls.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onRequestPermissions,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(
                                    text = "Grant",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = volumeKeyStopRecording,
                            role = Role.Switch,
                            onValueChange = onToggleVolumeKeyStopRecording,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Volume button stops recording (screen off)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Intercepts phone Volume Up/Down only while recording, restoring normal media volume immediately on stop (Android 15+ and standard AOSP; Pixel Android 12–14 ignores remote session volume).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = volumeKeyStopRecording,
                            onCheckedChange = null,
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = telecomCallForRecording,
                            role = Role.Switch,
                            onValueChange = onToggleTelecomCallForRecording,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Headset call mode during recording (Shokz / HFP)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Opens a temporary local Telecom call during capture (when Headset mic is enabled) so headset call-hangup buttons stop recording. Triggers headset call chimes and In-Call UI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = telecomCallForRecording,
                            onCheckedChange = null,
                        )
                    }
                }

                if (telecomCallForRecording && !pinBluetoothSco) {
                    Text(
                        text = "Turn on 'Headset mic' on the main console to activate Telecom call routing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                if (telecomCallForRecording && !snapshot.hasBluetoothConnect) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_warning_amber),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Bluetooth permission is recommended on Android 12+ to route calls to your headset.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onRequestPermissions,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(
                                    text = "Grant",
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
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
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            label = { Text("${activeServer.displayLabel} only") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                    Text(
                        text = if (isUsingCustomServerVoice) {
                            "STT engine, speech input mode, audio cues, TTS order, and cloud role voices are saved just for ${activeServer.displayLabel}."
                        } else {
                            "Using shared global STT/TTS, audio cue, and role voice settings. Switch to \"${activeServer.displayLabel} only\" if this server needs its own setup."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
            }

            Column(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = sttEngine == engine,
                                role = Role.RadioButton,
                                onClick = { onSelectSttEngine(engine) },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sttEngine == engine,
                                onClick = null,
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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = sttFallbackToParakeet,
                                role = Role.Switch,
                                onValueChange = onToggleSttFallback,
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Speech Input Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TtsInputMode.entries.forEach { mode ->
                        FilterChip(
                            selected = ttsInputMode == mode,
                            onClick = { onSelectTtsInputMode(mode) },
                            label = { Text(mode.title) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Text(
                    text = ttsInputMode.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (ttsInputMode == TtsInputMode.SUMMARIZED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SpokenDetailSlider(
                        detailLevel = spokenDetailLevel,
                        onSelectLevel = onSelectSpokenDetailLevel,
                        subtitle = spokenDetailLevel.subtitle,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = quickTurnDirectAnswer,
                            role = Role.Switch,
                            onValueChange = onToggleQuickTurnDirectAnswer,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local quick-turn answers",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Answer quick follow-ups about the latest reply locally without prompting the session (live tuning commands stay active)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = quickTurnDirectAnswer,
                            onCheckedChange = null,
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = quickTranscriptCleanup,
                            role = Role.Switch,
                            onValueChange = onToggleQuickTranscriptCleanup,
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Quick-mode transcript cleanup",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Remove filled pauses and stutters with the small model in direct mode (off sends raw speech verbatim)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = quickTranscriptCleanup,
                            onCheckedChange = null,
                        )
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Audio Cues",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AudioCueMode.entries.forEach { mode ->
                        FilterChip(
                            selected = audioCueMode == mode,
                            onClick = { onSelectAudioCueMode(mode) },
                            label = { Text(mode.title) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Text(
                    text = audioCueMode.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (audioCueMode != AudioCueMode.TONES) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (cuePackWarmState.isWarming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                text = formatCueWarmStatus(cuePackWarmState),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Voice Roles",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Tap a role to customize or preview its voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                VoiceRole.entries.forEach { role ->
                    val roleSummary = VoiceRoleResolver.formatRoleSummaryLabel(
                        role = role,
                        basePipeline = ttsPipeline,
                        profiles = roleVoiceProfiles,
                        availableGeminiVoices = availableGeminiVoiceIds,
                        availableMlKitVoices = availableMlKitVoiceNames,
                        availableSystemVoiceLabels = systemVoiceLabels,
                    )
                    val rolePrimaryEngine = VoicePipelineDefaults.primaryEnabledEngine(
                        VoiceRoleResolver.resolvePipelineForRole(ttsPipeline, roleVoiceProfiles[role]),
                    ) ?: TtsEngine.GEMINI_BRIDGE
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .heightIn(min = 52.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = "Customize ${role.title} voice",
                                onClick = { onOpenVoicePickerFor(rolePrimaryEngine, role) },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = role.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = roleSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
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
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!isReorderingTts) {
                                    Modifier.clickable(
                                        role = Role.Button,
                                        onClickLabel = "Pick voice for ${option.engine.title}",
                                        onClick = { onOpenVoicePickerFor(option.engine, VoiceRole.AGENT) },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 14.dp,
                                    end = if (isReorderingTts) 4.dp else 14.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                    modifier = Modifier.semantics {
                                        contentDescription = "Enable ${option.engine.title}"
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
                            .padding(top = 2.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Copy ML Kit diagnostics")
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TTS Cache",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = formatTtsCacheStats(ttsCacheStats),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = onClearTtsCache,
                            enabled = ttsCacheStats.totalEntries > 0,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Clear")
                        }
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Servers",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (showServerForm) "Hide form" else "+ Add server")
                    }
                }

                servers.forEach { profile ->
                    val isActive = profile.id == activeServer?.id
                    val hasTrailingAction = !profile.isSignedIn || servers.size > 1
                    val statusLabel = buildString {
                        if (isActive) append("Active · ")
                        append(if (profile.isSignedIn) "Signed in" else "Sign in needed")
                        if (profile.voiceOverride != null) append(" · Custom voice")
                    }
                    Surface(
                        color = if (isActive) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
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
                                .padding(
                                    start = 14.dp,
                                    end = if (hasTrailingAction) 6.dp else 14.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Sign in")
                                }
                            } else if (servers.size > 1) {
                                TextButton(
                                    onClick = { onRemoveServer(profile) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
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
                            .padding(top = 2.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text("Sign out of ${activeServer.displayLabel}")
                    }
                }
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenTelemetryLog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("View Voice UX Telemetry Log")
                }
                OutlinedButton(
                    onClick = onOpenAudioDebug,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Open Live Audio & BT Debug")
                }
            }
        }
    }
}

internal fun formatMlKitBadge(status: MlKitAvailability?): String = when (status) {
    MlKitAvailability.AVAILABLE -> "Ready"
    MlKitAvailability.DOWNLOADABLE -> "Downloadable"
    MlKitAvailability.DOWNLOADING -> "Downloading"
    MlKitAvailability.UNAVAILABLE -> "Unavailable"
    null -> "Checking…"
}

internal fun formatCueWarmStatus(state: CuePackWarmState): String = when {
    state.isWarming -> "Warming vocal cues: ${state.warmedCount}/${state.totalCount}…"
    state.warmedCount > 0 ->
        "Vocal cues cached: ${state.warmedCount}/${state.totalCount} (${state.engine?.shortTitle ?: "Ready"} · ${state.voiceId ?: "Auto"})"
    else -> "Vocal cues warm automatically when armed"
}

internal fun formatTtsCacheStats(stats: TtsCacheStats): String {
    if (stats.totalEntries == 0) {
        return "Empty · summaries and synthesized audio are cached for instant offline re-reads"
    }
    val mb = stats.totalBytes / (1024.0 * 1024.0)
    val summaryLabel = if (stats.textEntries == 1) "summary" else "summaries"
    val clipLabel = if (stats.audioEntries == 1) "audio clip" else "audio clips"
    return String.format(
        Locale.US,
        "%.1f MB · %d %s · %d %s",
        mb,
        stats.textEntries,
        summaryLabel,
        stats.audioEntries,
        clipLabel,
    )
}
