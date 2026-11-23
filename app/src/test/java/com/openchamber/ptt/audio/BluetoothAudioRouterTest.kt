package com.openchamber.ptt.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAudioRouterTest {
    private class FakeAudioRoutingPlatform(
        override val sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        var audioMode: Int? = AudioManager.MODE_NORMAL,
        var scoAvailable: Boolean = true,
        var currentCommDeviceType: Int? = null,
        var pinScoResult: Boolean = true,
    ) : AudioRoutingPlatform {
        var pinCalls = 0
        var clearCalls = 0
        var focusRequests = 0
        var focusAbandons = 0
        var lastFocusForComm: Boolean? = null
        private var focusListener: ((Int) -> Unit)? = null

        override fun getMode(): Int? = audioMode

        override fun setMode(mode: Int) {
            this.audioMode = mode
        }

        override fun requestTransientFocus(
            forCommunication: Boolean,
            onAudioFocusChange: (Int) -> Unit,
        ): Boolean {
            focusRequests++
            lastFocusForComm = forCommunication
            focusListener = onAudioFocusChange
            return true
        }

        override fun abandonTransientFocus() {
            focusAbandons++
            focusListener = null
        }

        override fun isScoAvailable(): Boolean = scoAvailable

        override fun getCurrentCommunicationDeviceType(): Int? = currentCommDeviceType

        override fun pinSco(): Boolean {
            pinCalls++
            return pinScoResult
        }

        override fun clearSco() {
            clearCalls++
            if (currentCommDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                currentCommDeviceType = null
            }
        }

        fun dispatchFocusChange(focusChange: Int) {
            focusListener?.invoke(focusChange)
        }
    }

    @Test
    fun `pin failure restores previous audio mode and clears isScoPinned`() {
        val platform = FakeAudioRoutingPlatform(
            audioMode = AudioManager.MODE_NORMAL,
            pinScoResult = false,
        )
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }

        assertFalse(router.configureForPtt(pinSco = true))
        assertFalse(router.isScoPinned)
        assertEquals(AudioManager.MODE_NORMAL, platform.audioMode)
    }

    @Test
    fun `intentional release suppresses subsequent SCO drop callbacks and restores mode`() {
        val platform = FakeAudioRoutingPlatform(audioMode = AudioManager.MODE_NORMAL)
        var nowMs = 1000L
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { nowMs }
        router.startMonitoring { droppedEvents += it }

        assertTrue(router.configureForPtt(pinSco = true))
        assertTrue(router.isScoPinned)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.audioMode)

        // Confirm active via communication device callback
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))

        // Intentional release clears flags before platform clear fires callbacks
        router.release()
        assertFalse(router.isScoPinned)
        assertEquals(AudioManager.MODE_NORMAL, platform.audioMode)
        assertEquals(1, platform.clearCalls)

        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertTrue(droppedEvents.isEmpty())
    }

    @Test
    fun `unexpected SCO drop after confirmation emits single deduplicated event and allows clean release`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        var nowMs = 1000L
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { nowMs }
        router.startMonitoring { droppedEvents += it }

        assertTrue(router.configureForPtt(pinSco = true))

        // Sticky initial broadcast and pre-confirmation device events are ignored
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = true,
            ),
        )
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertTrue(droppedEvents.isEmpty())

        // Confirm SCO active
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))

        // First drop signal fires once
        nowMs = 2500L
        val drop = router.handleCommunicationDeviceChanged(null)
        assertEquals(
            ScoDropEvent(
                source = ScoDropSource.COMMUNICATION_DEVICE,
                btScoStillAvailable = true,
                interruptedByFocusOrCall = false,
            ),
            drop,
        )
        assertFalse(router.isScoPinned)
        assertEquals(1, droppedEvents.size)

        // Secondary SCO broadcast for the same disconnect is deduplicated
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertEquals(1, droppedEvents.size)

        // Subsequent release() still invokes clearSco() once and restores mode
        router.release()
        assertEquals(1, platform.clearCalls)
        assertEquals(AudioManager.MODE_NORMAL, platform.audioMode)
    }

    @Test
    fun `legacy API 29 SCO broadcast detects unexpected drop and ignores failed handshake`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.Q,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }
        router.startMonitoring { droppedEvents += it }

        assertTrue(router.configureForPtt(pinSco = true))
        assertTrue(router.isScoPinned)

        // Failed initial handshake (CONNECTING -> DISCONNECTED) must not trigger drop
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        assertTrue(droppedEvents.isEmpty())

        // Connected -> unexpected disconnect fires SCO_AUDIO_BROADCAST drop
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        val drop = router.handleScoAudioStateUpdated(
            state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
            previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
            isInitialSticky = false,
        )
        assertEquals(
            ScoDropEvent(
                source = ScoDropSource.SCO_AUDIO_BROADCAST,
                btScoStillAvailable = true,
                interruptedByFocusOrCall = false,
            ),
            drop,
        )
        assertEquals(1, droppedEvents.size)
    }

    @Test
    fun `early connecting failure suppresses subsequent communication device change even if device fired early`() {
        val platform = FakeAudioRoutingPlatform(sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }
        router.startMonitoring { droppedEvents += it }

        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertTrue(droppedEvents.isEmpty())
    }

    @Test
    fun `barge-in settle window suppresses stale teardown from already-active SCO until fresh confirm or elapsed`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            currentCommDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        var nowMs = 1000L
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { nowMs }
        router.startMonitoring { droppedEvents += it }

        assertTrue(router.configureForPtt(pinSco = true))

        // At 1080ms (within 300ms settle window), stale teardown clears confirmation and waits for fresh connect
        nowMs = 1080L
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertTrue(droppedEvents.isEmpty())

        // Fresh connection confirms SCO; immediate drop at 1200ms now reports cleanly
        nowMs = 1150L
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        nowMs = 1200L
        val drop = router.handleCommunicationDeviceChanged(null)
        assertEquals(1, droppedEvents.size)
        assertEquals(ScoDropSource.COMMUNICATION_DEVICE, drop?.source)
    }

    @Test
    fun `audio focus loss or call mode flags interruption and release never clobbers MODE_IN_CALL`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }

        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))

        platform.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        platform.audioMode = AudioManager.MODE_IN_CALL

        val drop = router.handleCommunicationDeviceChanged(null)
        assertEquals(
            ScoDropEvent(
                source = ScoDropSource.COMMUNICATION_DEVICE,
                btScoStillAvailable = true,
                interruptedByFocusOrCall = true,
            ),
            drop,
        )

        router.release()
        assertEquals(AudioManager.MODE_IN_CALL, platform.audioMode)
    }

    @Test
    fun `btScoStillAvailable false marks headset physical disconnect`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            scoAvailable = true,
        )
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }

        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))

        platform.scoAvailable = false
        val drop = router.handleCommunicationDeviceChanged(null)
        assertEquals(
            ScoDropEvent(
                source = ScoDropSource.COMMUNICATION_DEVICE,
                btScoStillAvailable = false,
                interruptedByFocusOrCall = false,
            ),
            drop,
        )
    }
}
