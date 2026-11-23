package com.openchamber.ptt.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Observes public Android audio, Bluetooth headset, volume, and route callbacks and records
 * them into [AudioDebugBuffer].
 *
 * Uses strictly public SDK APIs (`minSdk = 29`, `compileSdk = 35`) and registers Bluetooth
 * receivers with [ContextCompat.RECEIVER_EXPORTED] because `com.android.bluetooth` runs as UID 1002.
 */
class AudioPlatformDiagnosticMonitor(
    context: Context,
    private val buffer: AudioDebugBuffer = AudioDebugRegistry.shared,
    private val onAudioBecomingNoisy: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var started = false
    private var generalReceiver: BroadcastReceiver? = null
    private var vendorReceiver: BroadcastReceiver? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var commDeviceObserverHandle: Any? = null

    private var lastPlaybackSummary: String? = null
    private var lastRecordingSummary: String? = null

    fun start() {
        val playbackCb: AudioManager.AudioPlaybackCallback?
        val recordingCb: AudioManager.AudioRecordingCallback?
        val devCb: AudioDeviceCallback?
        val genRx: BroadcastReceiver
        val venRx: BroadcastReceiver

        synchronized(lock) {
            if (started) return
            started = true

            genRx = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    handleGeneralBroadcast(intent, isInitialStickyBroadcast)
                }
            }
            venRx = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) return
                    handleVendorHeadsetBroadcast(intent, isInitialStickyBroadcast)
                }
            }

            val generalFilter = IntentFilter().apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(ACTION_VOLUME_CHANGED)
            }
            ContextCompat.registerReceiver(
                appContext,
                genRx,
                generalFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            generalReceiver = genRx

            val vendorFilter = IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
                for (companyId in VENDOR_COMPANY_IDS) {
                    addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$companyId")
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                venRx,
                vendorFilter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            vendorReceiver = venRx

            val mgr = audioManager
            if (mgr != null) {
                playbackCb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        handlePlaybackConfigsChanged(configs.orEmpty())
                    }
                }
                mgr.registerAudioPlaybackCallback(playbackCb, mainHandler)
                playbackCallback = playbackCb

                recordingCb = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                        handleRecordingConfigsChanged(configs.orEmpty())
                    }
                }
                mgr.registerAudioRecordingCallback(recordingCb, mainHandler)
                recordingCallback = recordingCb

                devCb = object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        handleAudioDevicesChanged("DEVICES_ADDED", addedDevices)
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        handleAudioDevicesChanged("DEVICES_REMOVED", removedDevices)
                    }
                }
                mgr.registerAudioDeviceCallback(devCb, mainHandler)
                deviceCallback = devCb

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    commDeviceObserverHandle = runCatching {
                        Api31CommDeviceHelper.register(
                            audioManager = mgr,
                            executor = ContextCompat.getMainExecutor(appContext),
                        ) { device ->
                            refreshAudioManagerSnapshot()
                            val modeLabel = runCatching { audioModeName(mgr.mode) }.getOrDefault("Unknown")
                            buffer.record(
                                category = AudioDebugCategory.AUDIO_ROUTE,
                                event = "COMM_DEVICE_CHANGED",
                                outcome = AudioDebugOutcome.STATE_CHANGE,
                                details = mapOf(
                                    "device" to formatAudioDevice(device),
                                    "audioMode" to modeLabel,
                                ),
                            )
                        }
                    }.getOrNull()
                }
            }
        }

        refreshAudioManagerSnapshot()
        seedInitialBluetoothAndStreamState()
    }

    fun stop() {
        val genRx: BroadcastReceiver?
        val venRx: BroadcastReceiver?
        val playbackCb: AudioManager.AudioPlaybackCallback?
        val recordingCb: AudioManager.AudioRecordingCallback?
        val devCb: AudioDeviceCallback?
        val commHandle: Any?

        synchronized(lock) {
            if (!started) return
            started = false
            genRx = generalReceiver
            generalReceiver = null
            venRx = vendorReceiver
            vendorReceiver = null
            playbackCb = playbackCallback
            playbackCallback = null
            recordingCb = recordingCallback
            recordingCallback = null
            devCb = deviceCallback
            deviceCallback = null
            commHandle = commDeviceObserverHandle
            commDeviceObserverHandle = null
            lastPlaybackSummary = null
            lastRecordingSummary = null
        }

        if (genRx != null) runCatching { appContext.unregisterReceiver(genRx) }
        if (venRx != null) runCatching { appContext.unregisterReceiver(venRx) }
        val mgr = audioManager
        if (mgr != null) {
            if (playbackCb != null) runCatching { mgr.unregisterAudioPlaybackCallback(playbackCb) }
            if (recordingCb != null) runCatching { mgr.unregisterAudioRecordingCallback(recordingCb) }
            if (devCb != null) runCatching { mgr.unregisterAudioDeviceCallback(devCb) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && commHandle != null) {
                runCatching { Api31CommDeviceHelper.unregister(mgr, commHandle) }
            }
        }
    }

    fun refreshAudioManagerSnapshot() {
        val mgr = audioManager ?: return
        val modeLabel = runCatching { audioModeName(mgr.mode) }.getOrDefault("Unknown")
        val commDeviceLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { Api31CommDeviceHelper.getCommunicationDevice(mgr) }
                .getOrNull()
                ?.let { formatAudioDevice(it) }
                ?: "None"
        } else {
            @Suppress("DEPRECATION")
            val legacyScoOn = runCatching { mgr.isBluetoothScoOn }.getOrDefault(false)
            if (legacyScoOn) "Bluetooth SCO (legacy)" else "Default (API ${Build.VERSION.SDK_INT})"
        }
        val playbackSummary = runCatching { mgr.activePlaybackConfigurations }.getOrNull()?.let { configs ->
            summarizePlaybackConfigs(configs).also { summary ->
                synchronized(lock) { lastPlaybackSummary = summary }
            }
        }
        val recordingSummary = runCatching { mgr.activeRecordingConfigurations }.getOrNull()?.let { configs ->
            summarizeRecordingConfigs(configs).also { summary ->
                synchronized(lock) { lastRecordingSummary = summary }
            }
        }
        val btStateLabel = readCurrentHeadsetProfileState()
        buffer.updateLiveStatus { current ->
            current.copy(
                audioMode = modeLabel,
                communicationDevice = commDeviceLabel,
                btHeadsetState = btStateLabel ?: current.btHeadsetState,
                activePlaybackSummary = playbackSummary ?: current.activePlaybackSummary,
                activeRecordingSummary = recordingSummary ?: current.activeRecordingSummary,
            )
        }
    }

    private fun seedInitialBluetoothAndStreamState() {
        val mgr = audioManager
        if (mgr != null) {
            runCatching { mgr.activePlaybackConfigurations }.getOrNull()?.let { configs ->
                val summary = summarizePlaybackConfigs(configs)
                synchronized(lock) { lastPlaybackSummary = summary }
                buffer.updateLiveStatus { it.copy(activePlaybackSummary = summary) }
            }
            runCatching { mgr.activeRecordingConfigurations }.getOrNull()?.let { configs ->
                val summary = summarizeRecordingConfigs(configs)
                synchronized(lock) { lastRecordingSummary = summary }
                buffer.updateLiveStatus { it.copy(activeRecordingSummary = summary) }
            }
        }

        val btStateLabel = readCurrentHeadsetProfileState()
        if (btStateLabel != null) {
            buffer.updateLiveStatus { it.copy(btHeadsetState = btStateLabel) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readCurrentHeadsetProfileState(): String? {
        if (!hasBluetoothConnectPermission()) return "Permission needed"
        val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return null
        val adapter = btManager.adapter ?: return "Unavailable"
        val state = runCatching { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) }
            .getOrNull() ?: return null
        return btProfileStateName(state)
    }

    private fun handleGeneralBroadcast(intent: Intent, isInitialSticky: Boolean) {
        when (intent.action) {
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                val deviceLabel = safeBluetoothDeviceLabel(device)
                val stateLabel = btProfileStateName(state)
                buffer.updateLiveStatus { current ->
                    val nextBtState = if (state == BluetoothProfile.STATE_CONNECTED) {
                        "Connected ($deviceLabel)"
                    } else {
                        stateLabel
                    }
                    current.copy(btHeadsetState = nextBtState)
                }
                buffer.record(
                    category = AudioDebugCategory.BT_HEADSET,
                    event = "HFP_$stateLabel",
                    outcome = AudioDebugOutcome.STATE_CHANGE,
                    details = mapOf(
                        "state" to stateLabel,
                        "prevState" to btProfileStateName(prevState),
                        "device" to deviceLabel,
                        "sticky" to isInitialSticky,
                    ),
                )
            }

            BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
                val prevState = intent.getIntExtra(BluetoothHeadset.EXTRA_PREVIOUS_STATE, -1)
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                val deviceLabel = safeBluetoothDeviceLabel(device)
                val stateLabel = btAudioStateName(state)
                buffer.updateLiveStatus { current ->
                    val nextBtState = when (state) {
                        BluetoothHeadset.STATE_AUDIO_CONNECTED -> "SCO Connected ($deviceLabel)"
                        BluetoothHeadset.STATE_AUDIO_CONNECTING -> "SCO Connecting ($deviceLabel)"
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> {
                            if (current.btHeadsetState.startsWith("SCO ")) {
                                "Connected ($deviceLabel)"
                            } else {
                                current.btHeadsetState
                            }
                        }
                        else -> current.btHeadsetState
                    }
                    current.copy(btHeadsetState = nextBtState)
                }
                buffer.record(
                    category = AudioDebugCategory.BT_HEADSET,
                    event = "HFP_$stateLabel",
                    outcome = AudioDebugOutcome.STATE_CHANGE,
                    details = mapOf(
                        "state" to stateLabel,
                        "prevState" to btAudioStateName(prevState),
                        "device" to deviceLabel,
                        "sticky" to isInitialSticky,
                    ),
                )
            }

            AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                buffer.record(
                    category = AudioDebugCategory.AUDIO_ROUTE,
                    event = "AUDIO_BECOMING_NOISY",
                    outcome = AudioDebugOutcome.STATE_CHANGE,
                    details = mapOf("sticky" to isInitialSticky),
                )
                refreshAudioManagerSnapshot()
                if (!isInitialSticky) {
                    onAudioBecomingNoisy()
                }
            }

            ACTION_VOLUME_CHANGED -> {
                val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                val value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                val prevValue = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
                buffer.record(
                    category = AudioDebugCategory.VOLUME_KEY,
                    event = "VOLUME_CHANGED",
                    outcome = AudioDebugOutcome.STATE_CHANGE,
                    details = mapOf(
                        "stream" to streamTypeName(streamType),
                        "value" to value,
                        "prevValue" to prevValue,
                        "sticky" to isInitialSticky,
                    ),
                )
            }
        }
    }

    private fun handleVendorHeadsetBroadcast(intent: Intent, isInitialSticky: Boolean) {
        if (intent.action != BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT) return
        val cmd = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
        val cmdType = intent.getIntExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, -1)
        @Suppress("DEPRECATION")
        val rawArgs = runCatching {
            intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS)
        }.getOrNull()
        val argsText = when (rawArgs) {
            is Array<*> -> rawArgs.joinToString(",")
            else -> rawArgs?.toString()
        }
        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        )
        buffer.record(
            category = AudioDebugCategory.BT_HEADSET,
            event = "VENDOR_AT_${cmd ?: "UNKNOWN"}",
            outcome = AudioDebugOutcome.ACCEPTED,
            details = mapOf(
                "cmd" to cmd,
                "cmdType" to vendorCmdTypeName(cmdType),
                "args" to argsText,
                "device" to safeBluetoothDeviceLabel(device),
                "sticky" to isInitialSticky,
            ),
        )
    }

    private fun handlePlaybackConfigsChanged(configs: List<AudioPlaybackConfiguration>) {
        val summary = summarizePlaybackConfigs(configs)
        val changed = synchronized(lock) {
            if (lastPlaybackSummary == summary) {
                false
            } else {
                lastPlaybackSummary = summary
                true
            }
        }
        buffer.updateLiveStatus { it.copy(activePlaybackSummary = summary) }
        if (changed) {
            buffer.record(
                category = AudioDebugCategory.PLAYBACK_CONFIG,
                event = if (configs.isEmpty()) "PLAYBACK_IDLE" else "PLAYBACK_ACTIVE",
                outcome = AudioDebugOutcome.STATE_CHANGE,
                details = mapOf(
                    "count" to configs.size,
                    "summary" to summary,
                ),
            )
        }
    }

    private fun handleRecordingConfigsChanged(configs: List<AudioRecordingConfiguration>) {
        val summary = summarizeRecordingConfigs(configs)
        val changed = synchronized(lock) {
            if (lastRecordingSummary == summary) {
                false
            } else {
                lastRecordingSummary = summary
                true
            }
        }
        buffer.updateLiveStatus { it.copy(activeRecordingSummary = summary) }
        if (changed) {
            buffer.record(
                category = AudioDebugCategory.RECORDING_CONFIG,
                event = if (configs.isEmpty()) "RECORDING_IDLE" else "RECORDING_ACTIVE",
                outcome = AudioDebugOutcome.STATE_CHANGE,
                details = mapOf(
                    "count" to configs.size,
                    "summary" to summary,
                ),
            )
        }
    }

    private fun handleAudioDevicesChanged(eventName: String, devices: Array<out AudioDeviceInfo>?) {
        val deviceList = devices?.toList().orEmpty()
        if (deviceList.isEmpty()) return
        val formatted = deviceList.joinToString("; ") { info ->
            val direction = when {
                info.isSink && info.isSource -> "sink+src"
                info.isSink -> "sink"
                info.isSource -> "src"
                else -> "io"
            }
            "${formatAudioDevice(info)}[$direction]"
        }
        buffer.record(
            category = AudioDebugCategory.AUDIO_ROUTE,
            event = eventName,
            outcome = AudioDebugOutcome.STATE_CHANGE,
            details = mapOf(
                "count" to deviceList.size,
                "devices" to formatted,
            ),
        )
        refreshAudioManagerSnapshot()
    }

    private fun summarizePlaybackConfigs(configs: List<AudioPlaybackConfiguration>): String {
        if (configs.isEmpty()) return "Idle"
        return configs.joinToString("; ") { config ->
            val attrs = config.audioAttributes
            val usage = audioUsageName(attrs.usage)
            val content = audioContentTypeName(attrs.contentType)
            val deviceLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Api31PlaybackHelper.getAudioDeviceInfo(config)?.let { " -> ${formatAudioDevice(it)}" } ?: ""
            } else {
                ""
            }
            "$usage/$content$deviceLabel"
        }
    }

    private fun summarizeRecordingConfigs(configs: List<AudioRecordingConfiguration>): String {
        if (configs.isEmpty()) return "Idle"
        return configs.joinToString("; ") { config ->
            val clientSrc = audioSourceName(config.clientAudioSource)
            val activeSrc = audioSourceName(config.audioSource)
            val dev = config.audioDevice?.let { formatAudioDevice(it) } ?: "Unknown"
            val silenced = if (config.isClientSilenced) " [SILENCED]" else ""
            "$clientSrc(actual=$activeSrc) @ $dev$silenced"
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun safeBluetoothDeviceLabel(device: BluetoothDevice?): String {
        if (device == null) return "Unknown"
        if (!hasBluetoothConnectPermission()) return "Bluetooth Device (no permission)"
        val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        val addrSuffix = runCatching { device.address }.getOrNull()?.takeLast(5)
        return when {
            name != null && addrSuffix != null -> "$name (..$addrSuffix)"
            name != null -> name
            addrSuffix != null -> "BT (..$addrSuffix)"
            else -> "Bluetooth Device"
        }
    }

    private fun formatAudioDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "None"
        val typeLabel = audioDeviceTypeName(device.type)
        val product = device.productName?.toString()?.trim()?.takeIf { it.isNotBlank() }
        return if (product != null) "$typeLabel ($product)" else typeLabel
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31PlaybackHelper {
        fun getAudioDeviceInfo(config: AudioPlaybackConfiguration): AudioDeviceInfo? {
            return config.audioDeviceInfo
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31CommDeviceHelper {
        fun getCommunicationDevice(audioManager: AudioManager): AudioDeviceInfo? {
            return audioManager.communicationDevice
        }

        fun register(
            audioManager: AudioManager,
            executor: java.util.concurrent.Executor,
            onChanged: (AudioDeviceInfo?) -> Unit,
        ): Any {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                onChanged(device)
            }
            audioManager.addOnCommunicationDeviceChangedListener(executor, listener)
            return listener
        }

        fun unregister(audioManager: AudioManager, handle: Any) {
            val listener = handle as? AudioManager.OnCommunicationDeviceChangedListener ?: return
            audioManager.removeOnCommunicationDeviceChangedListener(listener)
        }
    }

    companion object {
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"

        private val VENDOR_COMPANY_IDS = listOf(
            76, 224, 117, 301, 158, 103, 85, 130, 10, 29, 93, 148, 688, 1452, 135, 911, 1862,
        )

        private fun btProfileStateName(state: Int): String = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN($state)"
        }

        private fun btAudioStateName(state: Int): String = when (state) {
            BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "AUDIO_DISCONNECTED"
            BluetoothHeadset.STATE_AUDIO_CONNECTING -> "AUDIO_CONNECTING"
            BluetoothHeadset.STATE_AUDIO_CONNECTED -> "AUDIO_CONNECTED"
            else -> "UNKNOWN($state)"
        }

        private fun vendorCmdTypeName(cmdType: Int): String = when (cmdType) {
            BluetoothHeadset.AT_CMD_TYPE_READ -> "READ"
            BluetoothHeadset.AT_CMD_TYPE_TEST -> "TEST"
            BluetoothHeadset.AT_CMD_TYPE_SET -> "SET"
            BluetoothHeadset.AT_CMD_TYPE_BASIC -> "BASIC"
            BluetoothHeadset.AT_CMD_TYPE_ACTION -> "ACTION"
            else -> "TYPE($cmdType)"
        }

        private fun audioModeName(mode: Int): String = when (mode) {
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_CALL_SCREENING -> "MODE_CALL_SCREENING"
            AudioManager.MODE_CALL_REDIRECT -> "MODE_CALL_REDIRECT"
            AudioManager.MODE_COMMUNICATION_REDIRECT -> "MODE_COMMUNICATION_REDIRECT"
            else -> "MODE($mode)"
        }

        private fun streamTypeName(streamType: Int): String = when (streamType) {
            AudioManager.STREAM_VOICE_CALL -> "STREAM_VOICE_CALL"
            AudioManager.STREAM_SYSTEM -> "STREAM_SYSTEM"
            AudioManager.STREAM_RING -> "STREAM_RING"
            AudioManager.STREAM_MUSIC -> "STREAM_MUSIC"
            AudioManager.STREAM_ALARM -> "STREAM_ALARM"
            AudioManager.STREAM_NOTIFICATION -> "STREAM_NOTIFICATION"
            6 -> "STREAM_BLUETOOTH_SCO"
            AudioManager.STREAM_DTMF -> "STREAM_DTMF"
            AudioManager.STREAM_ACCESSIBILITY -> "STREAM_ACCESSIBILITY"
            else -> "STREAM($streamType)"
        }

        private fun audioUsageName(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMM"
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "VOICE_COMM_SIG"
            AudioAttributes.USAGE_ALARM -> "ALARM"
            AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "ACCESSIBILITY"
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAV"
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "SONIFICATION"
            AudioAttributes.USAGE_GAME -> "GAME"
            AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
            else -> "USAGE($usage)"
        }

        private fun audioContentTypeName(contentType: Int): String = when (contentType) {
            AudioAttributes.CONTENT_TYPE_UNKNOWN -> "UNKNOWN"
            AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
            AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
            AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
            AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
            else -> "CONTENT($contentType)"
        }

        private fun audioSourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.DEFAULT -> "DEFAULT"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_PERFORMANCE -> "VOICE_PERFORMANCE"
            else -> "SOURCE($source)"
        }

        private fun audioDeviceTypeName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
            AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            AudioDeviceInfo.TYPE_BUS -> "BUS"
            else -> "DEV($type)"
        }
    }
}
