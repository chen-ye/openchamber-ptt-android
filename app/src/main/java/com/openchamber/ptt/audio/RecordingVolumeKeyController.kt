package com.openchamber.ptt.audio

import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.VolumeProviderCompat
import com.openchamber.ptt.service.VolumeKeyDraftMode
import com.openchamber.ptt.service.VolumeKeyRecordingMode

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

enum class VolumeKeyEpisode {
    NONE,
    RECORDING,
    DRAFT,
}

enum class VolumeKeyAction {
    STOP_QUICK_SEND,
    STOP_STAGE_DRAFT,
    COMMIT_DRAFT,
    DISCARD_DRAFT,
}

class RecordingVolumeKeyController internal constructor(
    private val adapter: MediaSessionVolumeAdapter,
    private val onVolumeAction: (action: VolumeKeyAction, direction: Int) -> Unit,
) {
    constructor(
        mediaSession: MediaSessionCompat,
        onVolumeAction: (action: VolumeKeyAction, direction: Int) -> Unit,
    ) : this(
        adapter = AndroidMediaSessionVolumeAdapter(mediaSession),
        onVolumeAction = onVolumeAction,
    )

    internal constructor(
        adapter: MediaSessionVolumeAdapter,
        onVolumeKeyStop: (direction: Int) -> Unit,
        legacyCallbackMarker: Unit = Unit,
    ) : this(
        adapter = adapter,
        onVolumeAction = { _, direction -> onVolumeKeyStop(direction) },
    )

    private val stateLock = Any()
    private val adapterLock = Any()

    private var currentEpisode: VolumeKeyEpisode = VolumeKeyEpisode.NONE
    private var currentRecordingMode: VolumeKeyRecordingMode = VolumeKeyRecordingMode.OFF
    private var currentDraftMode: VolumeKeyDraftMode = VolumeKeyDraftMode.OFF
    private var attached: Boolean = false
    private var triggeredInCurrentEpisode: Boolean = false
    private var currentAttachEpoch: Long = 0L
    private var nextOpSeq: Long = 0L
    private var lastAppliedOpSeq: Long = 0L

    @Volatile
    var onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null

    val isAttached: Boolean
        get() = synchronized(stateLock) { attached }

    val activeEpisode: VolumeKeyEpisode
        get() = synchronized(stateLock) { currentEpisode }

    fun sync(
        isRecording: Boolean,
        featureEnabled: Boolean,
        isScreenOff: Boolean = true,
    ) {
        sync(
            episode = if (isRecording) VolumeKeyEpisode.RECORDING else VolumeKeyEpisode.NONE,
            isScreenOff = isScreenOff,
            recordingMode = if (featureEnabled) VolumeKeyRecordingMode.ANY_SEND else VolumeKeyRecordingMode.OFF,
            draftMode = VolumeKeyDraftMode.OFF,
        )
    }

    fun sync(
        episode: VolumeKeyEpisode,
        isScreenOff: Boolean,
        recordingMode: VolumeKeyRecordingMode,
        draftMode: VolumeKeyDraftMode,
    ) {
        var actionOpSeq: Long? = null
        var actionAttach = false
        var actionEpoch = 0L
        var diagOutcome: String? = null
        var diagDetails: Map<String, String> = emptyMap()

        synchronized(stateLock) {
            val episodeChanged = episode != currentEpisode
            currentEpisode = episode
            currentRecordingMode = recordingMode
            currentDraftMode = draftMode
            if (episode == VolumeKeyEpisode.NONE || episodeChanged) {
                triggeredInCurrentEpisode = false
            }

            val modeEnabled = when (episode) {
                VolumeKeyEpisode.NONE -> false
                VolumeKeyEpisode.RECORDING -> recordingMode.isEnabled
                VolumeKeyEpisode.DRAFT -> draftMode.isEnabled
            }
            val shouldBeAttached = episode != VolumeKeyEpisode.NONE &&
                isScreenOff &&
                modeEnabled &&
                !triggeredInCurrentEpisode

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
                    "episode" to episode.name,
                    "reason" to when (episode) {
                        VolumeKeyEpisode.RECORDING -> "attached_for_recording_screen_off"
                        VolumeKeyEpisode.DRAFT -> "attached_for_draft_screen_off"
                        VolumeKeyEpisode.NONE -> "attached"
                    },
                )
            } else if (!shouldBeAttached && attached) {
                attached = false
                actionEpoch = currentAttachEpoch
                actionAttach = false
                actionOpSeq = ++nextOpSeq
                val reason = when {
                    episode == VolumeKeyEpisode.NONE -> "episode_exited"
                    !isScreenOff -> "screen_on"
                    !modeEnabled -> "feature_disabled"
                    else -> "already_triggered"
                }
                diagOutcome = "STATE_CHANGE"
                diagDetails = mapOf(
                    "attached" to "false",
                    "attachEpoch" to actionEpoch.toString(),
                    "episode" to episode.name,
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
            currentEpisode = VolumeKeyEpisode.NONE
            triggeredInCurrentEpisode = false
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
        var resolvedAction: VolumeKeyAction? = null
        var episodeSnapshot = VolumeKeyEpisode.NONE
        synchronized(stateLock) {
            episodeSnapshot = currentEpisode
            when {
                direction == 0 -> {
                    ignoreReason = "action_up_zero"
                    restoreOpSeq = null
                }
                triggeredInCurrentEpisode && attachEpoch == currentAttachEpoch -> {
                    ignoreReason = "already_triggered"
                    restoreOpSeq = null
                }
                !attached || attachEpoch != currentAttachEpoch -> {
                    ignoreReason = "not_attached"
                    restoreOpSeq = null
                }
                else -> {
                    val candidate = resolveAction(
                        episode = currentEpisode,
                        recordingMode = currentRecordingMode,
                        draftMode = currentDraftMode,
                        direction = direction,
                    )
                    if (candidate == null) {
                        ignoreReason = "unmapped_direction"
                        restoreOpSeq = null
                    } else {
                        resolvedAction = candidate
                        ignoreReason = null
                        triggeredInCurrentEpisode = true
                        attached = false
                        restoreOpSeq = ++nextOpSeq
                    }
                }
            }
        }

        if (ignoreReason != null) {
            onDiagnosticEvent?.invoke(
                "onAdjustVolume",
                "IGNORED",
                mapOf(
                    "reason" to ignoreReason,
                    "direction" to direction.toString(),
                    "episode" to episodeSnapshot.name,
                    "attachEpoch" to attachEpoch.toString(),
                ),
            )
            return
        }

        applyAdapterAction(opSeq = restoreOpSeq!!, shouldAttach = false, attachEpoch = attachEpoch)
        val action = resolvedAction!!
        onDiagnosticEvent?.invoke(
            "onAdjustVolume",
            "ACCEPTED",
            mapOf(
                "action" to action.name,
                "direction" to direction.toString(),
                "episode" to episodeSnapshot.name,
                "attachEpoch" to attachEpoch.toString(),
            ),
        )
        onVolumeAction(action, direction)
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

    companion object {
        internal fun resolveAction(
            episode: VolumeKeyEpisode,
            recordingMode: VolumeKeyRecordingMode,
            draftMode: VolumeKeyDraftMode,
            direction: Int,
        ): VolumeKeyAction? {
            if (direction == 0) return null
            return when (episode) {
                VolumeKeyEpisode.NONE -> null
                VolumeKeyEpisode.RECORDING -> when (recordingMode) {
                    VolumeKeyRecordingMode.OFF -> null
                    VolumeKeyRecordingMode.ANY_SEND -> VolumeKeyAction.STOP_QUICK_SEND
                    VolumeKeyRecordingMode.ANY_STAGE -> VolumeKeyAction.STOP_STAGE_DRAFT
                    VolumeKeyRecordingMode.SPLIT_SEND_STAGE -> if (direction > 0) {
                        VolumeKeyAction.STOP_QUICK_SEND
                    } else {
                        VolumeKeyAction.STOP_STAGE_DRAFT
                    }
                }
                VolumeKeyEpisode.DRAFT -> when (draftMode) {
                    VolumeKeyDraftMode.OFF -> null
                    VolumeKeyDraftMode.SEND_AND_DISCARD -> if (direction > 0) {
                        VolumeKeyAction.COMMIT_DRAFT
                    } else {
                        VolumeKeyAction.DISCARD_DRAFT
                    }
                    VolumeKeyDraftMode.SEND_ONLY -> if (direction > 0) {
                        VolumeKeyAction.COMMIT_DRAFT
                    } else {
                        null
                    }
                }
            }
        }
    }
}
