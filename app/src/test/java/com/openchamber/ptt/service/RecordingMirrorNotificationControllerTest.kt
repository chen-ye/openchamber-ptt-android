package com.openchamber.ptt.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMirrorNotificationControllerTest {
    private class FakeRecordingMirrorPlatform(
        var permissionGranted: Boolean = true,
    ) : RecordingMirrorPlatform {
        val postedTitles = mutableListOf<String?>()
        var cancelCount = 0

        override fun hasPostNotificationsPermission(): Boolean = permissionGranted

        override fun post(sessionTitle: String?) {
            postedTitles.add(sessionTitle)
        }

        override fun cancel() {
            cancelCount++
        }
    }

    @After
    fun tearDown() {
        RecordingNotificationActionReceiver.onStopRecordingRequested = null
        RecordingNotificationActionReceiver.onDiagnosticEvent = null
    }

    @Test
    fun `posts once per recording burst and ignores mid-recording status updates`() {
        val platform = FakeRecordingMirrorPlatform(permissionGranted = true)
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val controller = RecordingMirrorNotificationController(platform) { event, outcome, details ->
            diagnostics.add(Triple(event, outcome, details))
        }

        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Main Session")
        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Main Session (still talking)")
        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Main Session (almost done)")

        assertEquals(listOf<String?>("Main Session"), platform.postedTitles)
        assertEquals(0, platform.cancelCount)
        assertEquals(1, diagnostics.size)
        assertEquals("POSTED", diagnostics[0].second)
        assertEquals("Main Session", diagnostics[0].third["sessionTitle"])

        // Ending recording cancels unconditionally and allows next burst to post again
        controller.sync(isRecording = false, featureEnabled = true, sessionTitle = "Main Session")
        assertEquals(1, platform.cancelCount)
        assertEquals("CANCELLED", diagnostics[1].second)
        assertEquals("not_recording", diagnostics[1].third["reason"])

        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Second Session")
        assertEquals(listOf<String?>("Main Session", "Second Session"), platform.postedTitles)
        assertEquals(3, diagnostics.size)
        assertEquals("POSTED", diagnostics[2].second)
    }

    @Test
    fun `skips post when POST_NOTIFICATIONS permission is missing and posts once granted on next burst`() {
        val platform = FakeRecordingMirrorPlatform(permissionGranted = false)
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val controller = RecordingMirrorNotificationController(platform) { event, outcome, details ->
            diagnostics.add(Triple(event, outcome, details))
        }

        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Alpha")
        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Alpha")

        assertTrue(platform.postedTitles.isEmpty())
        assertEquals(1, diagnostics.size)
        assertEquals("SKIPPED", diagnostics[0].second)
        assertEquals("missing_post_notifications_permission", diagnostics[0].third["reason"])

        controller.sync(isRecording = false, featureEnabled = true, sessionTitle = "Alpha")
        assertEquals(1, platform.cancelCount)
        assertEquals(1, diagnostics.size)

        platform.permissionGranted = true
        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Alpha")
        assertEquals(listOf<String?>("Alpha"), platform.postedTitles)
        assertEquals("POSTED", diagnostics[1].second)
    }

    @Test
    fun `cancels unconditionally when feature disabled, onServiceCreated, or release`() {
        val platform = FakeRecordingMirrorPlatform(permissionGranted = true)
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val controller = RecordingMirrorNotificationController(platform) { event, outcome, details ->
            diagnostics.add(Triple(event, outcome, details))
        }

        // Unconditional cancel on service creation even when nothing was posted in this instance
        controller.onServiceCreated()
        assertEquals(1, platform.cancelCount)
        assertEquals("CANCELLED", diagnostics.last().second)
        assertEquals("service_created", diagnostics.last().third["reason"])

        // Unconditional cancel when not recording or feature disabled
        controller.sync(isRecording = false, featureEnabled = true, sessionTitle = null)
        assertEquals(2, platform.cancelCount)

        controller.sync(isRecording = true, featureEnabled = false, sessionTitle = "Ignored")
        assertTrue(platform.postedTitles.isEmpty())
        assertEquals(3, platform.cancelCount)

        // Post then disable feature mid-recording -> cancels with feature_disabled
        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Active")
        assertEquals(1, platform.postedTitles.size)
        controller.sync(isRecording = true, featureEnabled = false, sessionTitle = "Active")
        assertEquals(4, platform.cancelCount)
        assertEquals("CANCELLED", diagnostics.last().second)
        assertEquals("feature_disabled", diagnostics.last().third["reason"])

        // Unconditional cancel on release even when already unposted, and again when posted
        controller.release()
        assertEquals(5, platform.cancelCount)

        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Final")
        controller.release()
        assertEquals(6, platform.cancelCount)
        assertEquals("CANCELLED", diagnostics.last().second)
        assertEquals("released", diagnostics.last().third["reason"])
    }

    @Test
    fun `diagnostic callback executes outside controller lock allowing reentrant sync`() {
        val platform = FakeRecordingMirrorPlatform(permissionGranted = true)
        lateinit var controller: RecordingMirrorNotificationController
        var reentrantCompleted = false

        controller = RecordingMirrorNotificationController(platform) { _, outcome, _ ->
            if (outcome == "POSTED") {
                controller.sync(isRecording = false, featureEnabled = true, sessionTitle = null)
                reentrantCompleted = true
            }
        }

        controller.sync(isRecording = true, featureEnabled = true, sessionTitle = "Test")
        assertTrue(reentrantCompleted)
        assertEquals(1, platform.postedTitles.size)
        assertEquals(1, platform.cancelCount)
    }

    @Test
    fun `receiver cancels notification unconditionally and emits ACCEPTED or IGNORED diagnostics`() {
        val receiver = RecordingNotificationActionReceiver()
        val events = mutableListOf<Triple<String, String, Map<String, String>>>()
        RecordingNotificationActionReceiver.onDiagnosticEvent = { event, outcome, details ->
            events.add(Triple(event, outcome, details))
        }

        var cancelCalls = 0
        // Unrelated action is ignored without cancelling or invoking callback
        RecordingNotificationActionReceiver.onStopRecordingRequested = { true }
        receiver.handleAction("com.example.OTHER") { cancelCalls++ }
        assertEquals(0, cancelCalls)
        assertTrue(events.isEmpty())

        // Accepted when callback returns true
        receiver.handleAction(RecordingNotificationActionReceiver.ACTION_STOP_RECORDING_MIRROR) { cancelCalls++ }
        assertEquals(1, cancelCalls)
        assertEquals(1, events.size)
        assertEquals("ACCEPTED", events[0].second)
        assertTrue(events[0].third.isEmpty())

        // Ignored when callback returns false or is null, still cancels notification
        RecordingNotificationActionReceiver.onStopRecordingRequested = { false }
        receiver.handleAction(RecordingNotificationActionReceiver.ACTION_STOP_RECORDING_MIRROR) { cancelCalls++ }
        assertEquals(2, cancelCalls)
        assertEquals("IGNORED", events[1].second)
        assertEquals("not_recording_or_service_inactive", events[1].third["reason"])

        RecordingNotificationActionReceiver.onStopRecordingRequested = null
        receiver.handleAction(RecordingNotificationActionReceiver.ACTION_STOP_RECORDING_MIRROR) { cancelCalls++ }
        assertEquals(3, cancelCalls)
        assertEquals("IGNORED", events[2].second)
        assertEquals("not_recording_or_service_inactive", events[2].third["reason"])
    }
}
