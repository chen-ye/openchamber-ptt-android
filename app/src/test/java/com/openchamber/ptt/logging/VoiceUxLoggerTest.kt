package com.openchamber.ptt.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class VoiceUxLoggerTest {
    @Test
    fun `logs structured JSONL events, summarizes metrics, and rotates over size cap`() {
        val tempDir = Files.createTempDirectory("ux-log-test").toFile()
        try {
            val file = File(tempDir, "ux.jsonl")
            var now = 1_700_000_000_000L
            val logger = VoiceUxLogger(
                logFile = file,
                maxFileBytes = 2048L,
                clockMs = { now.also { now += 1_000L } },
            )

            logger.log(VoiceUxLogger.CAT_GESTURE, "double_tap", "s1", mapOf("waitMs" to 180))
            logger.log(
                VoiceUxLogger.CAT_CAPTURE,
                "completed",
                "s1",
                mapOf("durationMs" to 4200, "sttLatencyMs" to 310, "fallbackUsed" to true, "hitMaxCap" to false),
            )
            logger.log(
                VoiceUxLogger.CAT_ORGANIZE,
                "smart_merge",
                "s1",
                mapOf("latencyMs" to 640, "timedOut" to false, "fallbackUsed" to false),
            )
            logger.log(VoiceUxLogger.CAT_SEND_GRACE, "pulled_back", "s1", mapOf("elapsedMs" to 900))
            logger.log(VoiceUxLogger.CAT_TTS, "completed", "s1", mapOf("kind" to "draft_preview"))

            val summary = logger.readSummary()
            assertEquals(5, summary.totalEvents)
            assertEquals(1, summary.gestureCounts["double_tap"])
            assertEquals(1, summary.captureCount)
            assertEquals(4200L, summary.avgCaptureDurationMs)
            assertEquals(310L, summary.avgSttLatencyMs)
            assertEquals(1, summary.sttFallbackCount)
            assertEquals(640L, summary.cleanupAvgLatencyMs)
            assertEquals(1, summary.sendGraceUndone)
            assertEquals(1, summary.draftReadbackCompleted)

            // Rotation keeps file bounded
            repeat(40) { idx ->
                logger.log(VoiceUxLogger.CAT_TURN, "completed", "s1", mapOf("idx" to idx))
            }
            assertTrue(file.length() < 3072L)

            logger.clear()
            assertEquals(0, logger.readEvents().size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `background executor flushes queued log entries before summary and clear`() {
        val tempDir = Files.createTempDirectory("ux-log-async-test").toFile()
        val exec = Executors.newSingleThreadExecutor()
        val logger = VoiceUxLogger(
            logFile = File(tempDir, "ux.jsonl"),
            maxFileBytes = 4096L,
            writeExecutor = exec,
        )
        try {
            repeat(10) { idx ->
                logger.log(VoiceUxLogger.CAT_GESTURE, "tap_$idx", "s1")
            }
            val summary = logger.readSummary()
            assertEquals(10, summary.totalEvents)
            logger.clear()
            assertEquals(0, logger.readEvents().size)
        } finally {
            logger.close()
            tempDir.deleteRecursively()
        }
    }
}
