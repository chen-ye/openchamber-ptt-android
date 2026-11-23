package com.openchamber.ptt.service

import com.openchamber.ptt.audio.CueEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PttState {
    DISARMED,
    ARMED_IDLE,
    RECORDING,
    TRANSCRIBING_SEND,
    TRANSCRIBING_STAGE,
    SEND_GRACE,
    STAGED_DRAFT,
    STAGED_DRAFT_SPEAKING,
    WAITING_AGENT,
    SPEAKING_TTS,
    PERMISSION_BLOCKED,
    ERROR,
    ;

    val isTranscribing: Boolean
        get() = this == TRANSCRIBING_SEND || this == TRANSCRIBING_STAGE

    val isBusyWithTurn: Boolean
        get() = this == RECORDING ||
            this == TRANSCRIBING_SEND ||
            this == TRANSCRIBING_STAGE ||
            this == SEND_GRACE ||
            this == WAITING_AGENT ||
            this == SPEAKING_TTS ||
            this == STAGED_DRAFT_SPEAKING ||
            this == PERMISSION_BLOCKED
}

sealed interface PttEvent {
    data class Arm(val hasPersistedDraft: Boolean = false) : PttEvent
    data object Disarm : PttEvent
    data object SingleTap : PttEvent
    data object DoubleTap : PttEvent
    data object TripleTap : PttEvent
    data object StageCapture : PttEvent
    data object DiscardCapture : PttEvent
    data object CommitDraft : PttEvent
    data object PullBackToDraft : PttEvent
    data object GraceTimerExpired : PttEvent
    data class SyncDraftPresence(val hasPersistedDraft: Boolean) : PttEvent
    data class TranscriptReady(val text: String? = null) : PttEvent
    data object DraftPreviewReady : PttEvent
    data class AgentCompleted(val hasTts: Boolean = true) : PttEvent
    data object AgentInterrupted : PttEvent
    data class PermissionAsked(val reason: String? = null) : PttEvent
    data object PermissionResolved : PttEvent
    data object PlayLastReply : PttEvent
    data object PlayQueuedReply : PttEvent
    data object TtsFinished : PttEvent
    data object PlaybackInterruptedByTransport : PttEvent
    data class Error(val message: String? = null, val isAuthExpired: Boolean = false) : PttEvent
}

sealed interface PttEffect {
    data class PlayCue(val cue: CueEvent) : PttEffect
    data object AcquireWakeAndWifiLocks : PttEffect
    data object ReleaseWakeAndWifiLocks : PttEffect
    data object AcquireCaptureAudio : PttEffect
    data object ReleaseCaptureAudio : PttEffect
    data object AcquirePlaybackFocus : PttEffect
    data object ReleasePlaybackFocus : PttEffect
    data object StartRecording : PttEffect
    data object StopRecording : PttEffect
    data object DiscardRecording : PttEffect
    data object StopTts : PttEffect
    data object StartSendGraceTimer : PttEffect
    data object CancelSendGraceTimer : PttEffect
    data class OrganizeAndSpeakDraft(val burst: String) : PttEffect
    data class DispatchPrompt(val fromStagedDraft: Boolean) : PttEffect
    data object ReadLastReply : PttEffect
    data object SpeakPendingReplies : PttEffect
    data object ClearDraft : PttEffect
}

data class PttTransition(
    val previous: PttState,
    val current: PttState,
    val event: PttEvent,
    val effects: List<PttEffect>,
) {
    val changed: Boolean get() = previous != current
}

