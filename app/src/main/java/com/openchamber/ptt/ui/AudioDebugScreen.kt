package com.openchamber.ptt.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.R
import com.openchamber.ptt.diagnostics.AudioDebugBuffer
import com.openchamber.ptt.diagnostics.AudioDebugCategory
import com.openchamber.ptt.diagnostics.AudioDebugEntry
import com.openchamber.ptt.diagnostics.AudioDebugOutcome
import com.openchamber.ptt.diagnostics.AudioDebugRegistry
import com.openchamber.ptt.diagnostics.AudioLiveStatusSnapshot
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AudioDebugFilter(val label: String) {
    ALL("All"),
    DROPPED_IGNORED("Dropped/Ignored"),
    BUTTONS_AND_KEYS("Buttons & Keys"),
    BT_AND_TELECOM("BT & Telecom"),
    FOCUS_AND_STREAMS("Focus & Streams"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioDebugScreen(
    buffer: AudioDebugBuffer = AudioDebugRegistry.shared,
    onRefresh: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val liveEntries by buffer.entries.collectAsState()
    val liveStatus by buffer.liveStatus.collectAsState()

    var selectedFilter by rememberSaveable { mutableStateOf(AudioDebugFilter.ALL) }
    var statusExpanded by rememberSaveable { mutableStateOf(true) }
    var pausedAtMaxSeq by rememberSaveable { mutableStateOf<Long?>(null) }
    val isPaused = pausedAtMaxSeq != null

    val activeEntries = remember(liveEntries, pausedAtMaxSeq) {
        pausedAtMaxSeq?.let { maxSeq -> liveEntries.filter { it.seq <= maxSeq } } ?: liveEntries
    }
    val filteredEntries = remember(activeEntries, selectedFilter) {
        activeEntries.filter { matchesFilter(it, selectedFilter) }
    }
    val listState = rememberLazyListState()
    val newestSeq = filteredEntries.firstOrNull()?.seq
    LaunchedEffect(newestSeq, isPaused) {
        if (!isPaused && newestSeq != null && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) {
            listState.scrollToItem(0)
        }
    }

    fun copyToClipboard(label: String, text: String, feedbackMessage: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(feedbackMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Audio Diagnostics",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isPaused) {
                                "Paused · ${activeEntries.size} frozen (${liveEntries.size} live)"
                            } else {
                                "${filteredEntries.size} shown · ${liveEntries.size} buffered"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = "Refresh audio state",
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
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LiveAudioStatusCard(
                status = liveStatus,
                expanded = statusExpanded,
                onToggleExpanded = { statusExpanded = !statusExpanded },
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = {
                        val report = buffer.formatSnapshotReport(
                            status = liveStatus,
                            entriesSnapshot = activeEntries,
                        )
                        copyToClipboard(
                            label = "OpenChamber Audio Snapshot",
                            text = report,
                            feedbackMessage = "Copied diagnostic snapshot",
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Snapshot")
                }

                FilledTonalButton(
                    onClick = {
                        val jsonl = buffer.formatJsonl(entriesSnapshot = activeEntries)
                        copyToClipboard(
                            label = "OpenChamber Audio JSONL",
                            text = jsonl,
                            feedbackMessage = if (jsonl.isEmpty()) "No events to copy" else "Copied JSONL log",
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_copy),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy JSONL")
                }

                if (isPaused) {
                    FilledTonalButton(
                        onClick = {
                            pausedAtMaxSeq = null
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resume")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            pausedAtMaxSeq = liveEntries.maxOfOrNull { it.seq } ?: 0L
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pause),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pause")
                    }
                }

                OutlinedButton(
                    onClick = {
                        buffer.clear()
                        pausedAtMaxSeq = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_stop),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AudioDebugFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }

            if (filteredEntries.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (activeEntries.isEmpty()) {
                                "No audio diagnostic events recorded yet."
                            } else {
                                "No events match \"${selectedFilter.label}\"."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Press headset buttons, volume keys, or arm PTT to observe routing and event handling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = filteredEntries,
                        key = { it.seq },
                    ) { entry ->
                        AudioDebugEntryRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveAudioStatusCard(
    status: AudioLiveStatusSnapshot,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onToggleExpanded)
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bluetooth_audio),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Live Audio State",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${status.pttState} · ${status.audioMode} · ${status.communicationDevice}",
                        style = monoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = if (expanded) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "ptt=${status.pttState} | transport=${status.captureTransport}",
                        style = monoStyle,
                    )
                    Text(
                        text = "mode=${status.audioMode} | commDev=${status.communicationDevice}",
                        style = monoStyle,
                    )
                    Text(
                        text = "scoPinned=${status.isScoPinned} | scoActive=${status.scoConfirmedActive} | bt=${status.btHeadsetState}",
                        style = monoStyle,
                    )
                    Text(
                        text = "telecom=${status.telecomState} | vol=${status.volumeHandlingMode}",
                        style = monoStyle,
                    )
                    Text(
                        text = "mediaSession=${if (status.mediaSessionActive) "ACTIVE" else "INACTIVE"} (${status.mediaSessionPlaybackState})",
                        style = monoStyle,
                    )
                    Text(
                        text = "playback=${status.activePlaybackSummary}",
                        style = monoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "recording=${status.activeRecordingSummary}",
                        style = monoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioDebugEntryRow(entry: AudioDebugEntry) {
    val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val outcomeContainerColor = when (entry.outcome) {
        AudioDebugOutcome.ACCEPTED -> MaterialTheme.colorScheme.primaryContainer
        AudioDebugOutcome.STATE_CHANGE -> MaterialTheme.colorScheme.tertiaryContainer
        AudioDebugOutcome.IGNORED -> MaterialTheme.colorScheme.surfaceContainerHighest
        AudioDebugOutcome.DROPPED -> MaterialTheme.colorScheme.errorContainer
        AudioDebugOutcome.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val outcomeContentColor = when (entry.outcome) {
        AudioDebugOutcome.ACCEPTED -> MaterialTheme.colorScheme.onPrimaryContainer
        AudioDebugOutcome.STATE_CHANGE -> MaterialTheme.colorScheme.onTertiaryContainer
        AudioDebugOutcome.IGNORED -> MaterialTheme.colorScheme.onSurfaceVariant
        AudioDebugOutcome.DROPPED -> MaterialTheme.colorScheme.onErrorContainer
        AudioDebugOutcome.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }

    val formattedTime = remember(entry.wallClockMs) {
        TIME_FORMATTER.format(Instant.ofEpochMilli(entry.wallClockMs))
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "#${entry.seq} $formattedTime",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = entry.category.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Surface(
                    color = outcomeContainerColor,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = entry.outcome.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = outcomeContentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (entry.pttState.isNotBlank()) {
                    Text(
                        text = entry.pttState,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Text(
                text = entry.event,
                style = monoStyle.copy(fontWeight = FontWeight.SemiBold),
            )

            if (entry.details.isNotEmpty()) {
                val detailsText = remember(entry.details) {
                    entry.details.entries.joinToString(" · ") { "${it.key}=${it.value}" }
                }
                Text(
                    text = detailsText,
                    style = monoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun matchesFilter(entry: AudioDebugEntry, filter: AudioDebugFilter): Boolean {
    return when (filter) {
        AudioDebugFilter.ALL -> true
        AudioDebugFilter.DROPPED_IGNORED -> {
            entry.outcome == AudioDebugOutcome.DROPPED ||
                entry.outcome == AudioDebugOutcome.IGNORED ||
                entry.outcome == AudioDebugOutcome.ERROR
        }
        AudioDebugFilter.BUTTONS_AND_KEYS -> {
            entry.category == AudioDebugCategory.MEDIA_BUTTON ||
                entry.category == AudioDebugCategory.VOLUME_KEY ||
                entry.category == AudioDebugCategory.FOREGROUND_KEY
        }
        AudioDebugFilter.BT_AND_TELECOM -> {
            entry.category == AudioDebugCategory.BT_HEADSET ||
                entry.category == AudioDebugCategory.TELECOM ||
                entry.category == AudioDebugCategory.AUDIO_ROUTE
        }
        AudioDebugFilter.FOCUS_AND_STREAMS -> {
            entry.category == AudioDebugCategory.AUDIO_FOCUS ||
                entry.category == AudioDebugCategory.PLAYBACK_CONFIG ||
                entry.category == AudioDebugCategory.RECORDING_CONFIG
        }
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())
