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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import com.openchamber.ptt.audio.TelecomHangupAction
import com.openchamber.ptt.bubble.BubbleAvailability
import com.openchamber.ptt.bubble.BubbleHelper
import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.service.BluetoothCaptureMode
import com.openchamber.ptt.service.ControlHintFormatter
import com.openchamber.ptt.service.ControlPreset
import com.openchamber.ptt.service.ControlSchemeConfig
import com.openchamber.ptt.service.HeadsetTapMode
import com.openchamber.ptt.service.VolumeKeyDraftMode
import com.openchamber.ptt.service.VolumeKeyRecordingMode
import com.openchamber.ptt.storage.ServerProfile
import com.openchamber.ptt.tts.AudioCueMode
import com.openchamber.ptt.tts.RoleVoiceProfileMap
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.SttEngine
import com.openchamber.ptt.tts.TtsCacheStats
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsInputMode
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.tts.VoiceRoleResolver
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient
import java.util.Locale

internal enum class SettingsSection(val title: String) {
    CONTROLS("Controls"),
    VOICE("Voice"),
    SERVERS("Servers"),
    SYSTEM("System"),
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    activeSection: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
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
    onOpenVoicePickerForRole: (VoiceRole) -> Unit,
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
    onOpenAudioDebug: () -> Unit,
    controlScheme: ControlSchemeConfig,
    onApplyControlPreset: (ControlPreset) -> Unit,
    onTogglePinBluetoothSco: (Boolean) -> Unit,
    onSelectBluetoothCaptureMode: (BluetoothCaptureMode) -> Unit,
    onSelectTelecomHangupAction: (TelecomHangupAction) -> Unit,
    onSelectHeadsetTapMode: (HeadsetTapMode) -> Unit,
    onToggleAvrcpTrackControls: (Boolean) -> Unit,
    onSelectVolumeKeyRecordingMode: (VolumeKeyRecordingMode) -> Unit,
    onSelectVolumeKeyDraftMode: (VolumeKeyDraftMode) -> Unit,
    onToggleMirrorRecordingNotification: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var isReorderingTts by rememberSaveable { mutableStateOf(false) }
    val tabStateHolder = rememberSaveableStateHolder()

