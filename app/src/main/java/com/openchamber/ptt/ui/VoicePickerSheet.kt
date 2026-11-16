package com.openchamber.ptt.ui

import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.openchamber.ptt.tts.SystemTtsVoiceSummary
import com.openchamber.ptt.tts.TtsEngine
import com.openchamber.ptt.tts.TtsEngineOption
import com.openchamber.ptt.tts.TtsVoice
import com.openchamber.ptt.tts.VoicePipelineDefaults
import com.openchamber.ptt.voice.MlKitAvailability
import com.openchamber.ptt.voice.MlKitStatusReport
import com.openchamber.ptt.voice.MlKitSttClient

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
internal fun VoiceOptionRow(
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
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
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
