package com.openchamber.ptt.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

enum class ScoDropSource {
    COMMUNICATION_DEVICE,
    SCO_AUDIO_BROADCAST,
}

data class ScoDropEvent(
    val source: ScoDropSource,
    val btScoStillAvailable: Boolean,
    val interruptedByFocusOrCall: Boolean,
    val pinGeneration: Long = 0L,
)

internal interface AudioRoutingPlatform {
    val sdkInt: Int
    fun getMode(): Int?
    fun setMode(mode: Int)
    fun requestTransientFocus(forCommunication: Boolean, onAudioFocusChange: (Int) -> Unit): Boolean
    fun abandonTransientFocus()
    fun isScoAvailable(): Boolean
    fun getCurrentCommunicationDeviceType(): Int?
    fun pinSco(): Boolean
    fun clearSco()
}

private class AndroidAudioRoutingPlatform(
    private val audioManager: AudioManager,
) : AudioRoutingPlatform {
    private var activeFocusRequest: AudioFocusRequest? = null

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun getMode(): Int? = runCatching { audioManager.mode }.getOrNull()

    override fun setMode(mode: Int) {
        runCatching { audioManager.mode = mode }
    }

    override fun requestTransientFocus(
        forCommunication: Boolean,
        onAudioFocusChange: (Int) -> Unit,
    ): Boolean {
        abandonTransientFocus()
        val attributes = AudioAttributes.Builder()
            .setUsage(
                if (forCommunication) {
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                } else {
                    AudioAttributes.USAGE_MEDIA
                },
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { focusChange ->
                onAudioFocusChange(focusChange)
            }
            .build()

        val result = runCatching {
            audioManager.requestAudioFocus(focusRequest)
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeFocusRequest = focusRequest
            true
        } else {
            false
        }
    }

    override fun abandonTransientFocus() {
        val current = activeFocusRequest ?: return
        activeFocusRequest = null
        runCatching {
            audioManager.abandonAudioFocusRequest(current)
        }
    }

    override fun isScoAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return runCatching {
                audioManager.availableCommunicationDevices.any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrDefault(false)
        }
        return runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }.getOrDefault(false)
    }

    override fun getCurrentCommunicationDeviceType(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching { audioManager.communicationDevice?.type }.getOrNull()
    }

    override fun pinSco(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scoDevice = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrNull() ?: return false

            return runCatching {
                audioManager.setCommunicationDevice(scoDevice)
            }.getOrDefault(false)
        }

        if (!isScoAvailable()) return false

        @Suppress("DEPRECATION")
        return runCatching {
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
            true
        }.getOrDefault(false)
    }

    override fun clearSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
    }
}