    BackHandler {
        if (isReorderingTts) {
            isReorderingTts = false
        } else {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isReorderingTts) {
                                isReorderingTts = false
                            } else {
                                onNavigateBack()
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (snapshot.needsAttention && activeSection != SettingsSection.SYSTEM) {
                    SettingsInlineNotice(
                        text = if (!snapshot.allRuntimeGranted) {
                            "Required permissions need attention for screen-off and headset PTT."
                        } else {
                            "Battery exemption is recommended so Android keeps screen-off audio alive."
                        },
                        tone = if (!snapshot.allRuntimeGranted) SettingsNoticeTone.ERROR else SettingsNoticeTone.WARNING,
                        actionLabel = "Review",
                        onAction = {
                            isReorderingTts = false
                            onSelectSection(SettingsSection.SYSTEM)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsSection.entries.forEach { section ->
                        val selected = activeSection == section
                        val showAttentionDot = section == SettingsSection.SYSTEM && snapshot.needsAttention
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (section != SettingsSection.VOICE) {
                                    isReorderingTts = false
                                }
                                onSelectSection(section)
                            },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(section.title)
                                    if (showAttentionDot) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.error),
                                        )
                                    }
                                }
                            },
                            colors = tonalSelectedChipColors(),
                            border = tonalSelectedChipBorder(selected),
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            tabStateHolder.SaveableStateProvider(key = activeSection.name) {
                val sectionScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .verticalScroll(sectionScrollState)
                            .padding(horizontal = 16.dp)
                            .padding(top = 14.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        when (activeSection) {
                            SettingsSection.CONTROLS -> ControlsSettingsTab(
                                snapshot = snapshot,
                                controlScheme = controlScheme,
                                onApplyControlPreset = onApplyControlPreset,
                                onTogglePinBluetoothSco = onTogglePinBluetoothSco,
                                onSelectBluetoothCaptureMode = onSelectBluetoothCaptureMode,
                                onSelectTelecomHangupAction = onSelectTelecomHangupAction,
                                onSelectHeadsetTapMode = onSelectHeadsetTapMode,
                                onToggleAvrcpTrackControls = onToggleAvrcpTrackControls,
                                onSelectVolumeKeyRecordingMode = onSelectVolumeKeyRecordingMode,
                                onSelectVolumeKeyDraftMode = onSelectVolumeKeyDraftMode,
                                onToggleMirrorRecordingNotification = onToggleMirrorRecordingNotification,
                                onRequestPermissions = onRequestPermissions,
                            )

                            SettingsSection.VOICE -> VoiceSettingsTab(
                                servers = servers,
                                activeServer = activeServer,
                                isUsingCustomServerVoice = isUsingCustomServerVoice,
                                onToggleCustomServerVoice = onToggleCustomServerVoice,
                                ttsPipeline = ttsPipeline,
                                onUpdateTtsPipeline = onUpdateTtsPipeline,
                                isReorderingTts = isReorderingTts,
                                onToggleReorderingTts = { isReorderingTts = !isReorderingTts },
                                ttsInputMode = ttsInputMode,
                                onSelectTtsInputMode = onSelectTtsInputMode,
                                spokenDetailLevel = spokenDetailLevel,
                                onSelectSpokenDetailLevel = onSelectSpokenDetailLevel,
                                audioCueMode = audioCueMode,
                                onSelectAudioCueMode = onSelectAudioCueMode,
                                cuePackWarmState = cuePackWarmState,
                                roleVoiceProfiles = roleVoiceProfiles,
                                availableGeminiVoiceIds = availableGeminiVoiceIds,
                                availableMlKitVoiceNames = availableMlKitVoiceNames,
                                systemVoiceLabels = systemVoiceLabels,
                                ttsCacheStats = ttsCacheStats,
                                onClearTtsCache = onClearTtsCache,
                                selectedGeminiVoice = selectedGeminiVoice,
                                selectedOpenAiVoice = selectedOpenAiVoice,
                                selectedSystemVoiceLabel = selectedSystemVoiceLabel,
                                onOpenVoicePickerForRole = onOpenVoicePickerForRole,
                                sttEngine = sttEngine,
                                onSelectSttEngine = onSelectSttEngine,
                                sttFallbackToParakeet = sttFallbackToParakeet,
                                onToggleSttFallback = onToggleSttFallback,
                                quickTurnDirectAnswer = quickTurnDirectAnswer,
                                onToggleQuickTurnDirectAnswer = onToggleQuickTurnDirectAnswer,
                                quickTranscriptCleanup = quickTranscriptCleanup,
                                onToggleQuickTranscriptCleanup = onToggleQuickTranscriptCleanup,
                                mlKitAdvancedStatus = mlKitAdvancedStatus,
                                mlKitBasicStatus = mlKitBasicStatus,
                                mlKitDownloadStatus = mlKitDownloadStatus,
                                isDownloadingMlKit = isDownloadingMlKit,
                                mlKitTtsStatus = mlKitTtsStatus,
                                mlKitTtsVoicesCount = mlKitTtsVoicesCount,
                                selectedMlKitTtsVoice = selectedMlKitTtsVoice,
                                onDownloadMlKitModel = onDownloadMlKitModel,
                            )

                            SettingsSection.SERVERS -> ServersSettingsTab(
                                servers = servers,
                                activeServer = activeServer,
                                serverUrl = serverUrl,
                                onServerUrlChange = onServerUrlChange,
                                serverLabel = serverLabel,
                                onServerLabelChange = onServerLabelChange,
                                password = password,
                                onPasswordChange = onPasswordChange,
                                showPassword = showPassword,
                                onToggleShowPassword = onToggleShowPassword,
                                isConnected = isConnected,
                                isAuthenticating = isAuthenticating,
                                authError = authError,
                                startWithAddServerOpen = startWithAddServerOpen,
                                onSelectServer = onSelectServer,
                                onRemoveServer = onRemoveServer,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                            )

                            SettingsSection.SYSTEM -> SystemSettingsTab(
                                snapshot = snapshot,
                                onRequestPermissions = onRequestPermissions,
                                onRequestBatteryExemption = onRequestBatteryExemption,
                                onOpenBubbleSettings = onOpenBubbleSettings,
                                onOpenTelemetryLog = onOpenTelemetryLog,
                                onOpenAudioDebug = onOpenAudioDebug,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsSettingsTab(
    snapshot: PermissionStatusSnapshot,
    controlScheme: ControlSchemeConfig,
    onApplyControlPreset: (ControlPreset) -> Unit,
    onTogglePinBluetoothSco: (Boolean) -> Unit,
    onSelectBluetoothCaptureMode: (BluetoothCaptureMode) -> Unit,
    onSelectTelecomHangupAction: (TelecomHangupAction) -> Unit,
    onSelectHeadsetTapMode: (HeadsetTapMode) -> Unit,
    onToggleAvrcpTrackControls: (Boolean) -> Unit,
    onSelectVolumeKeyRecordingMode: (VolumeKeyRecordingMode) -> Unit,
    onSelectVolumeKeyDraftMode: (VolumeKeyDraftMode) -> Unit,
    onToggleMirrorRecordingNotification: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val activePreset = controlScheme.resolvedPreset

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(
            title = "Control Preset",
            subtitle = "Device-global headset and button defaults. Any control below can be tuned individually.",
            trailingBadge = if (activePreset == ControlPreset.CUSTOM) ControlPreset.CUSTOM.shortLabel else null,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(ControlPreset.STANDARD, ControlPreset.SHOKZ_SINGLE_TAP).forEach { preset ->
                val selected = activePreset == preset
                FilterChip(
                    selected = selected,
                    onClick = { onApplyControlPreset(preset) },
                    label = { Text(preset.shortLabel) },
                    colors = tonalSelectedChipColors(),
                    border = tonalSelectedChipBorder(selected),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        ActiveGestureMapCard(
            rows = ControlHintFormatter.formatGestureMapRows(controlScheme),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Capture Mic & Bluetooth")

        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Headset mic during recording",
                subtitle = "Off keeps stereo media audio and uses phone mic. On routes microphone via Bluetooth while recording.",
                checked = controlScheme.pinBluetoothSco,
                onCheckedChange = onTogglePinBluetoothSco,
            )

            SettingsGroupDivider()

            SettingsChoiceGroup(
                label = "Bluetooth capture mode",
                options = BluetoothCaptureMode.entries.map {
                    SettingsChoiceOption(it, it.shortLabel)
                },
                selectedValue = controlScheme.bluetoothCaptureMode,
                onSelect = onSelectBluetoothCaptureMode,
                helperText = if (!controlScheme.pinBluetoothSco) {
                    "${controlScheme.bluetoothCaptureMode.description} Applies whenever Headset mic is enabled."
                } else {
                    controlScheme.bluetoothCaptureMode.description
                },
            )

            if (controlScheme.isTelecomCallEnabled) {
                SettingsGroupDivider()

                SettingsChoiceGroup(
                    label = "When headset hangs up call",
                    options = TelecomHangupAction.entries.map {
                        SettingsChoiceOption(it, it.shortLabel)
                    },
                    selectedValue = controlScheme.telecomHangupAction,
                    onSelect = onSelectTelecomHangupAction,
                    helperText = controlScheme.telecomHangupAction.description,
                )
            }
        }

        if (controlScheme.isTelecomCallEnabled && !controlScheme.pinBluetoothSco) {
            SettingsInlineNotice(
                text = "Headset mic is currently off. Enable it to route recording through Call mode (HFP).",
                tone = SettingsNoticeTone.INFO,
                actionLabel = "Turn on",
                onAction = { onTogglePinBluetoothSco(true) },
            )
        }

        if (controlScheme.isTelecomCallEnabled && !snapshot.hasBluetoothConnect) {
            SettingsInlineNotice(
                text = "Bluetooth permission is recommended on Android 12+ to route calls to your headset.",
                tone = SettingsNoticeTone.WARNING,
                actionLabel = "Grant",
                onAction = onRequestPermissions,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Headset Media Button")

        SettingsGroupCard {
            SettingsChoiceGroup(
                label = "Tap cadence",
                options = HeadsetTapMode.entries.map {
                    SettingsChoiceOption(it, it.shortLabel)
                },
                selectedValue = controlScheme.headsetTapMode,
                onSelect = onSelectHeadsetTapMode,
                helperText = controlScheme.headsetTapMode.description,
            )

            SettingsGroupDivider()

            SettingsSwitchRow(
                title = "AVRCP Next / Previous shortcuts",
                subtitle = "Next triggers 2x action and Previous triggers 3x action. Turn off if headset long-presses skip or discard accidentally.",
                checked = controlScheme.avrcpTrackControlsEnabled,
                onCheckedChange = onToggleAvrcpTrackControls,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(
            title = "Phone Volume Keys (Screen Off)",
            subtitle = "Intercepted only while the screen is off; screen-on presses always control normal volume.",
        )

        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Stop recording with volume keys",
                subtitle = "Android 15+ and standard AOSP (Pixel 12–14 ignores remote session volume); restores media volume immediately.",
                checked = controlScheme.volumeKeyRecordingMode.isEnabled,
                onCheckedChange = { enabled ->
                    onSelectVolumeKeyRecordingMode(
                        if (enabled) {
                            if (controlScheme.volumeKeyRecordingMode.isEnabled) {
                                controlScheme.volumeKeyRecordingMode
                            } else {
                                VolumeKeyRecordingMode.ANY_SEND
                            }
                        } else {
                            VolumeKeyRecordingMode.OFF
                        },
                    )
                },
            )

            if (controlScheme.volumeKeyRecordingMode.isEnabled) {
                SettingsGroupDivider()

                SettingsChoiceGroup(
                    label = "Recording volume action",
                    options = listOf(
                        VolumeKeyRecordingMode.ANY_SEND,
                        VolumeKeyRecordingMode.ANY_STAGE,
                        VolumeKeyRecordingMode.SPLIT_SEND_STAGE,
                    ).map { SettingsChoiceOption(it, it.shortLabel) },
                    selectedValue = controlScheme.volumeKeyRecordingMode,
                    onSelect = onSelectVolumeKeyRecordingMode,
                    helperText = controlScheme.volumeKeyRecordingMode.description,
                )
            }

            SettingsGroupDivider()

            SettingsSwitchRow(
                title = "Control staged draft with volume keys",
                subtitle = "Active screen-off during staged draft and draft preview playback instead of loudness adjustment.",
                checked = controlScheme.volumeKeyDraftMode.isEnabled,
                onCheckedChange = { enabled ->
                    onSelectVolumeKeyDraftMode(
                        if (enabled) {
                            if (controlScheme.volumeKeyDraftMode.isEnabled) {
                                controlScheme.volumeKeyDraftMode
                            } else {
                                VolumeKeyDraftMode.SEND_AND_DISCARD
                            }
                        } else {
                            VolumeKeyDraftMode.OFF
                        },
                    )
                },
            )

            if (controlScheme.volumeKeyDraftMode.isEnabled) {
                SettingsGroupDivider()

                SettingsChoiceGroup(
                    label = "Staged draft volume action",
                    options = listOf(
                        VolumeKeyDraftMode.SEND_AND_DISCARD,
                        VolumeKeyDraftMode.SEND_ONLY,
                    ).map { SettingsChoiceOption(it, it.shortLabel) },
                    selectedValue = controlScheme.volumeKeyDraftMode,
                    onSelect = onSelectVolumeKeyDraftMode,
                    helperText = controlScheme.volumeKeyDraftMode.description,
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Smartwatch")

        SettingsGroupCard {
            SettingsSwitchRow(
                title = "Mirror recording control to watch",
                subtitle = "Posts a silent notification with 'Stop recording' during capture for Garmin and Wear OS.",
                checked = controlScheme.mirrorRecordingNotification,
                onCheckedChange = onToggleMirrorRecordingNotification,
            )
        }

        if (controlScheme.mirrorRecordingNotification && !snapshot.hasPostNotifications) {
            SettingsInlineNotice(
                text = "Notifications permission is required on Android 13+ for smartwatch apps to see recording controls.",
                tone = SettingsNoticeTone.ERROR,
                actionLabel = "Grant",
                onAction = onRequestPermissions,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceSettingsTab(
    servers: List<ServerProfile>,
    activeServer: ServerProfile?,
    isUsingCustomServerVoice: Boolean,
    onToggleCustomServerVoice: (Boolean) -> Unit,
    ttsPipeline: List<TtsEngineOption>,
    onUpdateTtsPipeline: (List<TtsEngineOption>) -> Unit,
    isReorderingTts: Boolean,
    onToggleReorderingTts: () -> Unit,
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
    onOpenVoicePickerForRole: (VoiceRole) -> Unit,
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
) {
    if (servers.size > 1 && activeServer != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSectionHeading(
                title = "Voice & Speech Scope",
                subtitle = if (isUsingCustomServerVoice) {
                    "STT, reply readback, cues, and cloud role voices apply only to ${activeServer.displayLabel}. Hardware controls stay device-global."
                } else {
                    "Using shared global voice settings across all servers. Switch to \"${activeServer.displayLabel} only\" to customize this host."
                },
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
                    colors = tonalSelectedChipColors(),
                    border = tonalSelectedChipBorder(!isUsingCustomServerVoice),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                FilterChip(
                    selected = isUsingCustomServerVoice,
                    onClick = { onToggleCustomServerVoice(true) },
                    label = { Text("${activeServer.displayLabel} only") },
                    colors = tonalSelectedChipColors(),
                    border = tonalSelectedChipBorder(isUsingCustomServerVoice),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(
            title = "Voice Roles",
            subtitle = "Tap a role to choose its provider and voice directly.",
        )

        SettingsGroupCard {
            VoiceRole.entries.forEachIndexed { index, role ->
                if (index > 0) {
                    SettingsGroupDivider()
                }
                val roleSummary = VoiceRoleResolver.formatRoleSummaryLabel(
                    role = role,
                    basePipeline = ttsPipeline,
                    profiles = roleVoiceProfiles,
                    availableGeminiVoices = availableGeminiVoiceIds,
                    availableMlKitVoices = availableMlKitVoiceNames,
                    availableSystemVoiceLabels = systemVoiceLabels,
                )
                val routingNote = if (roleVoiceProfiles[role].preferredEngine == null) {
                    "$roleSummary · Follows TTS Order"
                } else {
                    roleSummary
                }
                SettingsChevronRow(
                    title = role.title,
                    subtitle = routingNote,
                    onClick = { onOpenVoicePickerForRole(role) },
                )
            }
        }
    }

    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsSectionHeading(title = "Dictation & Input")

        SettingsGroupCard {
            SttEngine.entries.forEachIndexed { index, engine ->
                if (index > 0) {
                    SettingsGroupDivider()
                }
                val report = when (engine) {
                    SttEngine.OPENCHAMBER_PARAKEET -> null
                    SttEngine.MLKIT_GENAI_ADVANCED -> mlKitAdvancedStatus
                    SttEngine.MLKIT_GENAI_BASIC -> mlKitBasicStatus
                }
                val statusSuffix = when (engine) {
                    SttEngine.OPENCHAMBER_PARAKEET -> "Host"
                    else -> formatMlKitBadge(report?.availability)
                }
                val selected = sttEngine == engine
                val rowBg = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        .compositeOver(MaterialTheme.colorScheme.surfaceContainer)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelectSttEngine(engine) },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${engine.title} ($statusSuffix)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        Text(
                            text = report?.detail ?: engine.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (sttEngine != SttEngine.OPENCHAMBER_PARAKEET) {
                SettingsGroupDivider()
                SettingsSwitchRow(
                    title = "Fallback to Parakeet",
                    subtitle = "Replay audio to host Parakeet if ML Kit fails",
                    checked = sttFallbackToParakeet,
                    onCheckedChange = onToggleSttFallback,
                )
            }

            SettingsGroupDivider()

            SettingsSwitchRow(
                title = "Quick-mode transcript cleanup",
                subtitle = "Remove filler pauses and stutters with the small model in quick-send mode (off sends verbatim).",
                checked = quickTranscriptCleanup,
                onCheckedChange = onToggleQuickTranscriptCleanup,
            )
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
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Reply Readback & Routing")

        SettingsGroupCard {
            SettingsChoiceGroup(
                label = "Spoken formatting",
                options = TtsInputMode.entries.map {
                    SettingsChoiceOption(it, it.title)
                },
                selectedValue = ttsInputMode,
                onSelect = onSelectTtsInputMode,
                helperText = ttsInputMode.subtitle,
            )

            SettingsGroupDivider()

            SettingsSwitchRow(
                title = "Local quick-turn answers",
                subtitle = "Answer quick follow-ups about the latest reply locally without prompting the session (live tuning commands stay active).",
                checked = quickTurnDirectAnswer,
                onCheckedChange = onToggleQuickTurnDirectAnswer,
            )
        }

        if (ttsInputMode == TtsInputMode.SUMMARIZED) {
            SpokenDetailSlider(
                detailLevel = spokenDetailLevel,
                onSelectLevel = onSelectSpokenDetailLevel,
                subtitle = spokenDetailLevel.subtitle,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Audio Cues")

        SettingsGroupCard {
            SettingsChoiceGroup(
                label = "Audio cue style",
                options = AudioCueMode.entries.map {
                    SettingsChoiceOption(it, it.title)
                },
                selectedValue = audioCueMode,
                onSelect = onSelectAudioCueMode,
                helperText = audioCueMode.subtitle,
            )

            if (audioCueMode != AudioCueMode.TONES) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionHeading(
                title = "Text-to-Speech Fallback Order",
                subtitle = if (isReorderingTts) {
                    "Move fallback engines up or down."
                } else {
                    "Global fallback chain tried top-to-bottom when a role follows TTS Order or fails."
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onToggleReorderingTts,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (isReorderingTts) "Done" else "Reorder")
            }
        }

        SettingsGroupCard {
            ttsPipeline.forEachIndexed { index, option ->
                if (index > 0) {
                    SettingsGroupDivider()
                }
                val engineSubtitle = when (option.engine) {
                    TtsEngine.GEMINI_BRIDGE -> "Default: $selectedGeminiVoice · Cloud via :4005"
                    TtsEngine.LOCAL_KOKORO -> "Host default speaker · /api/dictation/tts/speak"
                    TtsEngine.MLKIT_GENAI -> {
                        val badge = formatMlKitBadge(mlKitTtsStatus?.availability)
                        val voicePart = selectedMlKitTtsVoice ?: "Auto"
                        val diag = mlKitTtsStatus?.detail?.takeIf {
                            mlKitTtsStatus.availability == MlKitAvailability.UNAVAILABLE
                        }?.let { "\n$it" }.orEmpty()
                        "Default: $voicePart · $badge$diag"
                    }
                    TtsEngine.ANDROID_SYSTEM -> "Default: ${selectedSystemVoiceLabel ?: "System default"}"
                    TtsEngine.OPENAI_CLOUD -> "Default: $selectedOpenAiVoice · Direct /api/tts/speak"
                }
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

            SettingsGroupDivider()

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

        val hasUnavailableMlKit = listOf(
            mlKitAdvancedStatus?.availability,
            mlKitBasicStatus?.availability,
            mlKitTtsStatus?.availability,
        ).any { it == MlKitAvailability.UNAVAILABLE }
        if (hasUnavailableMlKit) {
            val screenContext = LocalContext.current
            @Suppress("DEPRECATION")
            val clipboardManager = LocalClipboardManager.current
            OutlinedButton(
                onClick = {
                    val report = buildString {
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}, ${Build.TAGS})")
                        appendLine("Packages: ${MlKitSttClient.buildPackageDiagnostic(screenContext)}")
                        appendLine("STT Adv: ${mlKitAdvancedStatus?.availability} | ${mlKitAdvancedStatus?.detail ?: "ok"}")
                        appendLine("STT Basic: ${mlKitBasicStatus?.availability} | ${mlKitBasicStatus?.detail ?: "ok"}")
                        append("TTS GenAI: ${mlKitTtsStatus?.availability} | ${mlKitTtsStatus?.detail ?: "ok"} (voices=$mlKitTtsVoicesCount)")
                    }
                    clipboardManager.setText(AnnotatedString(report))
                    Toast.makeText(screenContext, "Copied ML Kit diagnostics", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Copy ML Kit diagnostics")
            }
        }
    }
}

@Composable
private fun ServersSettingsTab(
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
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var showServerForm by rememberSaveable(startWithAddServerOpen, servers.size) {
        mutableStateOf(startWithAddServerOpen || servers.isEmpty() || activeServer?.isSignedIn != true)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSectionHeading(
                title = "Saved Servers",
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
            SettingsSelectableRowCard(
                selected = isActive,
                onClick = {
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
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemSettingsTab(
    snapshot: PermissionStatusSnapshot,
    onRequestPermissions: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onOpenBubbleSettings: () -> Unit,
    onOpenTelemetryLog: () -> Unit,
    onOpenAudioDebug: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Permissions & Multitasking")

        SettingsGroupCard {
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionHeading(title = "Diagnostics")

        SettingsGroupCard {
            SettingsChevronRow(
                title = "Voice UX Telemetry Log",
                subtitle = "Capture latencies, tap counts, and local JSONL export.",
                onClick = onOpenTelemetryLog,
            )
            SettingsGroupDivider()
            SettingsChevronRow(
                title = "Live Audio & BT Debug",
                subtitle = "Real-time SCO, Telecom, media-button, and volume-key monitor.",
                onClick = onOpenAudioDebug,
            )
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