class PttStateMachine(
    initialState: PttState = PttState.DISARMED,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<PttState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _permissionPrompt = MutableStateFlow<String?>(null)
    val permissionPrompt: StateFlow<String?> = _permissionPrompt.asStateFlow()

    private var hasDraft: Boolean = false
    private var speechReturnToDisarmed: Boolean = false

    val currentState: PttState
        get() = _state.value

    private fun idleState(): PttState = if (hasDraft) PttState.STAGED_DRAFT else PttState.ARMED_IDLE

    private fun postTtsState(): PttState = if (speechReturnToDisarmed) PttState.DISARMED else idleState()

    fun dispatch(event: PttEvent): PttTransition {
        synchronized(lock) {
            val previous = _state.value
            val (next, effects) = computeTransition(previous, event)

            when (event) {
                is PttEvent.Error -> {
                    if (next == PttState.ERROR || (previous == PttState.SPEAKING_TTS && speechReturnToDisarmed)) {
                        _lastError.value = event.message
                    }
                    hasDraft = false
                    speechReturnToDisarmed = false
                }
                is PttEvent.PermissionAsked -> {
                    if (next == PttState.PERMISSION_BLOCKED) {
                        _permissionPrompt.value = event.reason
                    }
                }
                is PttEvent.Arm -> {
                    _lastError.value = null
                    _permissionPrompt.value = null
                    hasDraft = event.hasPersistedDraft
                    speechReturnToDisarmed = false
                }
                PttEvent.Disarm -> {
                    _lastError.value = null
                    _permissionPrompt.value = null
                    hasDraft = false
                    speechReturnToDisarmed = false
                }
                PttEvent.SingleTap -> {
                    if (previous == PttState.SEND_GRACE && next == PttState.STAGED_DRAFT) {
                        hasDraft = true
                    } else if (previous == PttState.SPEAKING_TTS && next != previous) {
                        speechReturnToDisarmed = false
                    }
                }
                PttEvent.DoubleTap,
                PttEvent.PullBackToDraft,
                -> {
                    if (previous == PttState.ARMED_IDLE && next == PttState.SPEAKING_TTS) {
                        speechReturnToDisarmed = false
                    }
                    if (previous == PttState.SEND_GRACE && next == PttState.STAGED_DRAFT) {
                        hasDraft = true
                    }
                    if (next == PttState.WAITING_AGENT && (previous == PttState.STAGED_DRAFT || previous == PttState.STAGED_DRAFT_SPEAKING)) {
                        hasDraft = false
                    }
                }
                PttEvent.CommitDraft -> {
                    if (next == PttState.WAITING_AGENT) {
                        hasDraft = false
                    }
                }
                PttEvent.DraftPreviewReady -> {
                    if (next == PttState.STAGED_DRAFT_SPEAKING) {
                        hasDraft = true
                    }
                }
                PttEvent.TripleTap,
                PttEvent.DiscardCapture,
                -> {
                    if (previous == PttState.STAGED_DRAFT || previous == PttState.STAGED_DRAFT_SPEAKING) {
                        if (next == PttState.ARMED_IDLE) {
                            hasDraft = false
                        }
                    }
                }
                is PttEvent.SyncDraftPresence -> {
                    hasDraft = event.hasPersistedDraft
                }
                is PttEvent.AgentCompleted -> {
                    if (previous == PttState.PERMISSION_BLOCKED) {
                        _permissionPrompt.value = null
                    }
                    if (next == PttState.SPEAKING_TTS) {
                        speechReturnToDisarmed = false
                    }
                }
                PttEvent.AgentInterrupted -> {
                    _permissionPrompt.value = null
                }
                PttEvent.PermissionResolved -> {
                    if (previous == PttState.PERMISSION_BLOCKED) {
                        _permissionPrompt.value = null
                    }
                }
                PttEvent.PlayLastReply -> {
                    if (next == PttState.SPEAKING_TTS) {
                        speechReturnToDisarmed = previous == PttState.DISARMED
                    }
                }
                PttEvent.PlayQueuedReply -> {
                    if (next == PttState.SPEAKING_TTS) {
                        speechReturnToDisarmed = false
                    }
                }
                PttEvent.TtsFinished,
                PttEvent.PlaybackInterruptedByTransport,
                -> {
                    if (previous == PttState.SPEAKING_TTS) {
                        speechReturnToDisarmed = false
                    }
                }
                else -> Unit
            }

            if (next != previous) {
                _state.value = next
            }

            return PttTransition(
                previous = previous,
                current = next,
                event = event,
                effects = effects,
            )
        }
    }

    private fun computeTransition(
        current: PttState,
        event: PttEvent,
    ): Pair<PttState, List<PttEffect>> = when (event) {
        is PttEvent.Arm -> when (current) {
            PttState.DISARMED,
            PttState.ERROR,
            PttState.PERMISSION_BLOCKED,
            -> {
                val target = if (event.hasPersistedDraft) PttState.STAGED_DRAFT else PttState.ARMED_IDLE
                target to listOf(
                    PttEffect.PlayCue(if (event.hasPersistedDraft) CueEvent.ARMED_WITH_DRAFT else CueEvent.ARMED),
                )
            }

            else -> current to emptyList()
        }

        PttEvent.Disarm -> when (current) {
            PttState.DISARMED -> PttState.DISARMED to emptyList()
            else -> PttState.DISARMED to buildList {
                if (current == PttState.RECORDING) {
                    add(PttEffect.StopRecording)
                    add(PttEffect.ReleaseCaptureAudio)
                }
                if (current == PttState.SPEAKING_TTS || current == PttState.STAGED_DRAFT_SPEAKING) {
                    add(PttEffect.StopTts)
                    add(PttEffect.ReleasePlaybackFocus)
                }
                if (current == PttState.SEND_GRACE) add(PttEffect.CancelSendGraceTimer)
                add(PttEffect.ReleaseWakeAndWifiLocks)
                add(PttEffect.PlayCue(CueEvent.DISARMED))
            }
        }

        PttEvent.SingleTap -> when (current) {
            PttState.ARMED_IDLE,
            PttState.STAGED_DRAFT,
            -> PttState.RECORDING to listOf(
                PttEffect.AcquireCaptureAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.REC_START),
                PttEffect.StartRecording,
            )

            PttState.RECORDING -> if (hasDraft) {
                PttState.TRANSCRIBING_STAGE to listOf(
                    PttEffect.StopRecording,
                    PttEffect.ReleaseCaptureAudio,
                    PttEffect.PlayCue(CueEvent.STAGE_APPENDING),
                )
            } else {
                PttState.TRANSCRIBING_SEND to listOf(
                    PttEffect.StopRecording,
                    PttEffect.ReleaseCaptureAudio,
                    PttEffect.PlayCue(CueEvent.QUICK_STOP_ACK),
                )
            }

            PttState.SEND_GRACE -> PttState.STAGED_DRAFT to listOf(
                PttEffect.CancelSendGraceTimer,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.UNDO_TO_DRAFT),
            )

            PttState.STAGED_DRAFT_SPEAKING -> PttState.STAGED_DRAFT to listOf(
                PttEffect.StopTts,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
            )

            PttState.SPEAKING_TTS -> postTtsState() to listOf(
                PttEffect.StopTts,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
            )

            PttState.DISARMED,
            PttState.TRANSCRIBING_SEND,
            PttState.TRANSCRIBING_STAGE,
            PttState.WAITING_AGENT,
            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            -> current to emptyList()
        }

        PttEvent.DoubleTap -> when (current) {
            PttState.ARMED_IDLE -> PttState.SPEAKING_TTS to listOf(
                PttEffect.AcquirePlaybackFocus,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.ReadLastReply,
            )

            PttState.RECORDING -> PttState.TRANSCRIBING_STAGE to listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseCaptureAudio,
                PttEffect.PlayCue(if (hasDraft) CueEvent.STAGE_APPENDING else CueEvent.STAGE_ORGANIZING),
            )

            PttState.SEND_GRACE -> PttState.STAGED_DRAFT to listOf(
                PttEffect.CancelSendGraceTimer,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.UNDO_TO_DRAFT),
            )

            PttState.STAGED_DRAFT -> PttState.WAITING_AGENT to listOf(
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = true),
            )

            PttState.STAGED_DRAFT_SPEAKING -> PttState.WAITING_AGENT to listOf(
                PttEffect.StopTts,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = true),
            )

            else -> current to emptyList()
        }

        PttEvent.TripleTap,
        PttEvent.DiscardCapture,
        -> when (current) {
            PttState.RECORDING -> idleState() to listOf(
                PttEffect.StopRecording,
                PttEffect.DiscardRecording,
                PttEffect.ReleaseCaptureAudio,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.CANCEL_DISCARD),
            )

            PttState.SEND_GRACE -> idleState() to listOf(
                PttEffect.CancelSendGraceTimer,
                PttEffect.DiscardRecording,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.CANCEL_DISCARD),
            )

            PttState.STAGED_DRAFT -> PttState.ARMED_IDLE to listOf(
                PttEffect.ClearDraft,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.CANCEL_DISCARD),
            )

            PttState.STAGED_DRAFT_SPEAKING -> PttState.ARMED_IDLE to listOf(
                PttEffect.StopTts,
                PttEffect.ClearDraft,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.CANCEL_DISCARD),
            )

            else -> current to emptyList()
        }

        PttEvent.StageCapture -> when (current) {
            PttState.RECORDING -> PttState.TRANSCRIBING_STAGE to listOf(
                PttEffect.StopRecording,
                PttEffect.ReleaseCaptureAudio,
                PttEffect.PlayCue(if (hasDraft) CueEvent.STAGE_APPENDING else CueEvent.STAGE_ORGANIZING),
            )
            else -> current to emptyList()
        }

        PttEvent.CommitDraft -> when (current) {
            PttState.STAGED_DRAFT -> PttState.WAITING_AGENT to listOf(
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = true),
            )

            PttState.STAGED_DRAFT_SPEAKING -> PttState.WAITING_AGENT to listOf(
                PttEffect.StopTts,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = true),
            )

            else -> current to emptyList()
        }

        PttEvent.PullBackToDraft -> when (current) {
            PttState.SEND_GRACE -> PttState.STAGED_DRAFT to listOf(
                PttEffect.CancelSendGraceTimer,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.UNDO_TO_DRAFT),
            )
            else -> current to emptyList()
        }

        PttEvent.GraceTimerExpired -> when (current) {
            PttState.SEND_GRACE -> PttState.WAITING_AGENT to listOf(
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.PROMPT_SENT),
                PttEffect.DispatchPrompt(fromStagedDraft = false),
            )
            else -> current to emptyList()
        }

        is PttEvent.SyncDraftPresence -> when (current) {
            PttState.ARMED_IDLE -> if (event.hasPersistedDraft) {
                PttState.STAGED_DRAFT to emptyList()
            } else {
                current to emptyList()
            }
            PttState.STAGED_DRAFT -> if (!event.hasPersistedDraft) {
                PttState.ARMED_IDLE to emptyList()
            } else {
                current to emptyList()
            }
            PttState.STAGED_DRAFT_SPEAKING -> if (!event.hasPersistedDraft) {
                PttState.ARMED_IDLE to listOf(
                    PttEffect.StopTts,
                    PttEffect.ReleasePlaybackFocus,
                    PttEffect.ReleaseWakeAndWifiLocks,
                )
            } else {
                current to emptyList()
            }
            else -> current to emptyList()
        }

        is PttEvent.TranscriptReady -> when (current) {
            PttState.TRANSCRIBING_SEND -> {
                if (event.text.isNullOrBlank()) {
                    idleState() to listOf(
                        PttEffect.ReleaseWakeAndWifiLocks,
                        PttEffect.PlayCue(CueEvent.EMPTY_SPEECH),
                    )
                } else {
                    PttState.SEND_GRACE to listOf(
                        PttEffect.PlayCue(CueEvent.SEND_GRACE),
                        PttEffect.StartSendGraceTimer,
                    )
                }
            }

            PttState.TRANSCRIBING_STAGE -> {
                val burst = event.text?.trim().orEmpty()
                if (burst.isEmpty()) {
                    idleState() to listOf(
                        PttEffect.ReleaseWakeAndWifiLocks,
                        PttEffect.PlayCue(CueEvent.EMPTY_SPEECH),
                    )
                } else {
                    PttState.TRANSCRIBING_STAGE to listOf(
                        PttEffect.OrganizeAndSpeakDraft(burst),
                    )
                }
            }

            else -> current to emptyList()
        }

        PttEvent.DraftPreviewReady -> when (current) {
            PttState.TRANSCRIBING_STAGE -> PttState.STAGED_DRAFT_SPEAKING to listOf(
                PttEffect.AcquirePlaybackFocus,
                PttEffect.AcquireWakeAndWifiLocks,
            )
            else -> current to emptyList()
        }

        is PttEvent.AgentCompleted -> when (current) {
            PttState.WAITING_AGENT,
            PttState.PERMISSION_BLOCKED,
            -> {
                if (event.hasTts) {
                    PttState.SPEAKING_TTS to listOf(
                        PttEffect.AcquirePlaybackFocus,
                        PttEffect.AcquireWakeAndWifiLocks,
                        PttEffect.PlayCue(CueEvent.TURN_COMPLETE),
                    )
                } else {
                    idleState() to listOf(
                        PttEffect.ReleaseWakeAndWifiLocks,
                        PttEffect.PlayCue(CueEvent.TURN_COMPLETE_NO_TTS),
                    )
                }
            }
            else -> current to emptyList()
        }

        PttEvent.AgentInterrupted -> when (current) {
            PttState.WAITING_AGENT,
            PttState.PERMISSION_BLOCKED,
            -> idleState() to listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.TURN_INTERRUPTED),
            )
            else -> current to emptyList()
        }

        is PttEvent.PermissionAsked -> when (current) {
            PttState.ARMED_IDLE,
            PttState.STAGED_DRAFT,
            PttState.RECORDING,
            PttState.TRANSCRIBING_SEND,
            PttState.TRANSCRIBING_STAGE,
            PttState.SEND_GRACE,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            PttState.STAGED_DRAFT_SPEAKING,
            -> PttState.PERMISSION_BLOCKED to buildList {
                if (current == PttState.RECORDING) {
                    add(PttEffect.StopRecording)
                    add(PttEffect.ReleaseCaptureAudio)
                }
                if (current == PttState.SEND_GRACE) add(PttEffect.CancelSendGraceTimer)
                if (current == PttState.SPEAKING_TTS || current == PttState.STAGED_DRAFT_SPEAKING) {
                    add(PttEffect.StopTts)
                    add(PttEffect.ReleasePlaybackFocus)
                }
                add(PttEffect.ReleaseWakeAndWifiLocks)
                add(PttEffect.PlayCue(CueEvent.PERMISSION_NEEDED))
            }

            PttState.DISARMED,
            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            -> current to emptyList()
        }

        PttEvent.PermissionResolved -> when (current) {
            PttState.PERMISSION_BLOCKED -> PttState.WAITING_AGENT to listOf(
                PttEffect.AcquireWakeAndWifiLocks,
            )
            else -> current to emptyList()
        }

        PttEvent.PlayLastReply -> when (current) {
            PttState.DISARMED,
            PttState.ARMED_IDLE,
            PttState.STAGED_DRAFT,
            -> PttState.SPEAKING_TTS to listOf(
                PttEffect.AcquirePlaybackFocus,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.ReadLastReply,
            )
            else -> current to emptyList()
        }

        PttEvent.PlayQueuedReply -> when (current) {
            PttState.ARMED_IDLE,
            PttState.STAGED_DRAFT,
            -> PttState.SPEAKING_TTS to listOf(
                PttEffect.AcquirePlaybackFocus,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayCue(CueEvent.TURN_COMPLETE),
                PttEffect.SpeakPendingReplies,
            )
            else -> current to emptyList()
        }

        PttEvent.TtsFinished -> when (current) {
            PttState.SPEAKING_TTS -> postTtsState() to listOf(
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
            )
            PttState.STAGED_DRAFT_SPEAKING -> PttState.STAGED_DRAFT to listOf(
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
            )
            else -> current to emptyList()
        }

        PttEvent.PlaybackInterruptedByTransport -> when (current) {
            PttState.SPEAKING_TTS -> postTtsState() to listOf(
                PttEffect.StopTts,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
            )
            PttState.STAGED_DRAFT_SPEAKING -> PttState.STAGED_DRAFT to listOf(
                PttEffect.StopTts,
                PttEffect.ReleasePlaybackFocus,
                PttEffect.ReleaseWakeAndWifiLocks,
            )
            else -> current to emptyList()
        }

        is PttEvent.Error -> when (current) {
            PttState.DISARMED -> PttState.DISARMED to emptyList()
            PttState.SPEAKING_TTS -> {
                val targetState = if (speechReturnToDisarmed) PttState.DISARMED else PttState.ERROR
                targetState to listOf(
                    PttEffect.StopTts,
                    PttEffect.ReleasePlaybackFocus,
                    PttEffect.ReleaseWakeAndWifiLocks,
                    PttEffect.PlayCue(if (event.isAuthExpired) CueEvent.AUTH_EXPIRED else CueEvent.ERROR),
                )
            }
            else -> PttState.ERROR to buildList {
                if (current == PttState.RECORDING) {
                    add(PttEffect.StopRecording)
                    add(PttEffect.ReleaseCaptureAudio)
                }
                if (current == PttState.SEND_GRACE) add(PttEffect.CancelSendGraceTimer)
                if (current == PttState.STAGED_DRAFT_SPEAKING) {
                    add(PttEffect.StopTts)
                    add(PttEffect.ReleasePlaybackFocus)
                }
                add(PttEffect.ReleaseWakeAndWifiLocks)
                add(PttEffect.PlayCue(if (event.isAuthExpired) CueEvent.AUTH_EXPIRED else CueEvent.ERROR))
            }
        }
    }
}
