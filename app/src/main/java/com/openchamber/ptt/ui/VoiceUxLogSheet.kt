package com.openchamber.ptt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openchamber.ptt.R
import com.openchamber.ptt.logging.VoiceUxSummary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VoiceUxLogSheet(
    summary: VoiceUxSummary,
    onRefresh: () -> Unit,
    onCopyJsonl: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
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
                    text = "Voice UX Telemetry (${summary.totalEvents} events)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = "Refresh telemetry",
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Captures: ${summary.captureCount} · Avg dur: ${summary.avgCaptureDurationMs}ms · Avg STT: ${summary.avgSttLatencyMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "STT fallbacks: ${summary.sttFallbackCount} · 5m cap hits: ${summary.fiveMinCapHits}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Organize/Cleanup avg: ${summary.cleanupAvgLatencyMs}ms · Fallbacks/Timeouts: ${summary.cleanupTimeoutOrFallbackCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Send Grace — committed: ${summary.sendGraceCommitted}, undone: ${summary.sendGraceUndone}, discarded: ${summary.sendGraceDiscarded}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Draft Preview — done: ${summary.draftReadbackCompleted}, paused: ${summary.draftReadbackInterrupted}, sent early: ${summary.draftReadbackQuickSent}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (summary.gestureCounts.isNotEmpty()) {
                        Text(
                            text = "Gestures: " + summary.gestureCounts.entries.joinToString(", ") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onCopyJsonl,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Copy JSONL")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Clear Log")
                }
            }

            if (summary.recentLines.isEmpty()) {
                Text(
                    text = "No events recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(summary.recentLines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
