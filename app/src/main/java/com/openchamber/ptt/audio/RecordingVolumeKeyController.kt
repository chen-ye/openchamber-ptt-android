package com.openchamber.ptt.audio

import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.VolumeProviderCompat

internal interface MediaSessionVolumeAdapter {
    fun attachRelativeVolumeProvider(onAdjust: (direction: Int) -> Unit)
    fun restoreLocalStreamMusic()
}

private class AndroidMediaSessionVolumeAdapter(
    private val mediaSession: MediaSessionCompat,
) : MediaSessionVolumeAdapter {
    override fun attachRelativeVolumeProvider(onAdjust: (direction: Int) -> Unit) {
        val provider = object : VolumeProviderCompat(VOLUME_CONTROL_RELATIVE, 100, 50) {
            override fun onAdjustVolume(direction: Int) {
                onAdjust(direction)
            }
        }
        mediaSession.setPlaybackToRemote(provider)
    }

    override fun restoreLocalStreamMusic() {
        mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC)
    }
}

class RecordingVolumeKeyController internal constructor(
    private val adapter: MediaSessionVolumeAdapter,
    private val onVolumeKeyStop: (direction: Int) -> Unit,
) {
    constructor(
        mediaSession: MediaSessionCompat,
        onVolumeKeyStop: (direction: Int) -> Unit,
    ) : this(
        adapter = AndroidMediaSessionVolumeAdapter(mediaSession),
        onVolumeKeyStop = onVolumeKeyStop,
    )

    private val stateLock = Any()
    private val adapterLock = Any()

    private var wasRecording: Boolean = false
    private var attached: Boolean = false
    private var triggeredInCurrentRecording: Boolean = false
    private var currentAttachEpoch: Long = 0L
    private var nextOpSeq: Long = 0L
    private var lastAppliedOpSeq: Long = 0L

    @Volatile
    var onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null

    val isAttached: Boolean
        get() = synchronized(stateLock) { attached }

    fun sync(
        isRecording: Boolean,
        featureEnabled: Boolean,
    ) {
        var actionOpSeq: Long? = null
        var actionAttach = false
        var actionEpoch = 0L
        var diagOutcome: String? = null
        var diagDetails: Map<String, String> = emptyMap()

        synchronized(stateLock) {
            val freshEntry = isRecording && !wasRecording
            wasRecording = isRecording
            if (!isRecording || freshEntry) {
                triggeredInCurrentRecording = false
            }

            val shouldBeAttached = isRecording && featureEnabled && !triggeredInCurrentRecording
            if (shouldBeAttached && !attached) {
                attached = true
                currentAttachEpoch++
                actionEpoch = currentAttachEpoch
                actionAttach = true
                actionOpSeq = ++nextOpSeq
                diagOutcome = "STATE_CHANGE"
                diagDetails = mapOf(
                    "attached" to "true",
                    "attachEpoch" to actionEpoch.toString(),
                    "reason" to "attached_for_recording",
                )
            } else if (!shouldBeAttached && attached) {
                attached = false
                actionEpoch = currentAttachEpoch
                actionAttach = false
                actionOpSeq = ++nextOpSeq
                val reason = when {
                    !isRecording -> "recording_stopped"
                    !featureEnabled -> "feature_disabled"
                    else -> "already_triggered"
                }
                diagOutcome = "STATE_CHANGE"
                diagDetails = mapOf(
                    "attached" to "false",
                    "attachEpoch" to actionEpoch.toString(),
                    "reason" to reason,
                )
            }
        }

        val seq = actionOpSeq ?: return
        applyAdapterAction(opSeq = seq, shouldAttach = actionAttach, attachEpoch = actionEpoch)
        if (diagOutcome != null) {
            onDiagnosticEvent?.invoke("sync", diagOutcome, diagDetails)
        }
    }

    fun release() {
        val actionOpSeq: Long?
        synchronized(stateLock) {
            val wasAttached = attached
            wasRecording = false
            triggeredInCurrentRecording = false
            attached = false
            actionOpSeq = if (wasAttached) ++nextOpSeq else null
        }

        if (actionOpSeq != null) {
            applyAdapterAction(opSeq = actionOpSeq, shouldAttach = false, attachEpoch = 0L)
            onDiagnosticEvent?.invoke(
                "release",
                "STATE_CHANGE",
                mapOf(
                    "attached" to "false",
                    "reason" to "released",
                ),
            )
        }
    }

    private fun handleVolumeAdjust(
        direction: Int,
        attachEpoch: Long,
    ) {
        val ignoreReason: String?
        val restoreOpSeq: Long?
        synchronized(stateLock) {
            ignoreReason = when {
                direction == 0 -> "action_up_zero"
                triggeredInCurrentRecording && attachEpoch == currentAttachEpoch -> "already_triggered"
                !attached || attachEpoch != currentAttachEpoch -> "not_attached"
                else -> null
            }
            if (ignoreReason != null) {
                restoreOpSeq = null
            } else {
                triggeredInCurrentRecording = true
                attached = false
                restoreOpSeq = ++nextOpSeq
            }
        }

        if (ignoreReason != null) {
            onDiagnosticEvent?.invoke(
                "onAdjustVolume",
                "IGNORED",
                mapOf(
                    "reason" to ignoreReason,
                    "direction" to direction.toString(),
                    "attachEpoch" to attachEpoch.toString(),
                ),
            )
            return
        }

        applyAdapterAction(opSeq = restoreOpSeq!!, shouldAttach = false, attachEpoch = attachEpoch)
        onDiagnosticEvent?.invoke(
            "onAdjustVolume",
            "ACCEPTED",
            mapOf(
                "direction" to direction.toString(),
                "attachEpoch" to attachEpoch.toString(),
            ),
        )
        onVolumeKeyStop(direction)
    }

    private fun applyAdapterAction(
        opSeq: Long,
        shouldAttach: Boolean,
        attachEpoch: Long,
    ) {
        synchronized(adapterLock) {
            if (opSeq < lastAppliedOpSeq) return
            lastAppliedOpSeq = opSeq
            if (shouldAttach) {
                adapter.attachRelativeVolumeProvider { direction ->
                    handleVolumeAdjust(direction, attachEpoch)
                }
            } else {
                adapter.restoreLocalStreamMusic()
            }
        }
    }
}
