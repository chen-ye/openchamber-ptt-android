package com.openchamber.ptt.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BluetoothAudioRouterTest {
    private class FakeAudioRoutingPlatform(
        override val sdkInt: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        var audioMode: Int? = AudioManager.MODE_NORMAL,
        var scoAvailable: Boolean = true,
        var currentCommDeviceType: Int? = null,
        var pinScoResult: Boolean = true,
        var focusRequestResult: Boolean = true,
    ) : AudioRoutingPlatform {
        var modeSetCalls = 0
        var pinCalls = 0
        var clearCalls = 0
        var focusRequests = 0
        var focusAbandons = 0
        var lastFocusForComm: Boolean? = null
        private var focusListeners = mutableListOf<(Int) -> Unit>()

        override fun getMode(): Int? = audioMode

        override fun setMode(mode: Int) {
            modeSetCalls++
            this.audioMode = mode
        }

        override fun requestTransientFocus(
            forCommunication: Boolean,
            onAudioFocusChange: (Int) -> Unit,
        ): Boolean {
            focusRequests++
            lastFocusForComm = forCommunication
            focusListeners += onAudioFocusChange
            return focusRequestResult
        }

        override fun abandonTransientFocus() {
            focusAbandons++
        }

        override fun isScoAvailable(): Boolean = scoAvailable

        override fun getCurrentCommunicationDeviceType(): Int? = currentCommDeviceType

        override fun pinSco(): Boolean {
            pinCalls++
            if (!scoAvailable) return false
            return pinScoResult
        }

        override fun clearSco() {
            clearCalls++
            if (currentCommDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                currentCommDeviceType = null
            }
        }

        fun dispatchFocusChange(focusChange: Int, listenerIndex: Int = focusListeners.lastIndex) {
            focusListeners.getOrNull(listenerIndex)?.invoke(focusChange)
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
    fun `API 29 pin with no Bluetooth SCO available fails cleanly and keeps MODE_NORMAL`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.Q,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = false,
            pinScoResult = true,
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
                pinGeneration = 1L,
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
    fun `delayed prior-session DISCONNECTED broadcast arriving after 300ms while unconfirmed is ignored`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        var nowMs = 1000L
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { nowMs }
        router.startMonitoring { droppedEvents += it }

        // Start a fresh pin while prior teardown hasn't fired its broadcast yet (scoConfirmedActive == false)
        assertTrue(router.configureForPtt(pinSco = true))

        // Delayed DISCONNECTED(previousState = CONNECTED) from previous turn arrives at +350ms (> 300ms)
        nowMs = 1350L
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertTrue(droppedEvents.isEmpty())
        assertTrue(router.isScoPinned)
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
                pinGeneration = 1L,
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

        // Even after >300ms while unconfirmed, a delayed teardown does NOT fire until fresh confirmation arrives
        nowMs = 1350L
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertTrue(droppedEvents.isEmpty())

        // Fresh connection confirms SCO; immediate drop at 1400ms now reports cleanly
        nowMs = 1380L
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        nowMs = 1400L
        val drop = router.handleCommunicationDeviceChanged(null)
        assertEquals(1, droppedEvents.size)
        assertEquals(ScoDropSource.COMMUNICATION_DEVICE, drop?.source)
    }

    @Test
    fun `audio focus loss, failed focus grant, and stale focus listener isolation work cleanly`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }

        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))

        // Start a second pin generation; stale focus loss from listener 0 must be ignored
        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        platform.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, listenerIndex = 0)
        val cleanDrop = router.handleCommunicationDeviceChanged(null)
        assertEquals(false, cleanDrop?.interruptedByFocusOrCall)

        // Failed focus request marks interruptedByFocusOrCall = true
        platform.focusRequestResult = false
        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        val deniedFocusDrop = router.handleCommunicationDeviceChanged(null)
        assertEquals(true, deniedFocusDrop?.interruptedByFocusOrCall)

        // Active focus loss + MODE_IN_CALL marks interruption and release preserves MODE_IN_CALL
        platform.focusRequestResult = true
        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        platform.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        platform.audioMode = AudioManager.MODE_IN_CALL
        val callDrop = router.handleCommunicationDeviceChanged(null)
        assertEquals(true, callDrop?.interruptedByFocusOrCall)
        router.release()
        assertEquals(AudioManager.MODE_IN_CALL, platform.audioMode)
    }

    @Test
    fun `releaseIfGeneration and stopMonitoring respect generation tokens and detach listener`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            scoAvailable = true,
        )
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }
        router.startMonitoring { droppedEvents += it }

        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        val gen1Drop = router.handleCommunicationDeviceChanged(null)!!
        assertEquals(1L, gen1Drop.pinGeneration)

        // Start generation 2 before generation 1 cleanup runs
        assertTrue(router.configureForPtt(pinSco = true))
        assertTrue(router.isScoPinned)
        assertFalse(router.isCurrentPinGeneration(gen1Drop.pinGeneration))
        assertTrue(router.isCurrentPinGeneration(2L))

        // Stale releaseIfGeneration(1L) must NOT tear down generation 2
        router.releaseIfGeneration(gen1Drop.pinGeneration)
        assertTrue(router.isScoPinned)
        assertEquals(0, platform.clearCalls)

        // Matching releaseIfGeneration(2L) releases cleanly
        router.releaseIfGeneration(2L)
        assertFalse(router.isScoPinned)
        assertEquals(1, platform.clearCalls)

        // stopMonitoring detaches listener so subsequent drops are not delivered to callback
        assertTrue(router.configureForPtt(pinSco = true))
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        router.stopMonitoring()
        val dropAfterStop = router.handleCommunicationDeviceChanged(null)
        assertEquals(4L, dropAfterStop?.pinGeneration)
        assertEquals(1, droppedEvents.size)
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
                pinGeneration = 1L,
            ),
            drop,
        )
    }

    @Test
    fun `acquireCaptureAudio with telecomManaged true skips mode focus and pinSco while suppressing SCO drops`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        val droppedEvents = mutableListOf<ScoDropEvent>()
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }
        router.startMonitoring { droppedEvents += it }
        router.onDiagnosticEvent = { event, outcome, details ->
            diagnostics += Triple(event, outcome, details)
        }

        assertTrue(router.acquireCaptureAudio(pinSco = true, telecomManaged = true))
        assertFalse(router.isScoPinned)
        assertTrue(router.isCurrentPinGeneration(1L))
        assertEquals(AudioManager.MODE_NORMAL, platform.audioMode)
        assertEquals(0, platform.modeSetCalls)
        assertEquals(0, platform.focusRequests)
        assertEquals(0, platform.pinCalls)
        assertEquals(0, platform.clearCalls)

        // Both communication device changes and SCO broadcasts are suppressed under Telecom ownership
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertNull(router.handleCommunicationDeviceChanged(null))
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertTrue(droppedEvents.isEmpty())
        assertTrue(
            diagnostics.any {
                it.first == "handleCommunicationDeviceChanged" &&
                    it.second == "IGNORED" &&
                    it.third["reason"] == "telecom_managed"
            },
        )
        assertTrue(
            diagnostics.any {
                it.first == "handleScoAudioStateUpdated" &&
                    it.second == "IGNORED" &&
                    it.third["reason"] == "telecom_managed"
            },
        )

        // Release capture audio clears telecomManaged flag cleanly without calling clearSco/setMode
        router.releaseCaptureAudio()
        assertFalse(router.isScoPinned)
        assertEquals(0, platform.clearCalls)
        assertEquals(0, platform.modeSetCalls)
    }

    @Test
    fun `acquireCaptureAudio with telecomManaged true clears lingering legacy SCO state and advances generations`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }

        assertTrue(router.acquireCaptureAudio(pinSco = true, telecomManaged = false))
        assertTrue(router.isScoPinned)
        assertEquals(1, platform.focusRequests)
        assertEquals(1, platform.pinCalls)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.audioMode)

        // Transitioning to Telecom-managed capture clears lingering SCO + focus and increments generations
        assertTrue(router.acquireCaptureAudio(pinSco = true, telecomManaged = true))
        assertFalse(router.isScoPinned)
        assertFalse(router.isCurrentPinGeneration(1L))
        assertTrue(router.isCurrentPinGeneration(2L))
        assertEquals(1, platform.clearCalls)
        assertEquals(1, platform.focusAbandons)
        assertEquals(1, platform.focusRequests)
        assertEquals(1, platform.pinCalls)
        assertEquals(AudioManager.MODE_NORMAL, platform.audioMode)

        // Stale focus listener from generation 1 is ignored
        platform.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, listenerIndex = 0)
        assertFalse(router.isScoPinned)
    }

    @Test
    fun `acquirePlaybackFocus and releasePlaybackFocus isolate media focus from SCO pinning`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
        )
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { 1000L }

        // From idle, playback focus requests non-communication focus without pinning SCO or changing mode
        assertTrue(router.acquirePlaybackFocus())
        assertFalse(router.isScoPinned)
        assertEquals(1, platform.focusRequests)
        assertEquals(false, platform.lastFocusForComm)
        assertEquals(0, platform.pinCalls)
        assertEquals(0, platform.clearCalls)
        assertEquals(0, platform.modeSetCalls)

        // Releasing playback focus abandons focus without touching SCO
        router.releasePlaybackFocus()
        assertEquals(1, platform.focusAbandons)
        assertEquals(0, platform.clearCalls)
        assertEquals(0, platform.modeSetCalls)

        // If SCO was lingering, acquirePlaybackFocus clears SCO and restores MODE_NORMAL
        assertTrue(router.acquireCaptureAudio(pinSco = true, telecomManaged = false))
        assertTrue(router.isScoPinned)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, platform.audioMode)

        assertTrue(router.acquirePlaybackFocus())
        assertFalse(router.isScoPinned)
        assertEquals(1, platform.clearCalls)
        assertEquals(AudioManager.MODE_NORMAL, platform.audioMode)
        assertEquals(false, platform.lastFocusForComm)
        assertEquals(1, platform.pinCalls)

        // releasePlaybackFocus does NOT abandon active CAPTURE focus
        assertTrue(router.acquireCaptureAudio(pinSco = false, telecomManaged = false))
        val abandonsBefore = platform.focusAbandons
        router.releasePlaybackFocus()
        assertEquals(abandonsBefore, platform.focusAbandons)
        router.releaseCaptureAudio()
        assertEquals(abandonsBefore + 1, platform.focusAbandons)
    }

    @Test
    fun `onDiagnosticEvent reports exact suppression reasons and never holds lock`() {
        val platform = FakeAudioRoutingPlatform(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            audioMode = AudioManager.MODE_NORMAL,
            scoAvailable = true,
            currentCommDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        var nowMs = 1000L
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val router = BluetoothAudioRouter(appContext = null, audioManager = null, platform = platform) { nowMs }
        router.onDiagnosticEvent = { event, outcome, details ->
            // Calling a lock-acquiring getter from another thread proves lock is NOT held during callback
            val pinnedFromWorker = CompletableFuture.supplyAsync { router.isScoPinned }
                .get(1, TimeUnit.SECONDS)
            assertTrue(pinnedFromWorker || !pinnedFromWorker)
            diagnostics += Triple(event, outcome, details)
        }

        // 1. Sticky broadcast ignored
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = true,
            ),
        )
        assertEquals("sticky_broadcast", diagnostics.last().third["reason"])

        // 2. SCO not pinned ignored
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals("sco_not_pinned", diagnostics.last().third["reason"])

        // 3. Within settle window ignored
        assertTrue(router.acquireCaptureAudio(pinSco = true, telecomManaged = false))
        nowMs = 1050L
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTED,
                isInitialSticky = false,
            ),
        )
        assertEquals("within_settle_window", diagnostics.last().third["reason"])

        // 4. SCO never confirmed ignored
        nowMs = 1400L
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals("sco_never_confirmed", diagnostics.last().third["reason"])

        // 5. Handshake failed (CONNECTING -> DISCONNECTED) ignored
        assertNull(
            router.handleScoAudioStateUpdated(
                state = AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
                previousState = AudioManager.SCO_AUDIO_STATE_CONNECTING,
                isInitialSticky = false,
            ),
        )
        assertEquals("handshake_failed_connecting", diagnostics.last().third["reason"])

        // 6. Focus interruption emits DROPPED diagnostic with reason = interrupted_by_focus
        assertNull(router.handleCommunicationDeviceChanged(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        platform.dispatchFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        val drop = router.handleCommunicationDeviceChanged(null)
        assertEquals(true, drop?.interruptedByFocusOrCall)
        assertEquals("DROPPED", diagnostics.last().second)
        assertEquals("interrupted_by_focus", diagnostics.last().third["reason"])

        // 7. Stale pin generation on releaseIfGeneration
        router.releaseIfGeneration(999L)
        assertEquals("IGNORED", diagnostics.last().second)
        assertEquals("stale_pin_generation", diagnostics.last().third["reason"])
    }
}
