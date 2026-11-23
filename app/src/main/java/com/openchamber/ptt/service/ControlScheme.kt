package com.openchamber.ptt.service

import com.openchamber.ptt.audio.TelecomHangupAction

enum class BluetoothCaptureMode(
    val storageValue: String,
    val shortLabel: String,
    val description: String,
) {
    CLASSIC_SCO(
        storageValue = "classic_sco",
        shortLabel = "Classic SCO",
        description = "Pins Bluetooth SCO audio while recording; headset button triggers SCO drop.",
    ),
    TELECOM_HFP(
        storageValue = "telecom_hfp",
        shortLabel = "Call mode (HFP)",
        description = "Opens a temporary Telecom call during capture so headset hangup stops recording (Shokz / HFP).",
    ),
    ;

    companion object {
        val DEFAULT: BluetoothCaptureMode = CLASSIC_SCO

        fun fromStorageValue(raw: String?): BluetoothCaptureMode {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { it.storageValue.equals(cleaned, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}

enum class HeadsetTapMode(
    val storageValue: String,
    val shortLabel: String,
    val description: String,
) {
    MULTI_TAP(
        storageValue = "multi_tap",
        shortLabel = "Multi-tap (1x·2x·3x)",
        description = "Enables 2x and 3x button shortcuts (adds a 350ms wait on 1x taps where multi-tap applies).",
    ),
    SINGLE_ONLY(
        storageValue = "single_only",
        shortLabel = "Single-tap only",
        description = "Instant 1x response with zero delay — ideal for headsets like Shokz that swallow multi-taps.",
    ),
    ;

    companion object {
        val DEFAULT: HeadsetTapMode = MULTI_TAP

        fun fromStorageValue(raw: String?): HeadsetTapMode {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { it.storageValue.equals(cleaned, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}

enum class VolumeKeyRecordingMode(
    val storageValue: String,
    val shortLabel: String,
    val description: String,
) {
    OFF(
        storageValue = "off",
        shortLabel = "Off",
        description = "Volume keys control volume normally while recording.",
    ),
    ANY_SEND(
        storageValue = "any_send",
        shortLabel = "Any → Quick send",
        description = "Screen-off Volume Up or Down stops into 2.5s send grace (or appends if a draft is open).",
    ),
    ANY_STAGE(
        storageValue = "any_stage",
        shortLabel = "Any → Stage draft",
        description = "Screen-off Volume Up or Down stops recording and organizes into a staged draft.",
    ),
    SPLIT_SEND_STAGE(
        storageValue = "split_send_stage",
        shortLabel = "Up Send · Down Stage",
        description = "Screen-off Volume Up stops into 2.5s quick send; Volume Down stops and stages a draft.",
    ),
    ;

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        val DEFAULT: VolumeKeyRecordingMode = OFF

        fun fromStorageValue(raw: String?): VolumeKeyRecordingMode {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { it.storageValue.equals(cleaned, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}

enum class VolumeKeyDraftMode(
    val storageValue: String,
    val shortLabel: String,
    val description: String,
) {
    OFF(
        storageValue = "off",
        shortLabel = "Off",
        description = "Volume keys control volume normally during staged draft preview.",
    ),
    SEND_AND_DISCARD(
        storageValue = "send_and_discard",
        shortLabel = "Up Send · Down Discard",
        description = "Screen-off Volume Up sends the staged draft; Volume Down discards it.",
    ),
    SEND_ONLY(
        storageValue = "send_only",
        shortLabel = "Up Send only",
        description = "Screen-off Volume Up sends the staged draft; Volume Down is ignored so pocket bumps cannot discard.",
    ),
    ;

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        val DEFAULT: VolumeKeyDraftMode = OFF

        fun fromStorageValue(raw: String?): VolumeKeyDraftMode {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { it.storageValue.equals(cleaned, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
                ?: DEFAULT
        }
    }
}

enum class ControlPreset(
    val shortLabel: String,
    val description: String,
) {
    STANDARD(
        shortLabel = "Standard",
        description = "Classic SCO, multi-tap (1x/2x/3x), AVRCP Next/Prev on, volume keys off.",
    ),
    SHOKZ_SINGLE_TAP(
        shortLabel = "Shokz / Single-Tap",
        description = "Call mode (HFP) quick send, instant 1x tap, AVRCP off, screen-off volume keys for recording & drafts.",
    ),
    CUSTOM(
        shortLabel = "Custom",
        description = "Custom mix of headset button, call mode, and screen-off volume controls.",
    ),
}

data class ControlSchemeConfig(
    val pinBluetoothSco: Boolean = false,
    val bluetoothCaptureMode: BluetoothCaptureMode = BluetoothCaptureMode.DEFAULT,
    val telecomHangupAction: TelecomHangupAction = TelecomHangupAction.DEFAULT,
    val headsetTapMode: HeadsetTapMode = HeadsetTapMode.DEFAULT,
    val avrcpTrackControlsEnabled: Boolean = true,
    val volumeKeyRecordingMode: VolumeKeyRecordingMode = VolumeKeyRecordingMode.DEFAULT,
    val volumeKeyDraftMode: VolumeKeyDraftMode = VolumeKeyDraftMode.DEFAULT,
    val mirrorRecordingNotification: Boolean = true,
) {
    val isTelecomCallEnabled: Boolean
        get() = bluetoothCaptureMode == BluetoothCaptureMode.TELECOM_HFP

    val isTelecomActiveWithHeadsetMic: Boolean
        get() = pinBluetoothSco && isTelecomCallEnabled

    val resolvedPreset: ControlPreset
        get() = when {
            bluetoothCaptureMode == BluetoothCaptureMode.CLASSIC_SCO &&
                telecomHangupAction == TelecomHangupAction.QUICK_SEND &&
                headsetTapMode == HeadsetTapMode.MULTI_TAP &&
                avrcpTrackControlsEnabled &&
                volumeKeyRecordingMode == VolumeKeyRecordingMode.OFF &&
                volumeKeyDraftMode == VolumeKeyDraftMode.OFF -> ControlPreset.STANDARD

            bluetoothCaptureMode == BluetoothCaptureMode.TELECOM_HFP &&
                telecomHangupAction == TelecomHangupAction.QUICK_SEND &&
                headsetTapMode == HeadsetTapMode.SINGLE_ONLY &&
                !avrcpTrackControlsEnabled &&
                volumeKeyRecordingMode == VolumeKeyRecordingMode.SPLIT_SEND_STAGE &&
                volumeKeyDraftMode == VolumeKeyDraftMode.SEND_AND_DISCARD -> ControlPreset.SHOKZ_SINGLE_TAP

            else -> ControlPreset.CUSTOM
        }

    fun applyingPreset(preset: ControlPreset): ControlSchemeConfig = when (preset) {
        ControlPreset.STANDARD -> copy(
            bluetoothCaptureMode = BluetoothCaptureMode.CLASSIC_SCO,
            telecomHangupAction = TelecomHangupAction.QUICK_SEND,
            headsetTapMode = HeadsetTapMode.MULTI_TAP,
            avrcpTrackControlsEnabled = true,
            volumeKeyRecordingMode = VolumeKeyRecordingMode.OFF,
            volumeKeyDraftMode = VolumeKeyDraftMode.OFF,
        )

        ControlPreset.SHOKZ_SINGLE_TAP -> copy(
            bluetoothCaptureMode = BluetoothCaptureMode.TELECOM_HFP,
            telecomHangupAction = TelecomHangupAction.QUICK_SEND,
            headsetTapMode = HeadsetTapMode.SINGLE_ONLY,
            avrcpTrackControlsEnabled = false,
            volumeKeyRecordingMode = VolumeKeyRecordingMode.SPLIT_SEND_STAGE,
            volumeKeyDraftMode = VolumeKeyDraftMode.SEND_AND_DISCARD,
        )

        ControlPreset.CUSTOM -> this
    }
}

object ControlHintFormatter {
    fun formatCockpitHelper(
        config: ControlSchemeConfig,
        pttState: PttState,
        hasDraft: Boolean,
        statusText: String,
        pendingQuickTranscript: String?,
        canArm: Boolean,
    ): String = when (pttState) {
        PttState.DISARMED -> if (canArm) {
            "Starts foreground audio for screen-off & headset PTT"
        } else {
            "Select a pinned session above to arm"
        }

        PttState.ARMED_IDLE -> formatArmedIdleHint(config)
        PttState.RECORDING -> formatRecordingHint(config, hasDraft)
        PttState.TRANSCRIBING_SEND, PttState.TRANSCRIBING_STAGE, PttState.WAITING_AGENT -> statusText
        PttState.SEND_GRACE -> pendingQuickTranscript?.takeIf { it.isNotBlank() }
            ?: "Sending shortly — tap anywhere to pull into staged draft"
        PttState.STAGED_DRAFT -> formatStagedDraftHint(config, speaking = false)
        PttState.STAGED_DRAFT_SPEAKING -> formatStagedDraftHint(config, speaking = true)
        PttState.SPEAKING_TTS -> "Tap anytime to barge in and interrupt speech"
        PttState.PERMISSION_BLOCKED -> statusText
        PttState.ERROR -> statusText
    }

    fun formatNotificationStatus(
        config: ControlSchemeConfig,
        pttState: PttState,
    ): String? = when (pttState) {
        PttState.STAGED_DRAFT -> "Draft staged — ${formatStagedDraftShort(config)}"
        else -> null
    }

    fun formatGestureMapRows(config: ControlSchemeConfig): List<Pair<String, String>> = listOf(
        "Idle" to formatArmedIdleHint(config),
        "Recording" to formatRecordingHint(config, hasDraft = false),
        "Send grace" to "1x tap: undo to staged draft",
        "Staged draft" to formatStagedDraftHint(config, speaking = false),
    )

    private fun formatArmedIdleHint(config: ControlSchemeConfig): String {
        val primary = if (config.headsetTapMode == HeadsetTapMode.SINGLE_ONLY) {
            "1x tap: talk (instant single-tap)"
        } else {
            "1x tap: talk"
        }
        val parts = mutableListOf(primary)
        val readShortcut = when {
            config.headsetTapMode == HeadsetTapMode.MULTI_TAP && config.avrcpTrackControlsEnabled ->
                "2x/Next: read latest reply"
            config.headsetTapMode == HeadsetTapMode.MULTI_TAP ->
                "2x: read latest reply"
            config.avrcpTrackControlsEnabled ->
                "Next: read latest reply"
            else -> null
        }
        if (readShortcut != null) {
            parts += readShortcut
        }
        return parts.joinToString(" · ")
    }

    private fun formatRecordingHint(
        config: ControlSchemeConfig,
        hasDraft: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        val tapLabel = if (hasDraft) "1x: append draft" else "1x: quick send"
        parts += tapLabel

        if (config.headsetTapMode == HeadsetTapMode.MULTI_TAP) {
            if (config.avrcpTrackControlsEnabled) {
                parts += "2x/Next: stage"
                parts += "3x/Prev: discard"
            } else {
                parts += "2x: stage"
                parts += "3x: discard"
            }
        } else if (config.avrcpTrackControlsEnabled) {
            parts += "Next: stage"
            parts += "Prev: discard"
        }

        if (config.isTelecomActiveWithHeadsetMic) {
            val hangupLabel = when (config.telecomHangupAction) {
                TelecomHangupAction.QUICK_SEND -> if (hasDraft) "Hangup: append" else "Hangup: quick send"
                TelecomHangupAction.SEND_IMMEDIATELY -> if (hasDraft) "Hangup: append" else "Hangup: send now"
                TelecomHangupAction.STAGE_DRAFT -> "Hangup: stage"
            }
            parts += hangupLabel
        }

        val volLabel = when (config.volumeKeyRecordingMode) {
            VolumeKeyRecordingMode.OFF -> null
            VolumeKeyRecordingMode.ANY_SEND -> if (hasDraft) "Vol (off): append" else "Vol (off): quick send"
            VolumeKeyRecordingMode.ANY_STAGE -> "Vol (off): stage"
            VolumeKeyRecordingMode.SPLIT_SEND_STAGE -> if (hasDraft) {
                "Vol+ (off): append · Vol-: stage"
            } else {
                "Vol+ (off): send · Vol-: stage"
            }
        }
        if (volLabel != null) {
            parts += volLabel
        }

        return parts.joinToString(" · ")
    }

    private fun formatStagedDraftHint(
        config: ControlSchemeConfig,
        speaking: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        parts += if (speaking) "1x: pause preview" else "1x: append voice"

        if (config.headsetTapMode == HeadsetTapMode.MULTI_TAP) {
            if (config.avrcpTrackControlsEnabled) {
                parts += if (speaking) "2x/Next: send now" else "2x/Next: send draft"
                parts += "3x/Prev: discard"
            } else {
                parts += if (speaking) "2x: send now" else "2x: send draft"
                parts += "3x: discard"
            }
        } else if (config.avrcpTrackControlsEnabled) {
            parts += if (speaking) "Next: send now" else "Next: send draft"
            parts += "Prev: discard"
        }

        val volLabel = when (config.volumeKeyDraftMode) {
            VolumeKeyDraftMode.OFF -> null
            VolumeKeyDraftMode.SEND_AND_DISCARD -> "Vol+ (off): send · Vol-: discard"
            VolumeKeyDraftMode.SEND_ONLY -> "Vol+ (off): send"
        }
        if (volLabel != null) {
            parts += volLabel
        }

        return parts.joinToString(" · ")
    }

    private fun formatStagedDraftShort(config: ControlSchemeConfig): String {
        val parts = mutableListOf("1x append")
        if (config.headsetTapMode == HeadsetTapMode.MULTI_TAP) {
            parts += "2x send"
        } else if (config.avrcpTrackControlsEnabled) {
            parts += "Next send"
        }
        when (config.volumeKeyDraftMode) {
            VolumeKeyDraftMode.OFF -> Unit
            VolumeKeyDraftMode.SEND_AND_DISCARD -> parts += "Vol+ send / Vol- discard"
            VolumeKeyDraftMode.SEND_ONLY -> parts += "Vol+ send"
        }
        return parts.joinToString(" · ")
    }
}
