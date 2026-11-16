package com.openchamber.ptt.logging

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class VoiceUxEvent(
    val tsMs: Long,
    val isoUtc: String,
    val category: String,
    val event: String,
    val sessionKey: String? = null,
    val metrics: Map<String, String> = emptyMap(),
)

data class VoiceUxSummary(
    val totalEvents: Int,
    val gestureCounts: Map<String, Int>,
    val captureCount: Int,
    val avgCaptureDurationMs: Long,
    val avgSttLatencyMs: Long,
    val sttFallbackCount: Int,
    val fiveMinCapHits: Int,
    val cleanupAvgLatencyMs: Long,
    val cleanupTimeoutOrFallbackCount: Int,
    val sendGraceCommitted: Int,
    val sendGraceUndone: Int,
    val sendGraceDiscarded: Int,
    val draftReadbackCompleted: Int,
    val draftReadbackInterrupted: Int,
    val draftReadbackQuickSent: Int,
    val recentLines: List<String>,
)

/**
 * Lightweight JSONL performance & ergonomics logger for evaluating voice UX decisions
 * (tap cadence reliability, 5m cap hits, STT fallbacks, small-model latencies, send-grace undos,
 * draft lifecycle, and TTS readback interruptions) without recording raw user secrets.
 */
class VoiceUxLogger internal constructor(
    private val logFile: File?,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val logcatSink: ((String) -> Unit)? = null,
    private val writeExecutor: ExecutorService? = null,
    private val json: Json = DefaultJson,
) {
    constructor(context: Context) : this(
        logFile = File(context.applicationContext.filesDir, LOG_FILE_NAME),
        logcatSink = { line -> runCatching { Log.i(TAG, line) } },
        writeExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VoiceUxLogger").apply { isDaemon = true }
        },
    )

    private val lock = Any()

    fun log(
        category: String,
        event: String,
        sessionKey: String? = null,
        metrics: Map<String, Any?> = emptyMap(),
    ) {
        val nowMs = clockMs()
        val stringMetrics = buildMap {
            for ((k, v) in metrics) {
                if (v != null) put(k, v.toString())
            }
        }
        val entry = VoiceUxEvent(
            tsMs = nowMs,
            isoUtc = ISO_FORMATTER.format(Instant.ofEpochMilli(nowMs)),
            category = category,
            event = event,
            sessionKey = sessionKey?.takeIf { it.isNotBlank() },
            metrics = stringMetrics,
        )
        val line = json.encodeToString(entry)
        logcatSink?.invoke(line)

        val target = logFile ?: return
        val exec = writeExecutor
        if (exec != null && !exec.isShutdown) {
            runCatching {
                exec.execute { writeLineLocked(target, line) }
            }
        } else {
            writeLineLocked(target, line)
        }
    }

    private fun writeLineLocked(target: File, line: String) {
        synchronized(lock) {
            runCatching {
                target.parentFile?.mkdirs()
                if (target.exists() && target.length() >= maxFileBytes) {
                    rotateLocked(target)
                }
                target.appendText("$line\n", Charsets.UTF_8)
            }
        }
    }

    internal fun flush(timeoutMs: Long = 2_000L) {
        val exec = writeExecutor ?: return
        if (exec.isShutdown) return
        val latch = CountDownLatch(1)
        val submitted = runCatching {
            exec.execute { latch.countDown() }
        }.isSuccess
        if (submitted) {
            runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    fun readEvents(limit: Int = 500): List<VoiceUxEvent> {
        val target = logFile ?: return emptyList()
        flush()
        val lines = synchronized(lock) {
            if (!target.exists()) return emptyList()
            runCatching { target.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        }
        if (lines.isEmpty()) return emptyList()
        return lines
            .takeLast(limit.coerceAtLeast(1))
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) null
                else runCatching { json.decodeFromString<VoiceUxEvent>(trimmed) }.getOrNull()
            }
    }

    fun readSummary(recentLimit: Int = 25): VoiceUxSummary {
        val events = readEvents(limit = 1000)
        val gestureCounts = linkedMapOf<String, Int>()
        var captureCount = 0
        var captureDurSum = 0L
        var sttLatSum = 0L
        var sttLatCount = 0
        var sttFallbackCount = 0
        var fiveMinCapHits = 0
        var cleanupLatSum = 0L
        var cleanupCount = 0
        var cleanupFallbackCount = 0
        var graceCommitted = 0
        var graceUndone = 0
        var graceDiscarded = 0
        var readbackCompleted = 0
        var readbackInterrupted = 0
        var readbackQuickSent = 0

        for (ev in events) {
            when (ev.category) {
                CAT_GESTURE -> {
                    val key = ev.event
                    gestureCounts[key] = (gestureCounts[key] ?: 0) + 1
                }
                CAT_CAPTURE -> {
                    captureCount++
                    ev.metrics["durationMs"]?.toLongOrNull()?.let { captureDurSum += it }
                    ev.metrics["sttLatencyMs"]?.toLongOrNull()?.let {
                        sttLatSum += it
                        sttLatCount++
                    }
                    if (ev.metrics["fallbackUsed"] == "true") sttFallbackCount++
                    if (ev.metrics["hitMaxCap"] == "true") fiveMinCapHits++
                }
                CAT_ORGANIZE -> {
                    ev.metrics["latencyMs"]?.toLongOrNull()?.let {
                        cleanupLatSum += it
                        cleanupCount++
                    }
                    if (ev.metrics["fallbackUsed"] == "true" || ev.metrics["timedOut"] == "true") {
                        cleanupFallbackCount++
                    }
                }
                CAT_SEND_GRACE -> {
                    when (ev.event) {
                        "committed" -> graceCommitted++
                        "pulled_back" -> graceUndone++
                        "discarded" -> graceDiscarded++
                    }
                }
                CAT_TTS -> {
                    if (ev.metrics["kind"] == "draft_preview") {
                        when (ev.event) {
                            "completed" -> readbackCompleted++
                            "interrupted" -> readbackInterrupted++
                            "committed_during_preview" -> readbackQuickSent++
                        }
                    }
                }
            }
        }

        val recentLines = events.takeLast(recentLimit.coerceAtLeast(1)).reversed().map { ev ->
            val metricStr = if (ev.metrics.isEmpty()) {
                ""
            } else {
                " " + ev.metrics.entries.joinToString(" ") { "${it.key}=${it.value}" }
            }
            "${ev.isoUtc.substringAfter('T').removeSuffix("Z")} [${ev.category}] ${ev.event}$metricStr"
        }

        return VoiceUxSummary(
            totalEvents = events.size,
            gestureCounts = gestureCounts,
            captureCount = captureCount,
            avgCaptureDurationMs = if (captureCount > 0) captureDurSum / captureCount else 0L,
            avgSttLatencyMs = if (sttLatCount > 0) sttLatSum / sttLatCount else 0L,
            sttFallbackCount = sttFallbackCount,
            fiveMinCapHits = fiveMinCapHits,
            cleanupAvgLatencyMs = if (cleanupCount > 0) cleanupLatSum / cleanupCount else 0L,
            cleanupTimeoutOrFallbackCount = cleanupFallbackCount,
            sendGraceCommitted = graceCommitted,
            sendGraceUndone = graceUndone,
            sendGraceDiscarded = graceDiscarded,
            draftReadbackCompleted = readbackCompleted,
            draftReadbackInterrupted = readbackInterrupted,
            draftReadbackQuickSent = readbackQuickSent,
            recentLines = recentLines,
        )
    }

    fun exportJsonl(): String {
        val target = logFile ?: return ""
        flush()
        return synchronized(lock) {
            if (!target.exists()) "" else runCatching { target.readText(Charsets.UTF_8) }.getOrDefault("")
        }
    }

    fun clear() {
        val target = logFile ?: return
        flush()
        synchronized(lock) {
            runCatching { target.delete() }
        }
    }

    fun close() {
        runCatching { writeExecutor?.shutdown() }
    }

    private fun rotateLocked(file: File) {
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        val keep = lines.takeLast((lines.size / 2).coerceAtLeast(1))
        file.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "OpenChamberUx"
        const val LOG_FILE_NAME = "voice_ux_log.jsonl"
        private const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L

        val EMPTY_SUMMARY = VoiceUxSummary(
            totalEvents = 0,
            gestureCounts = emptyMap(),
            captureCount = 0,
            avgCaptureDurationMs = 0L,
            avgSttLatencyMs = 0L,
            sttFallbackCount = 0,
            fiveMinCapHits = 0,
            cleanupAvgLatencyMs = 0L,
            cleanupTimeoutOrFallbackCount = 0,
            sendGraceCommitted = 0,
            sendGraceUndone = 0,
            sendGraceDiscarded = 0,
            draftReadbackCompleted = 0,
            draftReadbackInterrupted = 0,
            draftReadbackQuickSent = 0,
            recentLines = emptyList(),
        )

        const val CAT_GESTURE = "gesture"
        const val CAT_CAPTURE = "capture"
        const val CAT_ORGANIZE = "organize"
        const val CAT_SEND_GRACE = "send_grace"
        const val CAT_DRAFT = "draft"
        const val CAT_TTS = "tts"
        const val CAT_TURN = "turn"

        private val ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
