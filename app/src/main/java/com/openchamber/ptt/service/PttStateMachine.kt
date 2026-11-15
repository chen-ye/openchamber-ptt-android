package com.openchamber.ptt.service

import com.openchamber.ptt.audio.Earcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PttState {
    DISARMED,
    ARMED_IDLE,
    RECORDING,
    TRANSCRIBING,
    WAITING_AGENT,
    SPEAKING_TTS,
    PERMISSION_BLOCKED,
    ERROR,
}

sealed interface PttEvent {
    data object Arm : PttEvent
    data object Disarm : PttEvent
    data object MediaButtonTap : PttEvent
    data class TranscriptReady(val text: String? = null) : PttEvent
    data class AgentCompleted(val hasTts: Boolean = true) : PttEvent
    data class PermissionAsked(val reason: String? = null) : PttEvent
    data object PermissionResolved : PttEvent
    data object PlayLastReply : PttEvent
    data object TtsFinished : PttEvent
    data class Error(val message: String? = null) : PttEvent
}

sealed interface PttEffect {
    data class PlayEarcon(val earcon: Earcon) : PttEffect
    data object AcquireWakeAndWifiLocks : PttEffect
    data object ReleaseWakeAndWifiLocks : PttEffect
    data object PinBluetoothAudio : PttEffect
    data object ReleaseBluetoothAudio : PttEffect
    data object StartRecording : PttEffect
    data object StopRecording : PttEffect
    data object StopTts : PttEffect
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

    private var returnStateAfterTts: PttState = PttState.ARMED_IDLE

    val currentState: PttState
        get() = _state.value

    fun dispatch(event: PttEvent): PttTransition {
        synchronized(lock) {
            val previous = _state.value
            val (next, effects) = computeTransition(previous, event)

            when (event) {
                is PttEvent.Error -> {
                    if (next == PttState.ERROR) {
                        _lastError.value = event.message
                    }
                }
                is PttEvent.PermissionAsked -> {
                    if (next == PttState.PERMISSION_BLOCKED) {
                        _permissionPrompt.value = event.reason
                    }
                }
                is PttEvent.Arm, is PttEvent.Disarm -> {
                    _lastError.value = null
                    _permissionPrompt.value = null
                    returnStateAfterTts = PttState.ARMED_IDLE
                }
                is PttEvent.AgentCompleted -> {
                    if (previous == PttState.PERMISSION_BLOCKED) {
                        _permissionPrompt.value = null
                    }
                    if (next == PttState.SPEAKING_TTS) {
                        returnStateAfterTts = PttState.ARMED_IDLE
                    }
                }
                is PttEvent.PermissionResolved -> {
                    if (previous == PttState.PERMISSION_BLOCKED) {
                        _permissionPrompt.value = null
                    }
                }
                PttEvent.PlayLastReply -> {
                    if (next == PttState.SPEAKING_TTS) {
                        returnStateAfterTts = if (previous == PttState.DISARMED) {
                            PttState.DISARMED
                        } else {
                            PttState.ARMED_IDLE
                        }
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
        PttEvent.Arm -> when (current) {
            PttState.DISARMED,
            PttState.ERROR,
            PttState.PERMISSION_BLOCKED,
            -> PttState.ARMED_IDLE to listOf(PttEffect.PlayEarcon(Earcon.ARMED))

            PttState.ARMED_IDLE,
            PttState.RECORDING,
            PttState.TRANSCRIBING,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            -> current to emptyList()
        }

        PttEvent.Disarm -> when (current) {
            PttState.DISARMED -> PttState.DISARMED to emptyList()
            else -> PttState.DISARMED to buildList {
                if (current == PttState.RECORDING) add(PttEffect.StopRecording)
                if (current == PttState.SPEAKING_TTS) add(PttEffect.StopTts)
                add(PttEffect.ReleaseWakeAndWifiLocks)
                add(PttEffect.ReleaseBluetoothAudio)
                add(PttEffect.PlayEarcon(Earcon.DISARMED))
            }
        }

        PttEvent.MediaButtonTap -> when (current) {
            PttState.ARMED_IDLE -> PttState.RECORDING to listOf(
                PttEffect.PinBluetoothAudio,
                PttEffect.AcquireWakeAndWifiLocks,
                PttEffect.PlayEarcon(Earcon.REC_START),
                PttEffect.StartRecording,
            )

            PttState.RECORDING -> PttState.TRANSCRIBING to listOf(
                PttEffect.StopRecording,
                PttEffect.PlayEarcon(Earcon.REC_SENT),
            )

            PttState.SPEAKING_TTS -> returnStateAfterTts to listOf(
                PttEffect.StopTts,
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
            )

            PttState.DISARMED,
            PttState.TRANSCRIBING,
            PttState.WAITING_AGENT,
            PttState.PERMISSION_BLOCKED,
            PttState.ERROR,
            -> current to emptyList()
        }

        is PttEvent.TranscriptReady -> when (current) {
            PttState.TRANSCRIBING -> {
                if (event.text != null && event.text.isBlank()) {
                    PttState.ARMED_IDLE to listOf(
                        PttEffect.ReleaseWakeAndWifiLocks,
                        PttEffect.ReleaseBluetoothAudio,
                    )
                } else {
                    PttState.WAITING_AGENT to emptyList()
                }
            }
            else -> current to emptyList()
        }

        is PttEvent.AgentCompleted -> when (current) {
            PttState.WAITING_AGENT,
            PttState.PERMISSION_BLOCKED,
            -> {
                if (event.hasTts) {
                    PttState.SPEAKING_TTS to emptyList()
                } else {
                    PttState.ARMED_IDLE to listOf(
                        PttEffect.ReleaseWakeAndWifiLocks,
                        PttEffect.ReleaseBluetoothAudio,
                    )
                }
            }
            else -> current to emptyList()
        }

        is PttEvent.PermissionAsked -> when (current) {
            PttState.ARMED_IDLE,
            PttState.RECORDING,
            PttState.TRANSCRIBING,
            PttState.WAITING_AGENT,
            PttState.SPEAKING_TTS,
            -> PttState.PERMISSION_BLOCKED to buildList {
                if (current == PttState.RECORDING) add(PttEffect.StopRecording)
                if (current == PttState.SPEAKING_TTS) add(PttEffect.StopTts)
                add(PttEffect.ReleaseWakeAndWifiLocks)
                add(PttEffect.ReleaseBluetoothAudio)
                add(PttEffect.PlayEarcon(Earcon.PERMISSION_NEEDED))
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
            -> PttState.SPEAKING_TTS to listOf(
                PttEffect.AcquireWakeAndWifiLocks,
            )
            else -> current to emptyList()
        }

        PttEvent.TtsFinished -> when (current) {
            PttState.SPEAKING_TTS -> returnStateAfterTts to listOf(
                PttEffect.ReleaseWakeAndWifiLocks,
                PttEffect.ReleaseBluetoothAudio,
            )
            else -> current to emptyList()
        }

        is PttEvent.Error -> when (current) {
            PttState.DISARMED -> PttState.DISARMED to emptyList()
            else -> PttState.ERROR to buildList {
                if (current == PttState.RECORDING) add(PttEffect.StopRecording)
                if (current == PttState.SPEAKING_TTS) add(PttEffect.StopTts)
                add(PttEffect.ReleaseWakeAndWifiLocks)
                add(PttEffect.ReleaseBluetoothAudio)
                add(PttEffect.PlayEarcon(Earcon.ERROR))
            }
        }
    }
}
