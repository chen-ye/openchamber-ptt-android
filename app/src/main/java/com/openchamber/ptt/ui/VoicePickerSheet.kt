package com.openchamber.ptt.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.R
import com.openchamber.ptt.tts.MlKitTtsVoiceSummary
import com.openchamber.ptt.tts.RoleVoiceProfileMap
import com.openchamber.ptt.tts.SystemTtsVoiceSummary
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsVoice
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.tts.VoiceRole
import com.openchamber.ptt.tts.VoiceRoleResolver
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VoicePickerSheet(
    ttsPipeline: List<TtsEngineOption>,
    roleProfiles: RoleVoiceProfileMap,
    initialEngine: TtsEngine,
    initialRole: VoiceRole = VoiceRole.AGENT,
    geminiVoices: List<TtsVoice>,
    isLoadingGemini: Boolean,
    geminiError: String?,
    onRefreshGemini: () -> Unit,
    onSelectGeminiVoice: (VoiceRole, String?) -> Unit,
    mlKitStatus: MlKitStatusReport?,
    mlKitVoices: List<MlKitTtsVoiceSummary>,
    mlKitDownloadStatus: String?,
    isDownloadingMlKit: Boolean,
    onRefreshMlKit: () -> Unit,
    onSelectMlKitVoice: (VoiceRole, String?) -> Unit,
    onDownloadMlKitVoice: (String?) -> Unit,
    onSelectOpenAiVoice: (VoiceRole, String?) -> Unit,
    systemVoices: List<SystemTtsVoiceSummary>,
    isLoadingSystemVoices: Boolean,
    onRefreshSystemVoices: () -> Unit,
    onSelectSystemVoice: (VoiceRole, String?) -> Unit,
    onSetRolePreferredEngine: (VoiceRole, TtsEngine?) -> Unit,
    onPreviewVoice: (VoiceRole, TtsEngine, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var activeRole by rememberSaveable { mutableStateOf(initialRole) }
    var activeTab by rememberSaveable { mutableStateOf(initialEngine) }
    var geminiQuery by rememberSaveable { mutableStateOf("") }
    var openAiQuery by rememberSaveable { mutableStateOf("") }
    var systemQuery by rememberSaveable { mutableStateOf("") }
    var appliedInitial by rememberSaveable(initialRole, initialEngine) { mutableStateOf(false) }

    LaunchedEffect(initialRole, initialEngine) {
        if (!appliedInitial) {
            activeRole = initialRole
            activeTab = initialEngine
            appliedInitial = true
        }
    }

    val primaryEngine = remember(ttsPipeline) {
        VoicePipelineDefaults.primaryEnabledEngine(ttsPipeline)
    }
    val orderedEngines = remember(ttsPipeline) {
        ttsPipeline.map { it.engine }
    }
    val activeOption = remember(ttsPipeline, activeTab) {
        ttsPipeline.firstOrNull { it.engine == activeTab }
    }
    val activeAssignment = roleProfiles[activeRole]
    val isPinnedEngine = activeAssignment.preferredEngine == activeTab

    val geminiVoiceIds = remember(geminiVoices) { geminiVoices.map { it.id } }
    val mlKitVoiceNames = remember(mlKitVoices) {
        mlKitVoices
            .filter { it.availability == MlKitAvailability.AVAILABLE }
            .map { it.name }
            .ifEmpty { mlKitVoices.map { it.name } }
    }
    val systemVoiceLabels = remember(systemVoices) {
        systemVoices.associate { it.name to it.label }
    }
    val systemVoiceNames = remember(systemVoices) { systemVoices.map { it.name } }

    val activeRoleSummary = remember(
        activeRole,
        ttsPipeline,
        roleProfiles,
        geminiVoiceIds,
        mlKitVoiceNames,
        systemVoiceLabels,
    ) {
        VoiceRoleResolver.formatRoleSummaryLabel(
            role = activeRole,
            basePipeline = ttsPipeline,
            profiles = roleProfiles,
            availableGeminiVoices = geminiVoiceIds,
            availableMlKitVoices = mlKitVoiceNames,
            availableSystemVoiceLabels = systemVoiceLabels,
        )
    }

    val autoResolved = remember(
        roleProfiles,
        activeRole,
        activeTab,
        geminiVoiceIds,
        mlKitVoiceNames,
        systemVoiceNames,
    ) {
        val cleared = when (activeTab) {
            TtsEngine.GEMINI_BRIDGE -> roleProfiles[activeRole].copy(geminiVoice = null)
            TtsEngine.OPENAI_CLOUD -> roleProfiles[activeRole].copy(openAiVoice = null)
            TtsEngine.MLKIT_GENAI -> roleProfiles[activeRole].copy(mlKitVoice = null)
            TtsEngine.ANDROID_SYSTEM -> roleProfiles[activeRole].copy(systemVoice = null)
            TtsEngine.LOCAL_KOKORO -> roleProfiles[activeRole].copy(kokoroSpeakerId = null)
        }
        VoiceRoleResolver.resolveVoiceForEngine(
            engine = activeTab,
            role = activeRole,
            profiles = roleProfiles.withRole(activeRole, cleared),
            availableGeminiVoices = geminiVoiceIds,
            availableMlKitVoices = mlKitVoiceNames,
            availableSystemVoices = systemVoiceNames,
        )
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
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                        text = "${activeRole.title}: $activeRoleSummary",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VoiceRole.entries.forEach { role ->
                        val isRoleSelected = activeRole == role
                        FilterChip(
                            selected = isRoleSelected,
                            onClick = {
                                activeRole = role
                                roleProfiles[role].preferredEngine?.let { pinned ->
                                    activeTab = pinned
                                }
                            },
                            label = { Text(role.shortTitle) },
                            leadingIcon = if (isRoleSelected) {
                                {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Text(
                    text = activeRole.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                orderedEngines.forEach { engine ->
                    val enabled = ttsPipeline.firstOrNull { it.engine == engine }?.enabled == true
                    val pinnedForRole = activeAssignment.preferredEngine == engine
                    val labelText = if (enabled || pinnedForRole) {
                        engine.shortTitle
                    } else {
                        "${engine.shortTitle} (off)"
                    }
                    FilterChip(
                        selected = activeTab == engine,
                        onClick = { activeTab = engine },
                        label = { Text(labelText) },
                        modifier = Modifier.heightIn(min = 48.dp),
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
                        value = isPinnedEngine,
                        role = Role.Switch,
                        onValueChange = { checked ->
                            onSetRolePreferredEngine(activeRole, if (checked) activeTab else null)
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Pin ${activeTab.shortTitle} for ${activeRole.shortTitle}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = if (isPinnedEngine) {
                                "Tried first for ${activeRole.title}, then falls back to TTS Order"
                            } else {
                                "Follows global TTS Order (${primaryEngine?.shortTitle ?: "None"})"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isPinnedEngine,
                        onCheckedChange = null,
                    )
                }
            }

            if (activeOption != null && !activeOption.enabled && !isPinnedEngine) {
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
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (activeTab) {
                    TtsEngine.GEMINI_BRIDGE -> {
                        val explicitGemini = activeAssignment.geminiVoice?.trim()?.takeIf { it.isNotEmpty() }
                        val effectiveAgentGemini = explicitGemini ?: TtsClient.DEFAULT_TTS_VOICE
                        val autoGeminiLabel = autoResolved.voiceId?.takeIf { it.isNotBlank() }
                            ?: TtsClient.DEFAULT_TTS_VOICE
                        val showAutoRow = activeRole != VoiceRole.AGENT &&
                            (geminiQuery.isBlank() ||
                                "auto $autoGeminiLabel".lowercase().contains(geminiQuery.trim().lowercase()))

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

                        if (!showAutoRow && filteredGemini.isEmpty() && !isLoadingGemini) {
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
                                    TextButton(
                                        onClick = onRefreshGemini,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    ) {
                                        Text("Reload voices")
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .heightIn(max = 320.dp)
                                    .selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (showAutoRow) {
                                    item(key = "__auto_gemini__") {
                                        VoiceOptionRow(
                                            title = "Auto ($autoGeminiLabel)",
                                            subtitle = "Stays distinct from Main Agent automatically",
                                            selected = explicitGemini == null,
                                            onClick = { onSelectGeminiVoice(activeRole, null) },
                                            onPreview = {
                                                onPreviewVoice(activeRole, TtsEngine.GEMINI_BRIDGE, autoGeminiLabel)
                                            },
                                        )
                                    }
                                }
                                if (filteredGemini.isEmpty() && !isLoadingGemini) {
                                    item(key = "__empty_gemini__") {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Text(
                                                text = if (geminiVoices.isEmpty()) "No Gemini voices loaded" else "No matching voices",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (geminiVoices.isEmpty()) {
                                                TextButton(
                                                    onClick = onRefreshGemini,
                                                    modifier = Modifier.heightIn(min = 48.dp),
                                                ) {
                                                    Text("Reload voices")
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    items(filteredGemini, key = { it.id }) { voice ->
                                        val selected = if (activeRole == VoiceRole.AGENT) {
                                            effectiveAgentGemini.equals(voice.id, ignoreCase = true)
                                        } else {
                                            explicitGemini != null && explicitGemini.equals(voice.id, ignoreCase = true)
                                        }
                                        val meta = listOfNotNull(
                                            voice.style.takeIf { it.isNotBlank() },
                                            voice.gender.takeIf { it.isNotBlank() },
                                            voice.accent.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ")
                                        VoiceOptionRow(
                                            title = voice.label,
                                            subtitle = meta.ifBlank { null },
                                            selected = selected,
                                            onClick = { onSelectGeminiVoice(activeRole, voice.id) },
                                            onPreview = {
                                                onPreviewVoice(activeRole, TtsEngine.GEMINI_BRIDGE, voice.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    TtsEngine.MLKIT_GENAI -> {
                        val explicitMlKit = activeAssignment.mlKitVoice?.trim()?.takeIf { it.isNotEmpty() }
                        val autoMlKitLabel = autoResolved.voiceId?.takeIf { it.isNotBlank() } ?: "Default"
                        val isAutoMlKitReady = autoResolved.voiceId != null && mlKitVoices.any {
                            it.name.equals(autoResolved.voiceId, ignoreCase = true) &&
                                it.availability == MlKitAvailability.AVAILABLE
                        }

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
                                it.name.equals(explicitMlKit ?: autoResolved.voiceId, ignoreCase = true)
                            } ?: mlKitVoices.firstOrNull()

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .heightIn(max = 280.dp)
                                    .selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (activeRole != VoiceRole.AGENT) {
                                    item(key = "__auto_mlkit__") {
                                        VoiceOptionRow(
                                            title = "Auto ($autoMlKitLabel)",
                                            subtitle = "Stays distinct from Main Agent automatically",
                                            selected = explicitMlKit == null,
                                            onClick = { onSelectMlKitVoice(activeRole, null) },
                                            onPreview = if (isAutoMlKitReady) {
                                                { onPreviewVoice(activeRole, TtsEngine.MLKIT_GENAI, autoResolved.voiceId) }
                                            } else {
                                                null
                                            },
                                        )
                                    }
                                }
                                items(mlKitVoices, key = { it.name }) { voice ->
                                    val selected = if (activeRole == VoiceRole.AGENT) {
                                        explicitMlKit.equals(voice.name, ignoreCase = true)
                                    } else {
                                        explicitMlKit != null && explicitMlKit.equals(voice.name, ignoreCase = true)
                                    }
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
                                        onClick = { onSelectMlKitVoice(activeRole, voice.name) },
                                        onPreview = if (voice.availability == MlKitAvailability.AVAILABLE) {
                                            { onPreviewVoice(activeRole, TtsEngine.MLKIT_GENAI, voice.name) }
                                        } else {
                                            null
                                        },
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
                        val explicitOpenAi = TtsClient.normalizeOpenAiVoiceOrNull(activeAssignment.openAiVoice)
                        val effectiveAgentOpenAi = explicitOpenAi ?: VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
                        val autoOpenAiId = autoResolved.voiceId?.takeIf { it.isNotBlank() }
                            ?: VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
                        val autoOpenAiLabel = VoicePipelineDefaults.openAiVoices
                            .firstOrNull { it.id.equals(autoOpenAiId, ignoreCase = true) }
                            ?.label
                            ?: autoOpenAiId.replaceFirstChar { it.uppercaseChar() }
                        val showAutoRow = activeRole != VoiceRole.AGENT &&
                            (openAiQuery.isBlank() ||
                                "auto $autoOpenAiLabel $autoOpenAiId".lowercase().contains(openAiQuery.trim().lowercase()))

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
                                .weight(1f, fill = false)
                                .heightIn(max = 320.dp)
                                .selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (showAutoRow) {
                                item(key = "__auto_openai__") {
                                    VoiceOptionRow(
                                        title = "Auto ($autoOpenAiLabel)",
                                        subtitle = "Stays distinct from Main Agent automatically",
                                        selected = explicitOpenAi == null,
                                        onClick = { onSelectOpenAiVoice(activeRole, null) },
                                        onPreview = {
                                            onPreviewVoice(activeRole, TtsEngine.OPENAI_CLOUD, autoOpenAiId)
                                        },
                                    )
                                }
                            }
                            items(filteredOpenAi, key = { it.id }) { preset ->
                                val selected = if (activeRole == VoiceRole.AGENT) {
                                    effectiveAgentOpenAi.equals(preset.id, ignoreCase = true)
                                } else {
                                    explicitOpenAi != null && explicitOpenAi.equals(preset.id, ignoreCase = true)
                                }
                                VoiceOptionRow(
                                    title = preset.label,
                                    subtitle = preset.description,
                                    selected = selected,
                                    onClick = { onSelectOpenAiVoice(activeRole, preset.id) },
                                    onPreview = {
                                        onPreviewVoice(activeRole, TtsEngine.OPENAI_CLOUD, preset.id)
                                    },
                                )
                            }
                        }
                    }

                    TtsEngine.ANDROID_SYSTEM -> {
                        val explicitSystem = activeAssignment.systemVoice?.trim()?.takeIf { it.isNotEmpty() }
                        val autoSystemLabel = autoResolved.voiceId?.let { id ->
                            systemVoiceLabels[id] ?: id.substringAfterLast('-').ifBlank { id }
                        } ?: "System default"
                        val showAutoRow = systemQuery.isBlank() ||
                            "auto $autoSystemLabel system default".lowercase().contains(systemQuery.trim().lowercase())

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
                                .weight(1f, fill = false)
                                .heightIn(max = 280.dp)
                                .selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (showAutoRow) {
                                item(key = "__auto_system__") {
                                    VoiceOptionRow(
                                        title = "Auto ($autoSystemLabel)",
                                        subtitle = if (activeRole == VoiceRole.AGENT) {
                                            "Follow Android Text-to-Speech preferred voice"
                                        } else {
                                            "Stays distinct from Main Agent automatically"
                                        },
                                        selected = explicitSystem == null,
                                        onClick = { onSelectSystemVoice(activeRole, null) },
                                        onPreview = {
                                            onPreviewVoice(activeRole, TtsEngine.ANDROID_SYSTEM, autoResolved.voiceId)
                                        },
                                    )
                                }
                            }
                            items(filteredSystem, key = { it.name }) { voice ->
                                val selected = explicitSystem == voice.name
                                val modeTag = if (voice.isNetworkRequired) "Network" else "Offline"
                                VoiceOptionRow(
                                    title = voice.label,
                                    subtitle = "${voice.name} · $modeTag",
                                    selected = selected,
                                    onClick = { onSelectSystemVoice(activeRole, voice.name) },
                                    onPreview = {
                                        onPreviewVoice(activeRole, TtsEngine.ANDROID_SYSTEM, voice.name)
                                    },
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
                                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                FilledTonalButton(
                                    onClick = {
                                        onPreviewVoice(activeRole, TtsEngine.LOCAL_KOKORO, null)
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_volume_up),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Preview host voice")
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
internal fun VoiceOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
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
            .clip(RoundedCornerShape(12.dp))
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                    .padding(
                        start = 14.dp,
                        top = 10.dp,
                        bottom = 10.dp,
                        end = if (onPreview != null) 8.dp else 14.dp,
                    ),
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
                        maxLines = 2,
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
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (onPreview != null) {
                IconButton(
                    onClick = onPreview,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume_up),
                        contentDescription = "Preview $title",
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
