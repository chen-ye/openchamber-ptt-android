package com.openchamber.ptt.diagnostics

enum class AudioDebugCategory(val label: String) {
    PTT_STATE("State"),
    MEDIA_BUTTON("MediaBtn"),
    VOLUME_KEY("VolumeKey"),
    AUDIO_ROUTE("Route"),
    BT_HEADSET("BT Headset"),
    TELECOM("Telecom"),
    AUDIO_FOCUS("Focus"),
    PLAYBACK_CONFIG("Playback"),
    RECORDING_CONFIG("Recording"),
    NOTIFICATION("Notif"),
    FOREGROUND_KEY("FgKey"),
}

enum class AudioDebugOutcome(val label: String) {
    ACCEPTED("Accepted"),
    IGNORED("Ignored"),
    DROPPED("Dropped"),
    STATE_CHANGE("State"),
    ERROR("Error"),
}

data class AudioDebugEntry(
    val seq: Long,
    val wallClockMs: Long,
    val elapsedMs: Long,
    val category: AudioDebugCategory,
    val event: String,
    val outcome: AudioDebugOutcome,
    val pttState: String = "",
    val details: Map<String, String> = emptyMap(),
)

data class AudioLiveStatusSnapshot(
    val pttState: String = "DISARMED",
    val captureTransport: String = "SYSTEM_DEFAULT",
    val audioMode: String = "MODE_NORMAL",
    val communicationDevice: String = "None",
    val isScoPinned: Boolean = false,
    val scoConfirmedActive: Boolean = false,
    val telecomState: String = "IDLE",
    val mediaSessionActive: Boolean = false,
    val mediaSessionPlaybackState: String = "NONE",
    val volumeHandlingMode: String = "LOCAL (STREAM_MUSIC)",
    val activeRecordingSummary: String = "Idle",
    val activePlaybackSummary: String = "Idle",
    val btHeadsetState: String = "Unknown",
)