class BluetoothAudioRouter internal constructor(
    private val appContext: Context?,
    private val audioManager: AudioManager?,
    private val platform: AudioRoutingPlatform,
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    constructor(audioManager: AudioManager) : this(
        appContext = null,
        audioManager = audioManager,
        platform = AndroidAudioRoutingPlatform(audioManager),
    )

    constructor(context: Context) : this(
        appContext = context.applicationContext,
        audioManager = context.applicationContext.getSystemService(AudioManager::class.java),
        platform = AndroidAudioRoutingPlatform(
            context.applicationContext.getSystemService(AudioManager::class.java),
        ),
    )

    private enum class FocusKind {
        NONE,
        CAPTURE,
        PLAYBACK,
    }

    private val lock = Any()
    private var previousAudioMode: Int? = null
    private var legacyScoStarted: Boolean = false
    private var communicationDevicePinned: Boolean = false
    private var scoPlatformClearNeeded: Boolean = false
    private var scoConfirmedActive: Boolean = false
    private var scoConfirmedFromEvent: Boolean = false
    private var dropReportedForCurrentPin: Boolean = false
    private var pinnedAtElapsedMs: Long = 0L
    private var pinGeneration: Long = 0L
    private var focusGeneration: Long = 0L
    private var audioFocusInterrupted: Boolean = false
    private var telecomManagedCapture: Boolean = false
    private var activeFocusKind: FocusKind = FocusKind.NONE

    @Volatile
    private var onScoDroppedListener: ((ScoDropEvent) -> Unit)? = null
    @Volatile
    var onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null
    private var monitorsRegistered: Boolean = false
    private var scoStateReceiver: BroadcastReceiver? = null
    private var commDeviceObserverHandle: Any? = null

    val isScoPinned: Boolean
        get() = synchronized(lock) { !telecomManagedCapture && (communicationDevicePinned || legacyScoStarted) }

    fun acquireCaptureAudio(
        pinSco: Boolean,
        telecomManaged: Boolean = false,
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean {
        val result: Boolean
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            pinGeneration++
            if (telecomManaged) {
                val clearedLingeringSco = hasLingeringScoStateLocked()
                focusGeneration++
                telecomManagedCapture = true
                audioFocusInterrupted = false
                if (clearedLingeringSco) {
                    clearScoRoutingLocked()
                }
                if (activeFocusKind != FocusKind.NONE) {
                    activeFocusKind = FocusKind.NONE
                    platform.abandonTransientFocus()
                }
                result = true
                diagnostic = DiagnosticSnapshot(
                    event = "acquireCaptureAudio",
                    outcome = "ACCEPTED",
                    details = mapOf(
                        "pinSco" to pinSco.toString(),
                        "telecomManaged" to "true",
                        "clearedLingeringSco" to clearedLingeringSco.toString(),
                        "pinGeneration" to pinGeneration.toString(),
                        "focusGeneration" to focusGeneration.toString(),
                        "isScoPinned" to "false",
                    ),
                )
            } else {
                telecomManagedCapture = false
                val focusGranted = requestTransientFocusLocked(
                    forCommunication = pinSco,
                    kind = FocusKind.CAPTURE,
                    onAudioFocusChange = onAudioFocusChange,
                )
                audioFocusInterrupted = !focusGranted
                result = if (pinSco) {
                    pinBluetoothScoLocked()
                } else {
                    clearScoRoutingLocked()
                    true
                }
                diagnostic = DiagnosticSnapshot(
                    event = "acquireCaptureAudio",
                    outcome = if (result) "ACCEPTED" else "ERROR",
                    details = buildMap {
                        put("pinSco", pinSco.toString())
                        put("telecomManaged", "false")
                        put("focusGranted", focusGranted.toString())
                        put("pinGeneration", pinGeneration.toString())
                        put("focusGeneration", focusGeneration.toString())
                        put("isScoPinned", (communicationDevicePinned || legacyScoStarted).toString())
                        if (!result) {
                            put("reason", "pin_sco_failed")
                        }
                    },
                )
            }
        }
        emitDiagnostic(diagnostic)
        return result
    }

    fun releaseCaptureAudio() {
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            val wasTelecom = telecomManagedCapture
            val hadSco = hasLingeringScoStateLocked()
            val hadCaptureFocus = activeFocusKind == FocusKind.CAPTURE
            pinGeneration++
            telecomManagedCapture = false
            clearScoRoutingLocked()
            if (hadCaptureFocus) {
                focusGeneration++
                audioFocusInterrupted = false
                activeFocusKind = FocusKind.NONE
                platform.abandonTransientFocus()
            }
            val changed = wasTelecom || hadSco || hadCaptureFocus
            diagnostic = DiagnosticSnapshot(
                event = "releaseCaptureAudio",
                outcome = if (changed) "STATE_CHANGE" else "IGNORED",
                details = buildMap {
                    put("wasTelecomManaged", wasTelecom.toString())
                    put("hadSco", hadSco.toString())
                    put("hadCaptureFocus", hadCaptureFocus.toString())
                    put("pinGeneration", pinGeneration.toString())
                    put("focusGeneration", focusGeneration.toString())
                    if (!changed) {
                        put("reason", "not_active")
                    }
                },
            )
        }
        emitDiagnostic(diagnostic)
    }

    fun acquirePlaybackFocus(
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean {
        val focusGranted: Boolean
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            val clearedLingeringSco = hasLingeringScoStateLocked()
            pinGeneration++
            telecomManagedCapture = false
            clearScoRoutingLocked()
            if (platform.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                platform.setMode(AudioManager.MODE_NORMAL)
            }
            focusGranted = requestTransientFocusLocked(
                forCommunication = false,
                kind = FocusKind.PLAYBACK,
                onAudioFocusChange = onAudioFocusChange,
            )
            audioFocusInterrupted = !focusGranted
            diagnostic = DiagnosticSnapshot(
                event = "acquirePlaybackFocus",
                outcome = if (focusGranted) "ACCEPTED" else "ERROR",
                details = buildMap {
                    put("forCommunication", "false")
                    put("focusGranted", focusGranted.toString())
                    put("clearedLingeringSco", clearedLingeringSco.toString())
                    put("audioMode", (platform.getMode()?.toString() ?: "null"))
                    put("focusGeneration", focusGeneration.toString())
                    put("isScoPinned", "false")
                    if (!focusGranted) {
                        put("reason", "audio_focus_denied")
                    }
                },
            )
        }
        emitDiagnostic(diagnostic)
        return focusGranted
    }

    fun releasePlaybackFocus() {
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            val clearedLingeringSco = hasLingeringScoStateLocked()
            if (clearedLingeringSco) {
                pinGeneration++
                clearScoRoutingLocked()
            }
            val hadPlaybackFocus = activeFocusKind == FocusKind.PLAYBACK
            if (hadPlaybackFocus) {
                focusGeneration++
                audioFocusInterrupted = false
                activeFocusKind = FocusKind.NONE
                platform.abandonTransientFocus()
            }
            val changed = hadPlaybackFocus || clearedLingeringSco
            diagnostic = DiagnosticSnapshot(
                event = "releasePlaybackFocus",
                outcome = if (changed) "STATE_CHANGE" else "IGNORED",
                details = buildMap {
                    put("hadPlaybackFocus", hadPlaybackFocus.toString())
                    put("clearedLingeringSco", clearedLingeringSco.toString())
                    put("activeFocusKind", activeFocusKind.name)
                    put("focusGeneration", focusGeneration.toString())
                    if (!changed) {
                        put("reason", "playback_focus_not_active")
                    }
                },
            )
        }
        emitDiagnostic(diagnostic)
    }

    fun configureForPtt(
        pinSco: Boolean,
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean = acquireCaptureAudio(
        pinSco = pinSco,
        telecomManaged = false,
        onAudioFocusChange = onAudioFocusChange,
    )

    fun requestTransientFocus(
        forCommunication: Boolean = true,
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean {
        val granted: Boolean
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            granted = requestTransientFocusLocked(
                forCommunication = forCommunication,
                kind = if (forCommunication) FocusKind.CAPTURE else FocusKind.PLAYBACK,
                onAudioFocusChange = onAudioFocusChange,
            )
            diagnostic = DiagnosticSnapshot(
                event = "requestTransientFocus",
                outcome = if (granted) "ACCEPTED" else "ERROR",
                details = mapOf(
                    "forCommunication" to forCommunication.toString(),
                    "focusGranted" to granted.toString(),
                    "focusGeneration" to focusGeneration.toString(),
                ),
            )
        }
        emitDiagnostic(diagnostic)
        return granted
    }

    fun isBluetoothScoAvailable(): Boolean = platform.isScoAvailable()

    fun release() {
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            val hadSco = hasLingeringScoStateLocked()
            val wasTelecom = telecomManagedCapture
            releaseLocked()
            diagnostic = DiagnosticSnapshot(
                event = "release",
                outcome = "STATE_CHANGE",
                details = mapOf(
                    "hadSco" to hadSco.toString(),
                    "wasTelecomManaged" to wasTelecom.toString(),
                    "pinGeneration" to pinGeneration.toString(),
                    "focusGeneration" to focusGeneration.toString(),
                ),
            )
        }
        emitDiagnostic(diagnostic)
    }

    fun releaseIfGeneration(generation: Long) {
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            if (pinGeneration != generation) {
                diagnostic = DiagnosticSnapshot(
                    event = "releaseIfGeneration",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "stale_pin_generation",
                        "requestedGeneration" to generation.toString(),
                        "currentGeneration" to pinGeneration.toString(),
                    ),
                )
            } else {
                releaseLocked()
                diagnostic = DiagnosticSnapshot(
                    event = "releaseIfGeneration",
                    outcome = "STATE_CHANGE",
                    details = mapOf(
                        "requestedGeneration" to generation.toString(),
                        "pinGeneration" to pinGeneration.toString(),
                        "focusGeneration" to focusGeneration.toString(),
                    ),
                )
            }
        }
        emitDiagnostic(diagnostic)
    }

    internal fun isCurrentPinGeneration(generation: Long): Boolean =
        synchronized(lock) { pinGeneration == generation }

    private fun releaseLocked() {
        pinGeneration++
        focusGeneration++
        telecomManagedCapture = false
        audioFocusInterrupted = false
        activeFocusKind = FocusKind.NONE
        clearScoRoutingLocked()
        platform.abandonTransientFocus()
    }

    fun startMonitoring(onScoDropped: (ScoDropEvent) -> Unit) {
        val ctx = appContext ?: run {
            onScoDroppedListener = onScoDropped
            return
        }
        synchronized(lock) {
            onScoDroppedListener = onScoDropped
            if (monitorsRegistered) return

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                    val prevState = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR,
                    )
                    handleScoAudioStateUpdated(
                        state = state,
                        previousState = prevState,
                        isInitialSticky = isInitialStickyBroadcast,
                    )
                }
            }
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            scoStateReceiver = receiver
            monitorsRegistered = true

            val mgr = audioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mgr != null) {
                commDeviceObserverHandle = runCatching {
                    Api31CommDeviceObserver.register(
                        audioManager = mgr,
                        executor = ContextCompat.getMainExecutor(ctx),
                    ) { deviceType ->
                        handleCommunicationDeviceChanged(deviceType)
                    }
                }.getOrNull()
            }
        }
    }

    fun stopMonitoring() {
        val ctx = appContext
        val receiverToUnregister: BroadcastReceiver?
        val commHandle: Any?
        synchronized(lock) {
            onScoDroppedListener = null
            if (!monitorsRegistered) return
            monitorsRegistered = false
            receiverToUnregister = scoStateReceiver
            scoStateReceiver = null
            commHandle = commDeviceObserverHandle
            commDeviceObserverHandle = null
        }

        if (ctx != null && receiverToUnregister != null) {
            runCatching { ctx.unregisterReceiver(receiverToUnregister) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioManager != null && commHandle != null) {
            Api31CommDeviceObserver.unregister(audioManager, commHandle)
        }
    }

    internal fun handleAudioFocusChange(
        focusChange: Int,
        expectedFocusGeneration: Long? = null,
    ) {
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            if (expectedFocusGeneration != null && expectedFocusGeneration != focusGeneration) {
                diagnostic = DiagnosticSnapshot(
                    event = "onAudioFocusChange",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "stale_focus_generation",
                        "focusChange" to focusChangeName(focusChange),
                        "expectedGeneration" to expectedFocusGeneration.toString(),
                        "currentGeneration" to focusGeneration.toString(),
                    ),
                )
            } else {
                val previousInterrupted = audioFocusInterrupted
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    -> audioFocusInterrupted = true

                    AudioManager.AUDIOFOCUS_GAIN -> audioFocusInterrupted = false
                }
                diagnostic = DiagnosticSnapshot(
                    event = "onAudioFocusChange",
                    outcome = "STATE_CHANGE",
                    details = mapOf(
                        "focusChange" to focusChangeName(focusChange),
                        "interrupted" to audioFocusInterrupted.toString(),
                        "previousInterrupted" to previousInterrupted.toString(),
                        "focusKind" to activeFocusKind.name,
                        "focusGeneration" to focusGeneration.toString(),
                    ),
                )
            }
        }
        emitDiagnostic(diagnostic)
    }

    internal fun handleCommunicationDeviceChanged(deviceType: Int?): ScoDropEvent? {
        var eventToEmit: ScoDropEvent? = null
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            if (telecomManagedCapture) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "telecom_managed",
                        "deviceType" to (deviceType?.toString() ?: "null"),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else if (!communicationDevicePinned) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "sco_not_pinned",
                        "deviceType" to (deviceType?.toString() ?: "null"),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                scoConfirmedActive = true
                scoConfirmedFromEvent = true
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "STATE_CHANGE",
                    details = mapOf(
                        "reason" to "sco_confirmed",
                        "deviceType" to deviceType.toString(),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else if (dropReportedForCurrentPin) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "drop_already_reported",
                        "deviceType" to (deviceType?.toString() ?: "null"),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else if (!scoConfirmedActive) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "sco_never_confirmed",
                        "deviceType" to (deviceType?.toString() ?: "null"),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else if (!scoConfirmedFromEvent && isWithinSettleWindowLocked()) {
                // Previous teardown was still settling when we re-pinned; wait for fresh confirmation.
                scoConfirmedActive = false
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "within_settle_window",
                        "deviceType" to (deviceType?.toString() ?: "null"),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else {
                dropReportedForCurrentPin = true
                val btAvailable = platform.isScoAvailable()
                val mode = platform.getMode()
                val focusInterrupted = audioFocusInterrupted
                val callMode = isCallAudioMode(mode)
                val interrupted = focusInterrupted || callMode
                val generation = pinGeneration
                clearActiveScoFlagsLocked()
                val drop = ScoDropEvent(
                    source = ScoDropSource.COMMUNICATION_DEVICE,
                    btScoStillAvailable = btAvailable,
                    interruptedByFocusOrCall = interrupted,
                    pinGeneration = generation,
                )
                eventToEmit = drop
                val dropReason = when {
                    focusInterrupted -> "interrupted_by_focus"
                    callMode -> "interrupted_by_call"
                    !btAvailable -> "bt_sco_unavailable"
                    else -> "sco_dropped"
                }
                diagnostic = DiagnosticSnapshot(
                    event = "handleCommunicationDeviceChanged",
                    outcome = "DROPPED",
                    details = mapOf(
                        "reason" to dropReason,
                        "source" to drop.source.name,
                        "deviceType" to (deviceType?.toString() ?: "null"),
                        "btScoStillAvailable" to btAvailable.toString(),
                        "interruptedByFocusOrCall" to interrupted.toString(),
                        "pinGeneration" to generation.toString(),
                    ),
                )
            }
        }
        emitDiagnostic(diagnostic)
        if (eventToEmit != null) {
            onScoDroppedListener?.invoke(eventToEmit)
        }
        return eventToEmit
    }

    internal fun handleScoAudioStateUpdated(
        state: Int,
        previousState: Int,
        isInitialSticky: Boolean,
    ): ScoDropEvent? {
        var eventToEmit: ScoDropEvent? = null
        val diagnostic: DiagnosticSnapshot
        synchronized(lock) {
            if (isInitialSticky) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleScoAudioStateUpdated",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "sticky_broadcast",
                        "state" to state.toString(),
                        "previousState" to previousState.toString(),
                    ),
                )
            } else if (telecomManagedCapture) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleScoAudioStateUpdated",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "telecom_managed",
                        "state" to state.toString(),
                        "previousState" to previousState.toString(),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else if (!(communicationDevicePinned || legacyScoStarted)) {
                diagnostic = DiagnosticSnapshot(
                    event = "handleScoAudioStateUpdated",
                    outcome = "IGNORED",
                    details = mapOf(
                        "reason" to "sco_not_pinned",
                        "state" to state.toString(),
                        "previousState" to previousState.toString(),
                        "pinGeneration" to pinGeneration.toString(),
                    ),
                )
            } else {
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        scoConfirmedActive = true
                        scoConfirmedFromEvent = true
                        diagnostic = DiagnosticSnapshot(
                            event = "handleScoAudioStateUpdated",
                            outcome = "STATE_CHANGE",
                            details = mapOf(
                                "reason" to "sco_confirmed",
                                "state" to state.toString(),
                                "previousState" to previousState.toString(),
                                "pinGeneration" to pinGeneration.toString(),
                            ),
                        )
                    }

                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        if (previousState == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                            // Initial SCO handshake failed before establishing an active audio link.
                            scoConfirmedActive = false
                            scoConfirmedFromEvent = false
                            diagnostic = DiagnosticSnapshot(
                                event = "handleScoAudioStateUpdated",
                                outcome = "IGNORED",
                                details = mapOf(
                                    "reason" to "handshake_failed_connecting",
                                    "state" to state.toString(),
                                    "previousState" to previousState.toString(),
                                    "pinGeneration" to pinGeneration.toString(),
                                ),
                            )
                        } else if (dropReportedForCurrentPin) {
                            diagnostic = DiagnosticSnapshot(
                                event = "handleScoAudioStateUpdated",
                                outcome = "IGNORED",
                                details = mapOf(
                                    "reason" to "drop_already_reported",
                                    "state" to state.toString(),
                                    "previousState" to previousState.toString(),
                                    "pinGeneration" to pinGeneration.toString(),
                                ),
                            )
                        } else if (!scoConfirmedActive) {
                            diagnostic = DiagnosticSnapshot(
                                event = "handleScoAudioStateUpdated",
                                outcome = "IGNORED",
                                details = mapOf(
                                    "reason" to "sco_never_confirmed",
                                    "state" to state.toString(),
                                    "previousState" to previousState.toString(),
                                    "pinGeneration" to pinGeneration.toString(),
                                ),
                            )
                        } else if (!scoConfirmedFromEvent && isWithinSettleWindowLocked()) {
                            scoConfirmedActive = false
                            diagnostic = DiagnosticSnapshot(
                                event = "handleScoAudioStateUpdated",
                                outcome = "IGNORED",
                                details = mapOf(
                                    "reason" to "within_settle_window",
                                    "state" to state.toString(),
                                    "previousState" to previousState.toString(),
                                    "pinGeneration" to pinGeneration.toString(),
                                ),
                            )
                        } else {
                            dropReportedForCurrentPin = true
                            val btAvailable = platform.isScoAvailable()
                            val mode = platform.getMode()
                            val focusInterrupted = audioFocusInterrupted
                            val callMode = isCallAudioMode(mode)
                            val interrupted = focusInterrupted || callMode
                            val generation = pinGeneration
                            clearActiveScoFlagsLocked()
                            val drop = ScoDropEvent(
                                source = ScoDropSource.SCO_AUDIO_BROADCAST,
                                btScoStillAvailable = btAvailable,
                                interruptedByFocusOrCall = interrupted,
                                pinGeneration = generation,
                            )
                            eventToEmit = drop
                            val dropReason = when {
                                focusInterrupted -> "interrupted_by_focus"
                                callMode -> "interrupted_by_call"
                                !btAvailable -> "bt_sco_unavailable"
                                else -> "sco_dropped"
                            }
                            diagnostic = DiagnosticSnapshot(
                                event = "handleScoAudioStateUpdated",
                                outcome = "DROPPED",
                                details = mapOf(
                                    "reason" to dropReason,
                                    "source" to drop.source.name,
                                    "state" to state.toString(),
                                    "previousState" to previousState.toString(),
                                    "btScoStillAvailable" to btAvailable.toString(),
                                    "interruptedByFocusOrCall" to interrupted.toString(),
                                    "pinGeneration" to generation.toString(),
                                ),
                            )
                        }
                    }

                    else -> {
                        diagnostic = DiagnosticSnapshot(
                            event = "handleScoAudioStateUpdated",
                            outcome = "IGNORED",
                            details = mapOf(
                                "reason" to "non_terminal_state",
                                "state" to state.toString(),
                                "previousState" to previousState.toString(),
                            ),
                        )
                    }
                }
            }
        }
        emitDiagnostic(diagnostic)
        if (eventToEmit != null) {
            onScoDroppedListener?.invoke(eventToEmit)
        }
        return eventToEmit
    }

    private fun requestTransientFocusLocked(
        forCommunication: Boolean,
        kind: FocusKind,
        onAudioFocusChange: (Int) -> Unit,
    ): Boolean {
        val gen = ++focusGeneration
        val granted = platform.requestTransientFocus(forCommunication) { focusChange ->
            handleAudioFocusChange(focusChange, expectedFocusGeneration = gen)
            onAudioFocusChange(focusChange)
        }
        activeFocusKind = if (granted) kind else FocusKind.NONE
        return granted
    }

    private fun hasLingeringScoStateLocked(): Boolean =
        communicationDevicePinned ||
            legacyScoStarted ||
            scoPlatformClearNeeded ||
            previousAudioMode != null

    private fun emitDiagnostic(snapshot: DiagnosticSnapshot?) {
        if (snapshot == null) return
        onDiagnosticEvent?.invoke(snapshot.event, snapshot.outcome, snapshot.details)
    }

    private data class DiagnosticSnapshot(
        val event: String,
        val outcome: String,
        val details: Map<String, String>,
    )

    private fun pinBluetoothScoLocked(): Boolean {
        val alreadyOnSco = if (platform.sdkInt >= Build.VERSION_CODES.S) {
            platform.getCurrentCommunicationDeviceType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } else {
            legacyScoStarted && scoConfirmedActive
        }

        if (previousAudioMode == null) {
            previousAudioMode = platform.getMode()
        }
        platform.setMode(AudioManager.MODE_IN_COMMUNICATION)

        val pinned = platform.pinSco()
        if (!pinned) {
            restorePreviousAudioModeLocked()
            communicationDevicePinned = false
            legacyScoStarted = false
            scoPlatformClearNeeded = false
            scoConfirmedActive = false
            scoConfirmedFromEvent = false
            dropReportedForCurrentPin = false
            return false
        }

        if (platform.sdkInt >= Build.VERSION_CODES.S) {
            communicationDevicePinned = true
        } else {
            legacyScoStarted = true
        }
        scoPlatformClearNeeded = true
        pinnedAtElapsedMs = clockElapsedMs()
        dropReportedForCurrentPin = false
        scoConfirmedActive = alreadyOnSco
        scoConfirmedFromEvent = false
        return true
    }

    private fun clearScoRoutingLocked() {
        val needsClear = communicationDevicePinned || legacyScoStarted || scoPlatformClearNeeded
        clearActiveScoFlagsLocked()
        dropReportedForCurrentPin = false
        if (needsClear) {
            scoPlatformClearNeeded = false
            platform.clearSco()
        }
        restorePreviousAudioModeLocked()
    }

    private fun clearActiveScoFlagsLocked() {
        communicationDevicePinned = false
        legacyScoStarted = false
        scoConfirmedActive = false
        scoConfirmedFromEvent = false
    }

    private fun restorePreviousAudioModeLocked() {
        val savedMode = previousAudioMode ?: return
        previousAudioMode = null
        val currentMode = platform.getMode()
        if (currentMode == null || currentMode == AudioManager.MODE_IN_COMMUNICATION) {
            platform.setMode(savedMode)
        }
    }

    private fun isWithinSettleWindowLocked(): Boolean {
        val elapsed = clockElapsedMs() - pinnedAtElapsedMs
        return elapsed in 0L until SCO_SETTLE_WINDOW_MS
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31CommDeviceObserver {
        fun register(
            audioManager: AudioManager,
            executor: java.util.concurrent.Executor,
            onDeviceTypeChanged: (Int?) -> Unit,
        ): Any {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                onDeviceTypeChanged(device?.type)
            }
            audioManager.addOnCommunicationDeviceChangedListener(executor, listener)
            return listener
        }

        fun unregister(
            audioManager: AudioManager,
            handle: Any,
        ) {
            val listener = handle as? AudioManager.OnCommunicationDeviceChangedListener ?: return
            runCatching {
                audioManager.removeOnCommunicationDeviceChangedListener(listener)
            }
        }
    }

    companion object {
        internal const val SCO_SETTLE_WINDOW_MS = 300L

        internal fun isCallAudioMode(mode: Int?): Boolean =
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE

        private fun focusChangeName(focusChange: Int): String = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> focusChange.toString()
        }
    }
}
