package com.openchamber.ptt.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.compositeOver
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePickerScreen(
    ttsPipeline: List<TtsEngineOption>,
    roleProfiles: RoleVoiceProfileMap,
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
    onOpenTtsSettings: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
) {
    var activeRole by rememberSaveable(initialRole) { mutableStateOf(initialRole) }
    val primaryEngine = remember(ttsPipeline) {
        VoicePipelineDefaults.primaryEnabledEngine(ttsPipeline)
    }
    val activeEngine = roleProfiles[activeRole].preferredEngine ?: primaryEngine ?: TtsEngine.GEMINI_BRIDGE

    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Voices",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    VoiceRefreshIconButton(
                        activeEngine = activeEngine,
                        isLoadingGemini = isLoadingGemini,
                        isLoadingSystemVoices = isLoadingSystemVoices,
                        onRefreshGemini = onRefreshGemini,
                        onRefreshMlKit = onRefreshMlKit,
                        onRefreshSystemVoices = onRefreshSystemVoices,
                    )
                },
            )
        },
    ) { innerPadding ->
        VoicePickerContent(
            activeRole = activeRole,
            onActiveRoleChange = { activeRole = it },
            ttsPipeline = ttsPipeline,
            roleProfiles = roleProfiles,
            geminiVoices = geminiVoices,
            isLoadingGemini = isLoadingGemini,
            geminiError = geminiError,
            onRefreshGemini = onRefreshGemini,
            onSelectGeminiVoice = onSelectGeminiVoice,
            mlKitStatus = mlKitStatus,
            mlKitVoices = mlKitVoices,
            mlKitDownloadStatus = mlKitDownloadStatus,
            isDownloadingMlKit = isDownloadingMlKit,
            onSelectMlKitVoice = onSelectMlKitVoice,
            onDownloadMlKitVoice = onDownloadMlKitVoice,
            onSelectOpenAiVoice = onSelectOpenAiVoice,
            systemVoices = systemVoices,
            onSelectSystemVoice = onSelectSystemVoice,
            onSetRolePreferredEngine = onSetRolePreferredEngine,
            onPreviewVoice = onPreviewVoice,
            onOpenTtsSettings = onOpenTtsSettings,
            expandListToFillHeight = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoicePickerSheet(
    ttsPipeline: List<TtsEngineOption>,
    roleProfiles: RoleVoiceProfileMap,
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeRole by rememberSaveable(initialRole) { mutableStateOf(initialRole) }
    val primaryEngine = remember(ttsPipeline) {
        VoicePipelineDefaults.primaryEnabledEngine(ttsPipeline)
    }
    val activeEngine = roleProfiles[activeRole].preferredEngine ?: primaryEngine ?: TtsEngine.GEMINI_BRIDGE

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Voices",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                VoiceRefreshIconButton(
                    activeEngine = activeEngine,
                    isLoadingGemini = isLoadingGemini,
                    isLoadingSystemVoices = isLoadingSystemVoices,
                    onRefreshGemini = onRefreshGemini,
                    onRefreshMlKit = onRefreshMlKit,
                    onRefreshSystemVoices = onRefreshSystemVoices,
                )
            }

            VoicePickerContent(
                activeRole = activeRole,
                onActiveRoleChange = { activeRole = it },
                ttsPipeline = ttsPipeline,
                roleProfiles = roleProfiles,
                geminiVoices = geminiVoices,
                isLoadingGemini = isLoadingGemini,
                geminiError = geminiError,
                onRefreshGemini = onRefreshGemini,
                onSelectGeminiVoice = onSelectGeminiVoice,
                mlKitStatus = mlKitStatus,
                mlKitVoices = mlKitVoices,
                mlKitDownloadStatus = mlKitDownloadStatus,
                isDownloadingMlKit = isDownloadingMlKit,
                onSelectMlKitVoice = onSelectMlKitVoice,
                onDownloadMlKitVoice = onDownloadMlKitVoice,
                onSelectOpenAiVoice = onSelectOpenAiVoice,
                systemVoices = systemVoices,
                onSelectSystemVoice = onSelectSystemVoice,
                onSetRolePreferredEngine = onSetRolePreferredEngine,
                onPreviewVoice = onPreviewVoice,
                onOpenTtsSettings = {
                    onDismiss()
                    onOpenSettings()
                },
                expandListToFillHeight = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun VoiceRefreshIconButton(
    activeEngine: TtsEngine,
    isLoadingGemini: Boolean,
    isLoadingSystemVoices: Boolean,
    onRefreshGemini: () -> Unit,
    onRefreshMlKit: () -> Unit,
    onRefreshSystemVoices: () -> Unit,
) {
    when (activeEngine) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoicePickerContent(
    activeRole: VoiceRole,
    onActiveRoleChange: (VoiceRole) -> Unit,
    ttsPipeline: List<TtsEngineOption>,
    roleProfiles: RoleVoiceProfileMap,
    geminiVoices: List<TtsVoice>,
    isLoadingGemini: Boolean,
    geminiError: String?,
    onRefreshGemini: () -> Unit,
    onSelectGeminiVoice: (VoiceRole, String?) -> Unit,
    mlKitStatus: MlKitStatusReport?,
    mlKitVoices: List<MlKitTtsVoiceSummary>,
    mlKitDownloadStatus: String?,
    isDownloadingMlKit: Boolean,
    onSelectMlKitVoice: (VoiceRole, String?) -> Unit,
    onDownloadMlKitVoice: (String?) -> Unit,
    onSelectOpenAiVoice: (VoiceRole, String?) -> Unit,
    systemVoices: List<SystemTtsVoiceSummary>,
    onSelectSystemVoice: (VoiceRole, String?) -> Unit,
    onSetRolePreferredEngine: (VoiceRole, TtsEngine?) -> Unit,
    onPreviewVoice: (VoiceRole, TtsEngine, String?) -> Unit,
    onOpenTtsSettings: (() -> Unit)? = null,
    expandListToFillHeight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var geminiQuery by rememberSaveable { mutableStateOf("") }
    var openAiQuery by rememberSaveable { mutableStateOf("") }
    var systemQuery by rememberSaveable { mutableStateOf("") }

    val primaryEngine = remember(ttsPipeline) {
        VoicePipelineDefaults.primaryEnabledEngine(ttsPipeline)
    }
    val orderedEngines = remember(ttsPipeline) {
        ttsPipeline.map { it.engine }
    }
    val activeAssignment = roleProfiles[activeRole]
    val assignedProvider = activeAssignment.preferredEngine
    val activeEngine = assignedProvider ?: primaryEngine ?: TtsEngine.GEMINI_BRIDGE
    val isFollowingTtsOrderWithNoEnabledFallback = assignedProvider == null && primaryEngine == null

    LaunchedEffect(activeEngine, geminiVoices.size, isLoadingGemini) {
        if (activeEngine == TtsEngine.GEMINI_BRIDGE && geminiVoices.isEmpty() && !isLoadingGemini) {
            onRefreshGemini()
        }
    }

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
        activeEngine,
        geminiVoiceIds,
        mlKitVoiceNames,
        systemVoiceNames,
    ) {
        val cleared = when (activeEngine) {
            TtsEngine.GEMINI_BRIDGE -> roleProfiles[activeRole].copy(geminiVoice = null)
            TtsEngine.OPENAI_CLOUD -> roleProfiles[activeRole].copy(openAiVoice = null)
            TtsEngine.MLKIT_GENAI -> roleProfiles[activeRole].copy(mlKitVoice = null)
            TtsEngine.ANDROID_SYSTEM -> roleProfiles[activeRole].copy(systemVoice = null)
            TtsEngine.LOCAL_KOKORO -> roleProfiles[activeRole].copy(kokoroSpeakerId = null)
        }
        VoiceRoleResolver.resolveVoiceForEngine(
            engine = activeEngine,
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VoiceRole.entries.forEach { role ->
                    val isRoleSelected = activeRole == role
                    FilterChip(
                        selected = isRoleSelected,
                        onClick = { onActiveRoleChange(role) },
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
                        colors = tonalSelectedChipColors(),
                        border = tonalSelectedChipBorder(isRoleSelected),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                text = "${activeRole.subtitle} · Active: $activeRoleSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Provider for ${activeRole.shortTitle}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val followSelected = assignedProvider == null
                val followLabel = primaryEngine?.let { "TTS Order (${it.shortTitle})" } ?: "TTS Order (Off)"
                FilterChip(
                    selected = followSelected,
                    onClick = { onSetRolePreferredEngine(activeRole, null) },
                    label = { Text(followLabel) },
                    colors = tonalSelectedChipColors(),
                    border = tonalSelectedChipBorder(followSelected),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                orderedEngines.forEach { engine ->
                    val selected = assignedProvider == engine
                    FilterChip(
                        selected = selected,
                        onClick = { onSetRolePreferredEngine(activeRole, engine) },
                        label = { Text(engine.shortTitle) },
                        colors = tonalSelectedChipColors(),
                        border = tonalSelectedChipBorder(selected),
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Text(
                text = if (assignedProvider != null) {
                    "Uses ${assignedProvider.title} first for ${activeRole.title}, falling back to TTS Order on failure."
                } else if (primaryEngine != null) {
                    "Follows global TTS Order (currently ${primaryEngine.title}). Pick any provider above to assign it directly."
                } else {
                    "All engines in global TTS Order are turned off."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (isFollowingTtsOrderWithNoEnabledFallback) {
            SettingsInlineNotice(
                text = "Pick a provider chip above for ${activeRole.shortTitle}, or enable an engine in TTS Order.",
                tone = SettingsNoticeTone.WARNING,
                actionLabel = if (onOpenTtsSettings != null) "TTS Order" else null,
                onAction = onOpenTtsSettings,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = expandListToFillHeight)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val listContainerModifier = if (expandListToFillHeight) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 320.dp)
            }
            when (activeEngine) {
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
                            modifier = listContainerModifier.selectableGroup(),
                            contentPadding = PaddingValues(bottom = 8.dp),
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
                            color = MaterialTheme.colorScheme.surfaceContainer,
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
                        val effectiveAgentMlKit = explicitMlKit ?: autoResolved.voiceId

                        LazyColumn(
                            modifier = listContainerModifier.selectableGroup(),
                            contentPadding = PaddingValues(bottom = 8.dp),
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
                                    effectiveAgentMlKit != null && effectiveAgentMlKit.equals(voice.name, ignoreCase = true)
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
                        modifier = listContainerModifier.selectableGroup(),
                        contentPadding = PaddingValues(bottom = 8.dp),
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
                        modifier = listContainerModifier.selectableGroup(),
                        contentPadding = PaddingValues(bottom = 8.dp),
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
                        color = MaterialTheme.colorScheme.surfaceContainer,
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

@Composable
internal fun VoiceOptionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(baseColor)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val selectionBorder = if (selected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f))
    } else {
        null
    }

    Surface(
        color = containerColor,
        border = selectionBorder,
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        tint = MaterialTheme.colorScheme.primary,
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
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
