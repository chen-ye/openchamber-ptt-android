package com.openchamber.ptt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingVolumeKeyControllerTest {
    private class FakeMediaSessionVolumeAdapter : MediaSessionVolumeAdapter {
        var attachCalls = 0
        var restoreCalls = 0
        var isRemoteAttached = false
        val attachedCallbacks = mutableListOf<(Int) -> Unit>()
        var onRestoreHook: (() -> Unit)? = null

        override fun attachRelativeVolumeProvider(onAdjust: (direction: Int) -> Unit) {
            attachCalls++
            isRemoteAttached = true
            attachedCallbacks += onAdjust
        }

        override fun restoreLocalStreamMusic() {
            restoreCalls++
            isRemoteAttached = false
            onRestoreHook?.invoke()
        }

        fun dispatchAdjust(direction: Int, index: Int = attachedCallbacks.lastIndex) {
            attachedCallbacks.getOrNull(index)?.invoke(direction)
        }
    }

    @Test
    fun `attaches iff recording and featureEnabled, and ignores direction zero`() {
        val adapter = FakeMediaSessionVolumeAdapter()
        val stoppedDirections = mutableListOf<Int>()
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val controller = RecordingVolumeKeyController(
            adapter = adapter,
            onVolumeKeyStop = { stoppedDirections += it },
        )
        controller.onDiagnosticEvent = { event, outcome, details ->
            diagnostics += Triple(event, outcome, details)
        }

        controller.sync(isRecording = false, featureEnabled = false)
        controller.sync(isRecording = false, featureEnabled = true)
        controller.sync(isRecording = true, featureEnabled = false)
        assertEquals(0, adapter.attachCalls)
        assertEquals(0, adapter.restoreCalls)
        assertFalse(controller.isAttached)

        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(1, adapter.attachCalls)
        assertTrue(adapter.isRemoteAttached)
        assertTrue(controller.isAttached)

        // ACTION_UP (direction == 0) is ignored without detaching or stopping
        adapter.dispatchAdjust(0)
        assertTrue(stoppedDirections.isEmpty())
        assertTrue(adapter.isRemoteAttached)
        assertTrue(controller.isAttached)
        assertEquals(0, adapter.restoreCalls)
        assertEquals("IGNORED", diagnostics.last().second)
        assertEquals("action_up_zero", diagnostics.last().third["reason"])
    }

    @Test
    fun `first volume adjustment restores STREAM_MUSIC immediately before stop callback and debounces repeats`() {
        val adapter = FakeMediaSessionVolumeAdapter()
        val timeline = mutableListOf<String>()
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        adapter.onRestoreHook = { timeline += "restore_stream_music" }

        val controller = RecordingVolumeKeyController(
            adapter = adapter,
            onVolumeKeyStop = { dir -> timeline += "stop:$dir" },
        )
        controller.onDiagnosticEvent = { event, outcome, details ->
            diagnostics += Triple(event, outcome, details)
        }

        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(1, adapter.attachCalls)

        // Press Volume Down (-1): restores local STREAM_MUSIC BEFORE invoking onVolumeKeyStop
        adapter.dispatchAdjust(-1)
        assertEquals(listOf("restore_stream_music", "stop:-1"), timeline)
        assertFalse(adapter.isRemoteAttached)
        assertFalse(controller.isAttached)
        assertEquals(1, adapter.restoreCalls)
        assertEquals("ACCEPTED", diagnostics.last().second)
        assertEquals("-1", diagnostics.last().third["direction"])

        // Repeat direction -1 (or +1) during same recording is debounced
        adapter.dispatchAdjust(-1)
        adapter.dispatchAdjust(1)
        assertEquals(listOf("restore_stream_music", "stop:-1"), timeline)
        assertEquals(1, adapter.restoreCalls)
        assertEquals("IGNORED", diagnostics.last().second)
        assertEquals("already_triggered", diagnostics.last().third["reason"])

        // Redundant sync while still in the same recording does not re-attach
        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(1, adapter.attachCalls)
        assertFalse(controller.isAttached)

        // Exiting recording cleans up state without redundant restoreLocalStreamMusic
        controller.sync(isRecording = false, featureEnabled = true)
        assertEquals(1, adapter.restoreCalls)

        // Fresh recording resets latch and re-attaches; stale provider from recording #1 is ignored
        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(2, adapter.attachCalls)
        assertTrue(controller.isAttached)

        adapter.dispatchAdjust(-1, index = 0)
        assertEquals(listOf("restore_stream_music", "stop:-1"), timeline)
        assertEquals("IGNORED", diagnostics.last().second)
        assertEquals("not_attached", diagnostics.last().third["reason"])

        // Recording #2 provider triggers cleanly
        adapter.dispatchAdjust(1, index = 1)
        assertEquals(
            listOf("restore_stream_music", "stop:-1", "restore_stream_music", "stop:1"),
            timeline,
        )
    }

    @Test
    fun `STREAM_MUSIC is restored on exit from recording, feature toggle off, and release`() {
        val adapter = FakeMediaSessionVolumeAdapter()
        val controller = RecordingVolumeKeyController(
            adapter = adapter,
            onVolumeKeyStop = {},
        )

        // 1. Exit recording restores STREAM_MUSIC
        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(1, adapter.attachCalls)
        controller.sync(isRecording = false, featureEnabled = true)
        assertEquals(1, adapter.restoreCalls)
        assertFalse(adapter.isRemoteAttached)
        assertFalse(controller.isAttached)

        // 2. Toggling feature off mid-recording restores STREAM_MUSIC
        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(2, adapter.attachCalls)
        controller.sync(isRecording = true, featureEnabled = false)
        assertEquals(2, adapter.restoreCalls)
        assertFalse(adapter.isRemoteAttached)
        assertFalse(controller.isAttached)

        // Stale callback after feature toggle off is ignored as not_attached
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        controller.onDiagnosticEvent = { event, outcome, details ->
            diagnostics += Triple(event, outcome, details)
        }
        adapter.dispatchAdjust(-1)
        assertEquals("IGNORED", diagnostics.last().second)
        assertEquals("not_attached", diagnostics.last().third["reason"])

        // Toggling feature back on mid-recording (before trigger) re-attaches
        controller.sync(isRecording = true, featureEnabled = true)
        assertEquals(3, adapter.attachCalls)
        assertTrue(controller.isAttached)

        // 3. release() while attached restores STREAM_MUSIC; repeated release() is a no-op
        controller.release()
        assertEquals(3, adapter.restoreCalls)
        assertFalse(adapter.isRemoteAttached)
        assertFalse(controller.isAttached)
        controller.release()
        assertEquals(3, adapter.restoreCalls)
    }
}
