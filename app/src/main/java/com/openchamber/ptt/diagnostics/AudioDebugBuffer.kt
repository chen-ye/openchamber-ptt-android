package com.openchamber.ptt.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Thread-safe bounded ring buffer for audio, Bluetooth, media-button, and key diagnostics.
 *
 * Entries are emitted **newest-first** via [entries] so live diagnostic UIs can render the
 * most recent events at the top without reversing on every frame.
 *
 * Pure Kotlin (zero `android.*` imports) so unit tests execute directly on the JVM.
 */
class AudioDebugBuffer(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val elapsedClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val lock = Any()
    private var seqCounter: Long = 0L

    @Volatile
    private var clearedAtSeq: Long = 0L

    private val _entries = MutableStateFlow<List<AudioDebugEntry>>(emptyList())
    val entries: StateFlow<List<AudioDebugEntry>> = _entries.asStateFlow()

    private val _liveStatus = MutableStateFlow(AudioLiveStatusSnapshot())
    val liveStatus: StateFlow<AudioLiveStatusSnapshot> = _liveStatus.asStateFlow()

    fun record(
        category: AudioDebugCategory,
        event: String,
        outcome: AudioDebugOutcome = AudioDebugOutcome.STATE_CHANGE,
        pttState: String = _liveStatus.value.pttState,
        details: Map<String, Any?> = emptyMap(),
    ): AudioDebugEntry {
        val stringDetails = buildMap {
            for ((key, value) in details) {
                if (value != null) {
                    put(key, value.toString())
                }
            }
        }
        val wallMs = wallClockMs()
        val elapsedMs = elapsedClockMs()
        val seq = synchronized(lock) {
            ++seqCounter
        }
        val entry = AudioDebugEntry(
            seq = seq,
            wallClockMs = wallMs,
            elapsedMs = elapsedMs,
            category = category,
            event = event,
            outcome = outcome,
            pttState = pttState,
            details = stringDetails,
        )

        // Publish outside synchronized(lock) so StateFlow collectors never run under the sequence lock.
        _entries.update { current ->
            if (entry.seq <= clearedAtSeq) {
                current
            } else {
                val headSeq = current.firstOrNull()?.seq ?: 0L
                if (entry.seq <= headSeq) {
                    (listOf(entry) + current)
                        .sortedByDescending { it.seq }
                        .take(maxEntries)
                } else {
                    buildList(minOf(current.size + 1, maxEntries)) {
                        add(entry)
                        val limit = maxEntries - 1
                        for (i in 0 until minOf(current.size, limit)) {
                            add(current[i])
                        }
                    }
                }
            }
        }
        return entry
    }

    fun updateLiveStatus(
        transform: (AudioLiveStatusSnapshot) -> AudioLiveStatusSnapshot,
    ) {
        _liveStatus.update(transform)
    }

    fun clear() {
        val clearSeq = synchronized(lock) {
            clearedAtSeq = seqCounter
            seqCounter
        }
        _entries.update { current ->
            if (current.isEmpty()) {
                current
            } else {
                current.filter { it.seq > clearSeq }
            }
        }
    }

    fun formatJsonl(
        entriesSnapshot: List<AudioDebugEntry> = entries.value,
    ): String {
        if (entriesSnapshot.isEmpty()) return ""
        return entriesSnapshot.joinToString(separator = "\n") { entry ->
            buildJsonObject {
                put("seq", entry.seq)
                put("wallClockMs", entry.wallClockMs)
                put("isoUtc", ISO_FORMATTER.format(Instant.ofEpochMilli(entry.wallClockMs)))
                put("elapsedMs", entry.elapsedMs)
                put("category", entry.category.name)
                put("event", entry.event)
                put("outcome", entry.outcome.name)
                put("pttState", entry.pttState)
                putJsonObject("details") {
                    for ((k, v) in entry.details) {
                        put(k, v)
                    }
                }
            }.toString()
        }
    }

    fun formatSnapshotReport(
        status: AudioLiveStatusSnapshot = liveStatus.value,
        entriesSnapshot: List<AudioDebugEntry> = entries.value,
    ): String {
        val generatedAt = ISO_FORMATTER.format(Instant.ofEpochMilli(wallClockMs()))
        return buildString {
            appendLine("OpenChamber PTT Audio Diagnostic Snapshot")
            appendLine("Generated: $generatedAt")
            appendLine()
            appendLine("[Live Status]")
            appendLine("pttState: ${status.pttState}")
            appendLine("captureTransport: ${status.captureTransport}")
            appendLine("audioMode: ${status.audioMode}")
            appendLine("communicationDevice: ${status.communicationDevice}")
            appendLine("isScoPinned: ${status.isScoPinned}")
            appendLine("scoConfirmedActive: ${status.scoConfirmedActive}")
            appendLine("telecomState: ${status.telecomState}")
            appendLine("mediaSessionActive: ${status.mediaSessionActive}")
            appendLine("mediaSessionPlaybackState: ${status.mediaSessionPlaybackState}")
            appendLine("volumeHandlingMode: ${status.volumeHandlingMode}")
            appendLine("activeRecordingSummary: ${status.activeRecordingSummary}")
            appendLine("activePlaybackSummary: ${status.activePlaybackSummary}")
            appendLine("btHeadsetState: ${status.btHeadsetState}")
            appendLine()
            appendLine("[Events (${entriesSnapshot.size}, newest first)]")
            if (entriesSnapshot.isEmpty()) {
                appendLine("(none)")
            } else {
                for (entry in entriesSnapshot) {
                    val ts = ISO_FORMATTER.format(Instant.ofEpochMilli(entry.wallClockMs))
                    val stateSuffix = entry.pttState.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                    val detailsSuffix = if (entry.details.isNotEmpty()) {
                        " | " + entry.details.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    } else {
                        ""
                    }
                    appendLine(
                        "#${entry.seq} $ts (${entry.elapsedMs}ms) ${entry.category.label}/${entry.outcome.label}$stateSuffix: ${entry.event}$detailsSuffix",
                    )
                }
            }
        }.trimEnd()
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 300

        private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
            .withZone(ZoneOffset.UTC)
    }
}

object AudioDebugRegistry {
    val shared: AudioDebugBuffer = AudioDebugBuffer()
}
