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

    @Volatile
    private var onScoDroppedListener: ((ScoDropEvent) -> Unit)? = null
    private var monitorsRegistered: Boolean = false
    private var scoStateReceiver: BroadcastReceiver? = null
    private var commDeviceObserverHandle: Any? = null

    val isScoPinned: Boolean
        get() = synchronized(lock) { communicationDevicePinned || legacyScoStarted }

    fun configureForPtt(
        pinSco: Boolean,
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean {
        synchronized(lock) {
            pinGeneration++
            val focusGranted = requestTransientFocusLocked(
                forCommunication = pinSco,
                onAudioFocusChange = onAudioFocusChange,
            )
            audioFocusInterrupted = !focusGranted
            return if (pinSco) {
                pinBluetoothScoLocked()
            } else {
                clearScoRoutingLocked()
                true
            }
        }
    }

    fun requestTransientFocus(
        forCommunication: Boolean = true,
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean {
        synchronized(lock) {
            return requestTransientFocusLocked(forCommunication, onAudioFocusChange)
        }
    }

    fun isBluetoothScoAvailable(): Boolean = platform.isScoAvailable()

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    fun releaseIfGeneration(generation: Long) {
        synchronized(lock) {
            if (pinGeneration != generation) return
            releaseLocked()
        }
    }

    internal fun isCurrentPinGeneration(generation: Long): Boolean =
        synchronized(lock) { pinGeneration == generation }

    private fun releaseLocked() {
        pinGeneration++
        focusGeneration++
        audioFocusInterrupted = false
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
        synchronized(lock) {
            if (expectedFocusGeneration != null && expectedFocusGeneration != focusGeneration) {
                return
            }
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> audioFocusInterrupted = true

                AudioManager.AUDIOFOCUS_GAIN -> audioFocusInterrupted = false
            }
        }
    }

    internal fun handleCommunicationDeviceChanged(deviceType: Int?): ScoDropEvent? {
        val eventToEmit: ScoDropEvent
        synchronized(lock) {
            if (!communicationDevicePinned) {
                return null
            }
            if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                scoConfirmedActive = true
                scoConfirmedFromEvent = true
                return null
            }
            if (!scoConfirmedActive || dropReportedForCurrentPin) {
                return null
            }
            if (!scoConfirmedFromEvent && isWithinSettleWindowLocked()) {
                // Previous teardown was still settling when we re-pinned; wait for fresh confirmation.
                scoConfirmedActive = false
                return null
            }
            dropReportedForCurrentPin = true
            val btAvailable = platform.isScoAvailable()
            val interrupted = audioFocusInterrupted || isCallAudioMode(platform.getMode())
            val generation = pinGeneration
            clearActiveScoFlagsLocked()
            eventToEmit = ScoDropEvent(
                source = ScoDropSource.COMMUNICATION_DEVICE,
                btScoStillAvailable = btAvailable,
                interruptedByFocusOrCall = interrupted,
                pinGeneration = generation,
            )
        }
        onScoDroppedListener?.invoke(eventToEmit)
        return eventToEmit
    }

    internal fun handleScoAudioStateUpdated(
        state: Int,
        previousState: Int,
        isInitialSticky: Boolean,
    ): ScoDropEvent? {
        val eventToEmit: ScoDropEvent
        synchronized(lock) {
            if (isInitialSticky || !(communicationDevicePinned || legacyScoStarted)) {
                return null
            }
            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    scoConfirmedActive = true
                    scoConfirmedFromEvent = true
                    return null
                }

                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (previousState == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                        // Initial SCO handshake failed before establishing an active audio link.
                        scoConfirmedActive = false
                        scoConfirmedFromEvent = false
                        return null
                    }
                    if (!scoConfirmedActive) {
                        return null
                    }
                    if (!scoConfirmedFromEvent && isWithinSettleWindowLocked()) {
                        scoConfirmedActive = false
                        return null
                    }
                    if (dropReportedForCurrentPin) {
                        return null
                    }
                    dropReportedForCurrentPin = true
                    val btAvailable = platform.isScoAvailable()
                    val interrupted = audioFocusInterrupted || isCallAudioMode(platform.getMode())
                    val generation = pinGeneration
                    clearActiveScoFlagsLocked()
                    eventToEmit = ScoDropEvent(
                        source = ScoDropSource.SCO_AUDIO_BROADCAST,
                        btScoStillAvailable = btAvailable,
                        interruptedByFocusOrCall = interrupted,
                        pinGeneration = generation,
                    )
                }

                else -> return null
            }
        }
        onScoDroppedListener?.invoke(eventToEmit)
        return eventToEmit
    }

    private fun requestTransientFocusLocked(
        forCommunication: Boolean,
        onAudioFocusChange: (Int) -> Unit,
    ): Boolean {
        val gen = ++focusGeneration
        return platform.requestTransientFocus(forCommunication) { focusChange ->
            handleAudioFocusChange(focusChange, expectedFocusGeneration = gen)
            onAudioFocusChange(focusChange)
        }
    }

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
    }
}
