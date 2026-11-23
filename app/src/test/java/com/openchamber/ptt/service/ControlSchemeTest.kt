package com.openchamber.ptt.service

import com.openchamber.ptt.audio.TelecomHangupAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSchemeTest {
    @Test
    fun `presets resolve STANDARD, SHOKZ_SINGLE_TAP, and CUSTOM while preserving mic pin and watch mirror`() {
        val base = ControlSchemeConfig()
        assertEquals(ControlPreset.STANDARD, base.resolvedPreset)

        val shokz = base
            .copy(pinBluetoothSco = true, mirrorRecordingNotification = false)
            .applyingPreset(ControlPreset.SHOKZ_SINGLE_TAP)
        assertEquals(ControlPreset.SHOKZ_SINGLE_TAP, shokz.resolvedPreset)
        assertTrue(shokz.pinBluetoothSco)
        assertFalse(shokz.mirrorRecordingNotification)
        assertEquals(BluetoothCaptureMode.TELECOM_HFP, shokz.bluetoothCaptureMode)
        assertEquals(TelecomHangupAction.QUICK_SEND, shokz.telecomHangupAction)
        assertEquals(HeadsetTapMode.SINGLE_ONLY, shokz.headsetTapMode)
        assertFalse(shokz.avrcpTrackControlsEnabled)
        assertEquals(VolumeKeyRecordingMode.SPLIT_SEND_STAGE, shokz.volumeKeyRecordingMode)
        assertEquals(VolumeKeyDraftMode.SEND_AND_DISCARD, shokz.volumeKeyDraftMode)

        val custom = shokz.copy(telecomHangupAction = TelecomHangupAction.STAGE_DRAFT)
        assertEquals(ControlPreset.CUSTOM, custom.resolvedPreset)

        val restoredStandard = custom.applyingPreset(ControlPreset.STANDARD)
        assertEquals(ControlPreset.STANDARD, restoredStandard.resolvedPreset)
        assertTrue(restoredStandard.pinBluetoothSco)
        assertFalse(restoredStandard.mirrorRecordingNotification)
    }

    @Test
    fun `storage enum parsers match values case-insensitively and fall back to defaults`() {
        assertEquals(BluetoothCaptureMode.TELECOM_HFP, BluetoothCaptureMode.fromStorageValue("TELECOM_HFP"))
        assertEquals(BluetoothCaptureMode.CLASSIC_SCO, BluetoothCaptureMode.fromStorageValue("unknown"))
        assertEquals(HeadsetTapMode.SINGLE_ONLY, HeadsetTapMode.fromStorageValue("single_only"))
        assertEquals(HeadsetTapMode.MULTI_TAP, HeadsetTapMode.fromStorageValue(null))
        assertEquals(VolumeKeyRecordingMode.SPLIT_SEND_STAGE, VolumeKeyRecordingMode.fromStorageValue("split_send_stage"))
        assertEquals(VolumeKeyRecordingMode.OFF, VolumeKeyRecordingMode.fromStorageValue("nope"))
        assertEquals(VolumeKeyDraftMode.SEND_ONLY, VolumeKeyDraftMode.fromStorageValue("send_only"))
        assertEquals(VolumeKeyDraftMode.OFF, VolumeKeyDraftMode.fromStorageValue(""))
    }

    @Test
    fun `ControlHintFormatter dynamically adapts hints for Shokz single-tap, volume keys, and Telecom hangup`() {
        val shokz = ControlSchemeConfig(
            pinBluetoothSco = true,
            bluetoothCaptureMode = BluetoothCaptureMode.TELECOM_HFP,
            telecomHangupAction = TelecomHangupAction.SEND_IMMEDIATELY,
            headsetTapMode = HeadsetTapMode.SINGLE_ONLY,
            avrcpTrackControlsEnabled = false,
            volumeKeyRecordingMode = VolumeKeyRecordingMode.SPLIT_SEND_STAGE,
            volumeKeyDraftMode = VolumeKeyDraftMode.SEND_AND_DISCARD,
        )

        val idleHint = ControlHintFormatter.formatCockpitHelper(
            config = shokz,
            pttState = PttState.ARMED_IDLE,
            hasDraft = false,
            statusText = "",
            pendingQuickTranscript = null,
            canArm = true,
        )
        assertTrue(idleHint.contains("instant single-tap"))
        assertFalse(idleHint.contains("2x"))
        assertFalse(idleHint.contains("Next"))

        val recHint = ControlHintFormatter.formatCockpitHelper(
            config = shokz,
            pttState = PttState.RECORDING,
            hasDraft = false,
            statusText = "",
            pendingQuickTranscript = null,
            canArm = true,
        )
        assertTrue(recHint.contains("Hangup: send now"))
        assertTrue(recHint.contains("Vol+ (off): send · Vol-: stage"))
        assertFalse(recHint.contains("2x"))
        assertFalse(recHint.contains("Next"))

        val draftHint = ControlHintFormatter.formatCockpitHelper(
            config = shokz,
            pttState = PttState.STAGED_DRAFT,
            hasDraft = true,
            statusText = "",
            pendingQuickTranscript = null,
            canArm = true,
        )
        assertTrue(draftHint.contains("1x: append voice"))
        assertTrue(draftHint.contains("Vol+ (off): send · Vol-: discard"))
        assertFalse(draftHint.contains("2x"))

        val notifDraft = ControlHintFormatter.formatNotificationStatus(shokz, PttState.STAGED_DRAFT)
        assertEquals("Draft staged — 1x append · Vol+ send / Vol- discard", notifDraft)
        assertNull(ControlHintFormatter.formatNotificationStatus(shokz, PttState.RECORDING))

        val mapRows = ControlHintFormatter.formatGestureMapRows(shokz)
        assertEquals(4, mapRows.size)
        assertEquals(listOf("Idle", "Recording", "Send grace", "Staged draft"), mapRows.map { it.first })
    }
}
