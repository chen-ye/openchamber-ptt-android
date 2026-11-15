package com.openchamber.ptt.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

class BluetoothAudioRouter(
    private val audioManager: AudioManager,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSystemService(AudioManager::class.java),
    )

    private val lock = Any()
    private var activeFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int? = null
    private var legacyScoStarted: Boolean = false
    private var communicationDevicePinned: Boolean = false

    val isScoPinned: Boolean
        get() = synchronized(lock) { communicationDevicePinned || legacyScoStarted }

    fun configureForPtt(
        pinSco: Boolean,
        onAudioFocusChange: (Int) -> Unit = {},
    ): Boolean {
        synchronized(lock) {
            requestTransientFocusLocked(forCommunication = pinSco, onAudioFocusChange = onAudioFocusChange)
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

    fun isBluetoothScoAvailable(): Boolean {
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

    fun release() {
        synchronized(lock) {
            clearScoRoutingLocked()
            abandonFocusLocked()
        }
    }

    private fun requestTransientFocusLocked(
        forCommunication: Boolean,
        onAudioFocusChange: (Int) -> Unit,
    ): Boolean {
        abandonFocusLocked()
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

    private fun abandonFocusLocked() {
        val current = activeFocusRequest ?: return
        activeFocusRequest = null
        runCatching {
            audioManager.abandonAudioFocusRequest(current)
        }
    }

    private fun pinBluetoothScoLocked(): Boolean {
        if (previousAudioMode == null) {
            previousAudioMode = runCatching { audioManager.mode }.getOrNull()
        }
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scoDevice = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrNull() ?: return false

            val pinned = runCatching {
                audioManager.setCommunicationDevice(scoDevice)
            }.getOrDefault(false)
            communicationDevicePinned = pinned
            return pinned
        }

        @Suppress("DEPRECATION")
        return runCatching {
            audioManager.isBluetoothScoOn = true
            audioManager.startBluetoothSco()
            legacyScoStarted = true
            true
        }.getOrDefault(false)
    }

    private fun clearScoRoutingLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (communicationDevicePinned) {
                runCatching { audioManager.clearCommunicationDevice() }
                communicationDevicePinned = false
            }
        } else {
            if (legacyScoStarted) {
                @Suppress("DEPRECATION")
                runCatching {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                }
                legacyScoStarted = false
            }
        }

        val savedMode = previousAudioMode
        if (savedMode != null) {
            previousAudioMode = null
            runCatching {
                audioManager.mode = savedMode
            }
        }
    }
}
