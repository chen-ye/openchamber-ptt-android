package com.openchamber.ptt.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioDebugBufferTest {
    @Test
    fun `ring buffer bounds to maxEntries, preserves newest first, and maintains monotonic seq`() {
        var wallMs = 1_700_000_000_000L
        var elapsedMs = 100L
        val buffer = AudioDebugBuffer(
            maxEntries = 3,
            wallClockMs = { wallMs.also { wallMs += 1_000L } },
            elapsedClockMs = { elapsedMs.also { elapsedMs += 50L } },
        )

        buffer.record(AudioDebugCategory.PTT_STATE, "ARMED", AudioDebugOutcome.STATE_CHANGE, pttState = "ARMED_IDLE")
        buffer.record(AudioDebugCategory.MEDIA_BUTTON, "PLAY_PAUSE", AudioDebugOutcome.ACCEPTED, pttState = "ARMED_IDLE")
        buffer.record(AudioDebugCategory.AUDIO_ROUTE, "SCO_PIN", AudioDebugOutcome.STATE_CHANGE, pttState = "RECORDING")
        buffer.record(AudioDebugCategory.VOLUME_KEY, "VOLUME_UP", AudioDebugOutcome.IGNORED, pttState = "RECORDING")
        buffer.record(AudioDebugCategory.BT_HEADSET, "HFP_DROPPED", AudioDebugOutcome.DROPPED, pttState = "RECORDING")

        val entries = buffer.entries.value
        assertEquals(3, entries.size)
        // Newest-first ordering: seq 5, 4, 3; seq 1 and 2 evicted.
        assertEquals(listOf(5L, 4L, 3L), entries.map { it.seq })
        assertEquals(listOf("HFP_DROPPED", "VOLUME_UP", "SCO_PIN"), entries.map { it.event })
        assertEquals(1_700_000_004_000L, entries[0].wallClockMs)
        assertEquals(300L, entries[0].elapsedMs)
    }

    @Test
    fun `updateLiveStatus stamps pttState defaults and survives clear`() {
        val buffer = AudioDebugBuffer(maxEntries = 10)

        buffer.updateLiveStatus {
            it.copy(
                pttState = "RECORDING",
                captureTransport = "BLUETOOTH_SCO",
                audioMode = "MODE_IN_COMMUNICATION",
                communicationDevice = "BT_SCO (Pixel Buds)",
                isScoPinned = true,
                scoConfirmedActive = true,
            )
        }

        val recorded = buffer.record(
            category = AudioDebugCategory.AUDIO_FOCUS,
            event = "GAIN_TRANSIENT",
            outcome = AudioDebugOutcome.ACCEPTED,
            details = mapOf("usage" to "VOICE_COMM", "ignoredNull" to null),
        )
        assertEquals("RECORDING", recorded.pttState)
        assertEquals(mapOf("usage" to "VOICE_COMM"), recorded.details)
        assertEquals(1, buffer.entries.value.size)

        buffer.clear()
        assertEquals(0, buffer.entries.value.size)
        assertEquals("RECORDING", buffer.liveStatus.value.pttState)
        assertTrue(buffer.liveStatus.value.isScoPinned)

        // Monotonic seq continues even after clear()
        val afterClear = buffer.record(AudioDebugCategory.PTT_STATE, "DISARMED")
        assertEquals(2L, afterClear.seq)
        assertEquals(1, buffer.entries.value.size)
    }

    @Test
    fun `formatJsonl produces valid single-line JSON entries`() {
        val buffer = AudioDebugBuffer(
            wallClockMs = { 1_700_000_000_000L },
            elapsedClockMs = { 42L },
        )
        buffer.record(
            category = AudioDebugCategory.FOREGROUND_KEY,
            event = "KEYCODE_HEADSETHOOK",
            outcome = AudioDebugOutcome.ACCEPTED,
            pttState = "ARMED_IDLE",
            details = mapOf("action" to "ACTION_DOWN", "device" to "Buds \"Pro\""),
        )
        buffer.record(
            category = AudioDebugCategory.BT_HEADSET,
            event = "VENDOR_AT_+XEVENT",
            outcome = AudioDebugOutcome.DROPPED,
            pttState = "ARMED_IDLE",
        )

        val jsonl = buffer.formatJsonl()
        val lines = jsonl.lines()
        assertEquals(2, lines.size)

        val firstObj = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("2", firstObj["seq"]?.jsonPrimitive?.content)
        assertEquals("BT_HEADSET", firstObj["category"]?.jsonPrimitive?.content)
        assertEquals("VENDOR_AT_+XEVENT", firstObj["event"]?.jsonPrimitive?.content)
        assertEquals("DROPPED", firstObj["outcome"]?.jsonPrimitive?.content)

        val secondObj = Json.parseToJsonElement(lines[1]).jsonObject
        assertEquals("1", secondObj["seq"]?.jsonPrimitive?.content)
        assertEquals("FOREGROUND_KEY", secondObj["category"]?.jsonPrimitive?.content)
        assertEquals("Buds \"Pro\"", secondObj["details"]?.jsonObject?.get("device")?.jsonPrimitive?.content)

        buffer.clear()
        assertEquals("", buffer.formatJsonl())
    }

    @Test
    fun `formatSnapshotReport includes live status fields and formatted events`() {
        val buffer = AudioDebugBuffer(
            wallClockMs = { 1_700_000_000_000L },
            elapsedClockMs = { 123L },
        )
        buffer.updateLiveStatus {
            it.copy(
                pttState = "SPEAKING_TTS",
                audioMode = "MODE_NORMAL",
                communicationDevice = "BT_A2DP (Headphones)",
                mediaSessionActive = true,
                mediaSessionPlaybackState = "STATE_PLAYING",
                btHeadsetState = "Connected (Headphones)",
            )
        }
        buffer.record(
            category = AudioDebugCategory.MEDIA_BUTTON,
            event = "KEYCODE_MEDIA_PAUSE",
            outcome = AudioDebugOutcome.ACCEPTED,
            details = mapOf("bargeIn" to true),
        )

        val report = buffer.formatSnapshotReport()
        assertTrue(report.contains("OpenChamber PTT Audio Diagnostic Snapshot"))
        assertTrue(report.contains("pttState: SPEAKING_TTS"))
        assertTrue(report.contains("communicationDevice: BT_A2DP (Headphones)"))
        assertTrue(report.contains("mediaSessionActive: true"))
        assertTrue(report.contains("btHeadsetState: Connected (Headphones)"))
        assertTrue(report.contains("#1"))
        assertTrue(report.contains("KEYCODE_MEDIA_PAUSE"))
        assertTrue(report.contains("bargeIn=true"))
        assertFalse(report.contains("(none)"))
    }

    @Test
    fun `concurrent record calls stay bounded with strictly descending unique sequences`() {
        val buffer = AudioDebugBuffer(maxEntries = 50)
        val threads = 4
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)

        try {
            repeat(threads) { threadIdx ->
                pool.execute {
                    startGate.await()
                    repeat(perThread) { idx ->
                        buffer.record(
                            category = AudioDebugCategory.AUDIO_ROUTE,
                            event = "EVT_${threadIdx}_$idx",
                            outcome = AudioDebugOutcome.STATE_CHANGE,
                        )
                    }
                    doneGate.countDown()
                }
            }
            startGate.countDown()
            assertTrue(doneGate.await(5, TimeUnit.SECONDS))

            val snapshot = buffer.entries.value
            assertEquals(50, snapshot.size)
            val seqs = snapshot.map { it.seq }
            assertEquals(seqs.distinct().size, seqs.size)
            assertEquals(seqs.sortedDescending(), seqs)
            assertEquals(400L, seqs.first())
        } finally {
            pool.shutdownNow()
        }
    }
}
