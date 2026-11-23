package com.openchamber.ptt.audio

import androidx.core.telecom.CallException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class TelecomCaptureCoordinatorTest {
    private class FakeSession(
        initialEndpoint: TelecomEndpoint,
        initialAvailableEndpoints: List<TelecomEndpoint>,
        private val setActiveOutcome: TelecomControlOutcome,
        private val autoSwitchOnRequest: Boolean,
        private val disconnectDelayMs: Long,
        private val onDisconnectCallback: suspend (TelecomDisconnectCode) -> Unit,
        private val onSetInactiveCallback: suspend () -> Unit,
    ) {
        val availableFlow = MutableStateFlow(initialAvailableEndpoints)
        val currentFlow = MutableStateFlow(initialEndpoint)
        val callEnded = CompletableDeferred<Unit>()
        var setActiveCalls = 0
        var disconnectCalls = 0
        var lastDisconnectCause: TelecomDisconnectCode? = null
        val endpointChangeRequests = mutableListOf<TelecomEndpoint>()

        fun createHandle(scope: CoroutineScope): TelecomCallSessionHandle =
            object : TelecomCallSessionHandle {
                override val scope: CoroutineScope = scope
                override val availableEndpoints: Flow<List<TelecomEndpoint>> = availableFlow
                override val currentEndpoint: Flow<TelecomEndpoint> = currentFlow

                override suspend fun setActive(): TelecomControlOutcome {
                    setActiveCalls++
                    return setActiveOutcome
                }

                override suspend fun requestEndpointChange(endpoint: TelecomEndpoint): TelecomControlOutcome {
                    endpointChangeRequests += endpoint
                    if (autoSwitchOnRequest) {
                        currentFlow.value = endpoint
                    }
                    return TelecomControlOutcome.Success
                }

                override suspend fun disconnect(cause: TelecomDisconnectCode): TelecomControlOutcome {
                    disconnectCalls++
                    lastDisconnectCause = cause
                    if (disconnectDelayMs > 0L) {
                        delay(disconnectDelayMs)
                    }
                    callEnded.complete(Unit)
                    return TelecomControlOutcome.Success
                }
            }

        suspend fun triggerRemoteDisconnect(cause: TelecomDisconnectCode) {
            onDisconnectCallback(cause)
            callEnded.complete(Unit)
        }

        suspend fun triggerSetInactive() {
            onSetInactiveCallback()
        }
    }

    private class FakeTelecomCallPlatform(
        var telecomSupported: Boolean = true,
        var permissionsGranted: Boolean = true,
        var cellularCallActive: Boolean = false,
        var startingBtEndpoint: TelecomEndpoint? = null,
        var initialEndpoint: TelecomEndpoint = SPEAKER_ENDPOINT,
        var initialAvailableEndpoints: List<TelecomEndpoint> = listOf(SPEAKER_ENDPOINT),
        var setActiveOutcome: TelecomControlOutcome = TelecomControlOutcome.Success,
        var autoSwitchOnRequest: Boolean = true,
        var callSetupDelayMs: Long = 0L,
        var disconnectDelayMs: Long = 0L,
        var failRegisterWith: Throwable? = null,
    ) : TelecomCallPlatform {
        var registerCalls = 0
        var addCallCount = 0
        var activeConcurrentCalls = 0
        var maxConcurrentCalls = 0
        var registeredWhileCallActive = false
        val sessions = mutableListOf<FakeSession>()
        var onScopeOpened: ((FakeSession) -> Unit)? = null

        override fun hasRequiredPermissions(): Boolean = permissionsGranted

        override fun isTelecomSupported(): Boolean = telecomSupported

        override fun isCellularCallActive(): Boolean = cellularCallActive

        override fun registerAppIfNeeded(): Result<Unit> {
            if (activeConcurrentCalls > 0) {
                registeredWhileCallActive = true
            }
            failRegisterWith?.let { return Result.failure(it) }
            if (registerCalls == 0) {
                registerCalls++
            }
            return Result.success(Unit)
        }

        override suspend fun findStartingBluetoothEndpoint(timeoutMs: Long): TelecomEndpoint? =
            startingBtEndpoint

        override suspend fun addOutgoingAudioCall(
            preferredBluetoothEndpoint: TelecomEndpoint?,
            onDisconnect: suspend (cause: TelecomDisconnectCode) -> Unit,
            onSetInactive: suspend () -> Unit,
            onCallScopeReady: (TelecomCallSessionHandle) -> Unit,
        ) {
            addCallCount++
            activeConcurrentCalls++
            maxConcurrentCalls = maxOf(maxConcurrentCalls, activeConcurrentCalls)
            val session = FakeSession(
                initialEndpoint = initialEndpoint,
                initialAvailableEndpoints = initialAvailableEndpoints,
                setActiveOutcome = setActiveOutcome,
                autoSwitchOnRequest = autoSwitchOnRequest,
                disconnectDelayMs = disconnectDelayMs,
                onDisconnectCallback = onDisconnect,
                onSetInactiveCallback = onSetInactive,
            )
            sessions += session
            val sessionScope = CoroutineScope(SupervisorJob(coroutineContext.job) + coroutineContext)
            try {
                if (callSetupDelayMs > 0L) {
                    delay(callSetupDelayMs)
                }
                val handle = session.createHandle(sessionScope)
                onCallScopeReady(handle)
                onScopeOpened?.invoke(session)
                session.callEnded.await()
            } finally {
                sessionScope.cancel()
                activeConcurrentCalls--
            }
        }
    }

    @Test
    fun `cancel under 200ms before CallControlScope skips setActive and disconnects cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(callSetupDelayMs = 150L)
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
        )

        val startDeferred = async { coordinator.startCaptureCall() }
        advanceTimeBy(50L)
        runCurrent()
        assertFalse(startDeferred.isCompleted)

        coordinator.endCaptureCall()
        runCurrent()
        assertTrue(startDeferred.isCompleted)
        assertEquals(TelecomCallStartResult.CancelledBeforeReady(1L), startDeferred.await())

        // Once CallControlScope opens at 150ms, line-1 check disconnects without calling setActive()
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(1, platform.sessions.size)
        val session = platform.sessions.single()
        assertEquals(0, session.setActiveCalls)
        assertEquals(1, session.disconnectCalls)
        assertEquals(TelecomDisconnectCode.LOCAL, session.lastDisconnectCause)
        assertFalse(coordinator.isCallActive)
        assertEquals(1, platform.maxConcurrentCalls)
        assertEquals(1, platform.registerCalls)
    }

    @Test
    fun `Bluetooth endpoint switches via requestEndpointChange and resolves Ready before timeout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = EARPIECE_ENDPOINT,
            initialAvailableEndpoints = listOf(EARPIECE_ENDPOINT, BT_ENDPOINT),
            autoSwitchOnRequest = true,
        )
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            btEndpointTimeoutMs = 1_200L,
            ioDispatcher = dispatcher,
        )

        val res = coordinator.startCaptureCall()
        assertEquals(
            TelecomCallStartResult.Ready(
                generation = 1L,
                bluetoothEndpointActive = true,
                endpointType = TelecomEndpointType.BLUETOOTH,
            ),
            res,
        )
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(coordinator.isCallActive)
        assertEquals(listOf(BT_ENDPOINT), platform.sessions.single().endpointChangeRequests)

        coordinator.endCaptureCall(1L)
        runCurrent()
        assertFalse(coordinator.isCallActive)
        assertEquals(1, platform.sessions.single().disconnectCalls)
    }

    @Test
    fun `1200ms BT endpoint timeout falls back to non-BT endpoint so recording never hangs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = SPEAKER_ENDPOINT,
            initialAvailableEndpoints = listOf(SPEAKER_ENDPOINT),
        )
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            btEndpointTimeoutMs = 1_200L,
            ioDispatcher = dispatcher,
        )

        val startDeferred = async { coordinator.startCaptureCall() }
        advanceTimeBy(1_199L)
        runCurrent()
        assertFalse(startDeferred.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(startDeferred.isCompleted)
        assertEquals(
            TelecomCallStartResult.Ready(
                generation = 1L,
                bluetoothEndpointActive = false,
                endpointType = TelecomEndpointType.SPEAKER,
            ),
            startDeferred.await(),
        )
        assertTrue(coordinator.isCallActive)

        coordinator.endCaptureCall()
        runCurrent()
        assertFalse(coordinator.isCallActive)
    }

    @Test
    fun `remote MFB onDisconnect invokes onRemoteHangupStop once while app disconnect is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = BT_ENDPOINT,
            initialAvailableEndpoints = listOf(BT_ENDPOINT),
        )
        val remoteStops = mutableListOf<Long>()
        val cellularInterruptions = mutableListOf<Long>()
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
            onRemoteHangupStop = { remoteStops += it },
            onCellularOrSystemInterruption = { cellularInterruptions += it },
        )

        val first = coordinator.startCaptureCall()
        assertTrue(first is TelecomCallStartResult.Ready)
        assertEquals(1L, (first as TelecomCallStartResult.Ready).generation)

        val session1 = platform.sessions[0]
        session1.triggerRemoteDisconnect(TelecomDisconnectCode.REMOTE)
        runCurrent()
        assertEquals(listOf(1L), remoteStops)
        assertTrue(cellularInterruptions.isEmpty())
        assertFalse(coordinator.isCallActive)

        // Duplicate disconnect callback for same call is deduplicated
        session1.triggerRemoteDisconnect(TelecomDisconnectCode.LOCAL)
        runCurrent()
        assertEquals(listOf(1L), remoteStops)

        // App-initiated endCaptureCall suppresses subsequent onDisconnect(LOCAL)
        val second = coordinator.startCaptureCall()
        assertTrue(second is TelecomCallStartResult.Ready)
        val session2 = platform.sessions[1]
        coordinator.endCaptureCall(2L)
        session2.triggerRemoteDisconnect(TelecomDisconnectCode.LOCAL)
        runCurrent()
        assertEquals(listOf(1L), remoteStops)
        assertTrue(cellularInterruptions.isEmpty())
        assertFalse(coordinator.isCallActive)
    }

    @Test
    fun `CallControlResult Error on setActive returns FailedCellularOrBusy and disconnects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            setActiveOutcome = TelecomControlOutcome.Error(CallException.ERROR_CANNOT_HOLD_CURRENT_ACTIVE_CALL),
        )
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
        )

        val res = coordinator.startCaptureCall()
        assertEquals(
            TelecomCallStartResult.FailedCellularOrBusy(
                errorCode = CallException.ERROR_CANNOT_HOLD_CURRENT_ACTIVE_CALL,
                reason = "setActive_error_${CallException.ERROR_CANNOT_HOLD_CURRENT_ACTIVE_CALL}",
            ),
            res,
        )
        runCurrent()
        assertFalse(coordinator.isCallActive)
        assertEquals(1, platform.sessions.single().disconnectCalls)
    }

    @Test
    fun `cellular onSetInactive and BUSY CANCELED or cellular-mode onDisconnect notify interruption`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = BT_ENDPOINT,
            initialAvailableEndpoints = listOf(BT_ENDPOINT),
        )
        val remoteStops = mutableListOf<Long>()
        val cellularInterruptions = mutableListOf<Long>()
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
            onRemoteHangupStop = { remoteStops += it },
            onCellularOrSystemInterruption = { cellularInterruptions += it },
        )

        // 1. onSetInactive disconnects cleanly and signals cellular/system interruption
        assertTrue(coordinator.startCaptureCall() is TelecomCallStartResult.Ready)
        platform.sessions[0].triggerSetInactive()
        runCurrent()
        assertEquals(listOf(1L), cellularInterruptions)
        assertTrue(remoteStops.isEmpty())
        assertEquals(1, platform.sessions[0].disconnectCalls)
        assertFalse(coordinator.isCallActive)

        // 2. onDisconnect(LOCAL) while cellular call active signals interruption, NOT remote hangup
        assertTrue(coordinator.startCaptureCall() is TelecomCallStartResult.Ready)
        platform.cellularCallActive = true
        platform.sessions[1].triggerRemoteDisconnect(TelecomDisconnectCode.LOCAL)
        runCurrent()
        assertEquals(listOf(1L, 2L), cellularInterruptions)
        assertTrue(remoteStops.isEmpty())

        // 3. onDisconnect(BUSY) and onDisconnect(CANCELED) signal interruption
        platform.cellularCallActive = false
        assertTrue(coordinator.startCaptureCall() is TelecomCallStartResult.Ready)
        platform.sessions[2].triggerRemoteDisconnect(TelecomDisconnectCode.BUSY)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), cellularInterruptions)
        assertTrue(remoteStops.isEmpty())

        assertTrue(coordinator.startCaptureCall() is TelecomCallStartResult.Ready)
        platform.sessions[3].triggerRemoteDisconnect(TelecomDisconnectCode.CANCELED)
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L), cellularInterruptions)
        assertTrue(remoteStops.isEmpty())
    }

    @Test
    fun `overlapping startCaptureCall serializes cleanly behind previous call disconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = BT_ENDPOINT,
            initialAvailableEndpoints = listOf(BT_ENDPOINT),
            disconnectDelayMs = 80L,
        )
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
        )

        val first = coordinator.startCaptureCall()
        assertTrue(first is TelecomCallStartResult.Ready)
        assertEquals(1, platform.addCallCount)
        assertTrue(coordinator.isCallActive)

        // Start second call while first call is still active and takes 80ms to disconnect
        val secondDeferred = async { coordinator.startCaptureCall() }
        advanceTimeBy(40L)
        runCurrent()
        assertFalse(secondDeferred.isCompleted)
        assertEquals(1, platform.addCallCount)
        assertEquals(1, platform.maxConcurrentCalls)

        advanceTimeBy(40L)
        runCurrent()
        assertTrue(secondDeferred.isCompleted)
        assertEquals(
            TelecomCallStartResult.Ready(
                generation = 2L,
                bluetoothEndpointActive = true,
                endpointType = TelecomEndpointType.BLUETOOTH,
            ),
            secondDeferred.await(),
        )
        assertEquals(2, platform.addCallCount)
        assertEquals(1, platform.maxConcurrentCalls)
        assertEquals(1, platform.registerCalls)
        assertFalse(platform.registeredWhileCallActive)

        coordinator.endCaptureCall()
        advanceTimeBy(80L)
        runCurrent()
        assertFalse(coordinator.isCallActive)
    }

    @Test
    fun `concurrent startup callers supersede cleanly without overlapping Telecom calls`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            callSetupDelayMs = 100L,
            initialEndpoint = BT_ENDPOINT,
            initialAvailableEndpoints = listOf(BT_ENDPOINT),
        )
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
        )

        val call1 = async { coordinator.startCaptureCall() }
        advanceTimeBy(20L)
        runCurrent()

        val call2 = async { coordinator.startCaptureCall() }
        runCurrent()

        assertTrue(call1.isCompleted)
        assertEquals(TelecomCallStartResult.CancelledBeforeReady(1L), call1.await())

        advanceTimeBy(250L)
        runCurrent()
        assertTrue(call2.isCompleted)
        val res2 = call2.await()
        assertTrue(res2 is TelecomCallStartResult.Ready)
        assertEquals(1, platform.maxConcurrentCalls)

        coordinator.endCaptureCall()
        runCurrent()
    }

    @Test
    fun `permission missing, cellular active at start, and diagnostic events behave cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(permissionsGranted = false)
        val diagnostics = mutableListOf<Triple<String, String, Map<String, String>>>()
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            ioDispatcher = dispatcher,
        )
        coordinator.onDiagnosticEvent = { event, outcome, details ->
            diagnostics += Triple(event, outcome, details)
        }

        val noPerm = coordinator.startCaptureCall()
        assertTrue(noPerm is TelecomCallStartResult.FailedUnavailable)
        assertEquals(0, platform.addCallCount)
        assertEquals("missing_manage_own_calls", diagnostics.last().third["reason"])

        platform.permissionsGranted = true
        platform.cellularCallActive = true
        val busy = coordinator.startCaptureCall()
        assertEquals(TelecomCallStartResult.FailedCellularOrBusy(reason = "cellular_call_active"), busy)
        assertEquals(0, platform.addCallCount)
        assertEquals("cellular_call_active", diagnostics.last().third["reason"])
    }

    @Test
    fun `pre-ready REJECTED and ERROR onDisconnect return FailedUnavailable without interruption`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = EARPIECE_ENDPOINT,
            initialAvailableEndpoints = listOf(EARPIECE_ENDPOINT),
        )
        val remoteStops = mutableListOf<Long>()
        val cellularInterruptions = mutableListOf<Long>()
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            btEndpointTimeoutMs = 1_200L,
            ioDispatcher = dispatcher,
            onRemoteHangupStop = { remoteStops += it },
            onCellularOrSystemInterruption = { cellularInterruptions += it },
        )

        val d1 = async { coordinator.startCaptureCall() }
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(d1.isCompleted)
        platform.sessions[0].triggerRemoteDisconnect(TelecomDisconnectCode.REJECTED)
        runCurrent()
        assertTrue(d1.isCompleted)
        assertEquals(
            TelecomCallStartResult.FailedUnavailable(reason = "disconnected_rejected"),
            d1.await(),
        )
        assertTrue(remoteStops.isEmpty())
        assertTrue(cellularInterruptions.isEmpty())
        assertFalse(coordinator.isCallActive)

        val d2 = async { coordinator.startCaptureCall() }
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(d2.isCompleted)
        platform.sessions[1].triggerRemoteDisconnect(TelecomDisconnectCode.ERROR)
        runCurrent()
        assertTrue(d2.isCompleted)
        assertEquals(
            TelecomCallStartResult.FailedUnavailable(reason = "disconnected_error"),
            d2.await(),
        )
        assertTrue(remoteStops.isEmpty())
        assertTrue(cellularInterruptions.isEmpty())
        assertFalse(coordinator.isCallActive)
    }

    @Test
    fun `pre-ready LOCAL and REMOTE onDisconnect return CancelledBeforeReady with byRemoteHangup true`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeTelecomCallPlatform(
            initialEndpoint = EARPIECE_ENDPOINT,
            initialAvailableEndpoints = listOf(EARPIECE_ENDPOINT),
        )
        val remoteStops = mutableListOf<Long>()
        val cellularInterruptions = mutableListOf<Long>()
        val coordinator = TelecomCaptureCoordinator(
            scope = backgroundScope,
            platform = platform,
            btEndpointTimeoutMs = 1_200L,
            ioDispatcher = dispatcher,
            onRemoteHangupStop = { remoteStops += it },
            onCellularOrSystemInterruption = { cellularInterruptions += it },
        )

        // App-initiated cancel before ready has byRemoteHangup = false
        val dApp = async { coordinator.startCaptureCall() }
        advanceTimeBy(100L)
        runCurrent()
        coordinator.endCaptureCall(1L)
        runCurrent()
        assertEquals(
            TelecomCallStartResult.CancelledBeforeReady(generation = 1L, byRemoteHangup = false),
            dApp.await(),
        )

        // Pre-ready remote disconnect LOCAL -> byRemoteHangup = true, no cellular interruption
        val dLocal = async { coordinator.startCaptureCall() }
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(dLocal.isCompleted)
        platform.sessions[1].triggerRemoteDisconnect(TelecomDisconnectCode.LOCAL)
        runCurrent()
        assertEquals(
            TelecomCallStartResult.CancelledBeforeReady(generation = 2L, byRemoteHangup = true),
            dLocal.await(),
        )
        assertTrue(remoteStops.isEmpty())
        assertTrue(cellularInterruptions.isEmpty())
        assertFalse(coordinator.isCallActive)

        // Pre-ready remote disconnect REMOTE -> byRemoteHangup = true, no cellular interruption
        val dRemote = async { coordinator.startCaptureCall() }
        advanceTimeBy(100L)
        runCurrent()
        assertFalse(dRemote.isCompleted)
        platform.sessions[2].triggerRemoteDisconnect(TelecomDisconnectCode.REMOTE)
        runCurrent()
        assertEquals(
            TelecomCallStartResult.CancelledBeforeReady(generation = 3L, byRemoteHangup = true),
            dRemote.await(),
        )
        assertTrue(remoteStops.isEmpty())
        assertTrue(cellularInterruptions.isEmpty())
        assertFalse(coordinator.isCallActive)
    }

    companion object {
        private val EARPIECE_ENDPOINT = TelecomEndpoint(
            id = "ep-earpiece",
            name = "Phone Earpiece",
            type = TelecomEndpointType.EARPIECE,
        )
        private val SPEAKER_ENDPOINT = TelecomEndpoint(
            id = "ep-speaker",
            name = "Speaker",
            type = TelecomEndpointType.SPEAKER,
        )
        private val BT_ENDPOINT = TelecomEndpoint(
            id = "ep-bt",
            name = "OpenRun Pro 2",
            type = TelecomEndpointType.BLUETOOTH,
        )
    }
}
