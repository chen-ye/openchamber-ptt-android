package com.openchamber.ptt.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallException
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TelecomEndpointType {
    EARPIECE,
    BLUETOOTH,
    WIRED_HEADSET,
    SPEAKER,
    STREAMING,
    UNKNOWN,
}

sealed interface TelecomCallStartResult {
    data class Ready(
        val generation: Long,
        val bluetoothEndpointActive: Boolean,
        val endpointType: TelecomEndpointType,
    ) : TelecomCallStartResult

    data class CancelledBeforeReady(
        val generation: Long,
        val byRemoteHangup: Boolean = false,
    ) : TelecomCallStartResult

    data class FailedCellularOrBusy(
        val errorCode: Int? = null,
        val reason: String? = null,
    ) : TelecomCallStartResult

    data class FailedUnavailable(
        val reason: String,
    ) : TelecomCallStartResult
}

internal enum class TelecomDisconnectCode {
    LOCAL,
    REMOTE,
    BUSY,
    CANCELED,
    REJECTED,
    ERROR,
    OTHER,
}

internal data class TelecomEndpoint(
    val id: String,
    val name: String,
    val type: TelecomEndpointType,
)

internal sealed interface TelecomControlOutcome {
    data object Success : TelecomControlOutcome
    data class Error(val errorCode: Int) : TelecomControlOutcome
}

internal interface TelecomCallSessionHandle {
    val scope: CoroutineScope
    val availableEndpoints: Flow<List<TelecomEndpoint>>
    val currentEndpoint: Flow<TelecomEndpoint>
    suspend fun setActive(): TelecomControlOutcome
    suspend fun requestEndpointChange(endpoint: TelecomEndpoint): TelecomControlOutcome
    suspend fun disconnect(cause: TelecomDisconnectCode): TelecomControlOutcome
}

internal interface TelecomCallPlatform {
    fun hasRequiredPermissions(): Boolean
    fun isTelecomSupported(): Boolean
    fun isCellularCallActive(): Boolean
    fun registerAppIfNeeded(): Result<Unit>
    suspend fun findStartingBluetoothEndpoint(timeoutMs: Long): TelecomEndpoint?
    suspend fun addOutgoingAudioCall(
        preferredBluetoothEndpoint: TelecomEndpoint?,
        onDisconnect: suspend (cause: TelecomDisconnectCode) -> Unit,
        onSetInactive: suspend () -> Unit,
        onCallScopeReady: (TelecomCallSessionHandle) -> Unit,
    )
}

private class AndroidTelecomCallPlatform(
    private val appContext: Context,
) : TelecomCallPlatform {
    private val audioManager: AudioManager? =
        appContext.getSystemService(AudioManager::class.java)
    private val registerLock = Any()
    private val registered = AtomicBoolean(false)
    private var callsManager: CallsManager? = null
    private val startingEndpointCache = ConcurrentHashMap<String, CallEndpointCompat>()

    override fun hasRequiredPermissions(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.MANAGE_OWN_CALLS,
        ) == PackageManager.PERMISSION_GRANTED

    override fun isTelecomSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            appContext.getSystemService(TelecomManager::class.java) != null

    override fun isCellularCallActive(): Boolean {
        val mode = audioManager?.let { runCatching { it.mode }.getOrNull() }
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
    }

    @SuppressLint("MissingPermission")
    override fun registerAppIfNeeded(): Result<Unit> {
        if (registered.get()) return Result.success(Unit)
        return synchronized(registerLock) {
            if (registered.get()) {
                Result.success(Unit)
            } else if (!isTelecomSupported()) {
                Result.failure(UnsupportedOperationException("Telecom unavailable on device"))
            } else if (!hasRequiredPermissions()) {
                Result.failure(SecurityException("MANAGE_OWN_CALLS permission required"))
            } else {
                runCatching {
                    val mgr = getOrCreateCallsManager()
                        ?: throw IllegalStateException("TelecomManager unavailable")
                    mgr.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
                    registered.set(true)
                }
            }
        }
    }

    override suspend fun findStartingBluetoothEndpoint(timeoutMs: Long): TelecomEndpoint? {
        if (!hasBluetoothAudioCandidate()) {
            return null
        }
        val mgr = getOrCreateCallsManager() ?: return null
        val found = try {
            withTimeoutOrNull(timeoutMs) {
                mgr.getAvailableStartingCallEndpoints()
                    .mapNotNull { endpoints ->
                        endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_BLUETOOTH }
                    }
                    .firstOrNull()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } ?: return null

        val mapped = found.toTelecomEndpoint()
        startingEndpointCache[mapped.id] = found
        return mapped
    }

    @SuppressLint("MissingPermission")
    override suspend fun addOutgoingAudioCall(
        preferredBluetoothEndpoint: TelecomEndpoint?,
        onDisconnect: suspend (cause: TelecomDisconnectCode) -> Unit,
        onSetInactive: suspend () -> Unit,
        onCallScopeReady: (TelecomCallSessionHandle) -> Unit,
    ) {
        val mgr = getOrCreateCallsManager()
            ?: throw IllegalStateException("TelecomManager unavailable")
        val startingBtEndpoint = preferredBluetoothEndpoint?.let { startingEndpointCache.remove(it.id) }
        startingEndpointCache.clear()

        val attributes = CallAttributesCompat(
            displayName = CALL_DISPLAY_NAME,
            address = Uri.parse(CALL_ADDRESS_URI),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = 0,
            preferredStartingCallEndpoint = startingBtEndpoint,
        )

        mgr.addCall(
            callAttributes = attributes,
            onAnswer = { _ -> },
            onDisconnect = { cause ->
                onDisconnect(mapDisconnectCause(cause))
            },
            onSetActive = {},
            onSetInactive = {
                onSetInactive()
            },
        ) {
            onCallScopeReady(AndroidCallSessionHandle(this))
        }
    }

    private fun getOrCreateCallsManager(): CallsManager? {
        if (!isTelecomSupported()) return null
        synchronized(registerLock) {
            callsManager?.let { return it }
            return runCatching { CallsManager(appContext) }
                .getOrNull()
                ?.also { callsManager = it }
        }
    }

    private fun hasBluetoothAudioCandidate(): Boolean {
        val mgr = audioManager ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mgr.availableCommunicationDevices.any { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                } || mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            } else {
                mgr.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }
        }.getOrDefault(false)
    }

    private class AndroidCallSessionHandle(
        private val callScope: CallControlScope,
    ) : TelecomCallSessionHandle {
        private val endpointCache = ConcurrentHashMap<String, CallEndpointCompat>()

        override val scope: CoroutineScope
            get() = callScope

        override val availableEndpoints: Flow<List<TelecomEndpoint>> =
            callScope.availableEndpoints.map { endpoints ->
                endpoints.map { compat ->
                    compat.toTelecomEndpoint().also { mapped ->
                        endpointCache[mapped.id] = compat
                    }
                }
            }

        override val currentEndpoint: Flow<TelecomEndpoint> =
            callScope.currentCallEndpoint.map { compat ->
                compat.toTelecomEndpoint()
            }

        override suspend fun setActive(): TelecomControlOutcome =
            mapControlResult(callScope.setActive())

        override suspend fun requestEndpointChange(endpoint: TelecomEndpoint): TelecomControlOutcome {
            val nativeEndpoint = endpointCache[endpoint.id]
                ?: return TelecomControlOutcome.Error(CallException.ERROR_BLUETOOTH_DEVICE_IS_NULL)
            return mapControlResult(callScope.requestEndpointChange(nativeEndpoint))
        }

        override suspend fun disconnect(cause: TelecomDisconnectCode): TelecomControlOutcome =
            mapControlResult(callScope.disconnect(toAndroidDisconnectCause(cause)))
    }

    companion object {
        private const val CALL_DISPLAY_NAME = "OpenChamber PTT"
        private const val CALL_ADDRESS_URI = "sip:ptt@openchamber.local"

        private fun CallEndpointCompat.toTelecomEndpoint(): TelecomEndpoint =
            TelecomEndpoint(
                id = identifier.toString(),
                name = name.toString(),
                type = when (type) {
                    CallEndpointCompat.TYPE_EARPIECE -> TelecomEndpointType.EARPIECE
                    CallEndpointCompat.TYPE_BLUETOOTH -> TelecomEndpointType.BLUETOOTH
                    CallEndpointCompat.TYPE_WIRED_HEADSET -> TelecomEndpointType.WIRED_HEADSET
                    CallEndpointCompat.TYPE_SPEAKER -> TelecomEndpointType.SPEAKER
                    CallEndpointCompat.TYPE_STREAMING -> TelecomEndpointType.STREAMING
                    else -> TelecomEndpointType.UNKNOWN
                },
            )

        private fun mapControlResult(result: CallControlResult): TelecomControlOutcome =
            when (result) {
                is CallControlResult.Success -> TelecomControlOutcome.Success
                is CallControlResult.Error -> TelecomControlOutcome.Error(result.errorCode)
            }

        private fun mapDisconnectCause(cause: DisconnectCause): TelecomDisconnectCode =
            when (cause.code) {
                DisconnectCause.LOCAL -> TelecomDisconnectCode.LOCAL
                DisconnectCause.REMOTE -> TelecomDisconnectCode.REMOTE
                DisconnectCause.BUSY -> TelecomDisconnectCode.BUSY
                DisconnectCause.CANCELED -> TelecomDisconnectCode.CANCELED
                DisconnectCause.REJECTED -> TelecomDisconnectCode.REJECTED
                DisconnectCause.ERROR -> TelecomDisconnectCode.ERROR
                else -> TelecomDisconnectCode.OTHER
            }

        private fun toAndroidDisconnectCause(code: TelecomDisconnectCode): DisconnectCause =
            when (code) {
                TelecomDisconnectCode.LOCAL -> DisconnectCause(DisconnectCause.LOCAL)
                TelecomDisconnectCode.REMOTE -> DisconnectCause(DisconnectCause.REMOTE)
                TelecomDisconnectCode.BUSY -> DisconnectCause(DisconnectCause.BUSY)
                TelecomDisconnectCode.CANCELED -> DisconnectCause(DisconnectCause.CANCELED)
                TelecomDisconnectCode.REJECTED -> DisconnectCause(DisconnectCause.REJECTED)
                TelecomDisconnectCode.ERROR -> DisconnectCause(DisconnectCause.ERROR)
                TelecomDisconnectCode.OTHER -> DisconnectCause(DisconnectCause.OTHER)
            }
    }
}

class TelecomCaptureCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val platform: TelecomCallPlatform,
    private val btEndpointTimeoutMs: Long = DEFAULT_BT_ENDPOINT_TIMEOUT_MS,
    private val startingEndpointLookupTimeoutMs: Long = DEFAULT_STARTING_ENDPOINT_LOOKUP_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onRemoteHangupStop: (generation: Long) -> Unit = {},
    private val onCellularOrSystemInterruption: (generation: Long) -> Unit = {},
) {
    constructor(
        context: Context,
        scope: CoroutineScope,
        onRemoteHangupStop: (generation: Long) -> Unit = {},
        onCellularOrSystemInterruption: (generation: Long) -> Unit = {},
    ) : this(
        scope = scope,
        platform = AndroidTelecomCallPlatform(context.applicationContext),
        onRemoteHangupStop = onRemoteHangupStop,
        onCellularOrSystemInterruption = onCellularOrSystemInterruption,
    )

    private class CallTicket(
        val generation: Long,
        val startupDeferred: CompletableDeferred<TelecomCallStartResult> = CompletableDeferred(),
        val endpointReadyDeferred: CompletableDeferred<TelecomEndpoint?> = CompletableDeferred(),
        var stopRequested: Boolean = false,
        var appInitiatedDisconnect: Boolean = false,
        var disconnectNotified: Boolean = false,
        var readyReturned: Boolean = false,
        var setActiveSucceeded: Boolean = false,
        var sessionHandle: TelecomCallSessionHandle? = null,
        var latestEndpoint: TelecomEndpoint? = null,
        var latestBtEndpoint: TelecomEndpoint? = null,
        var lastRequestedBtEndpointId: String? = null,
        val collectorJobs: MutableList<Job> = mutableListOf(),
    )

    private val stateLock = Any()
    private val startMutex = Mutex()
    private val activeCallFlag = AtomicBoolean(false)
    private val nextGeneration = AtomicLong(0L)
    private val startRequestSeq = AtomicLong(0L)

    private var currentTicket: CallTicket? = null
    private var activeCallJob: Job? = null

    @Volatile
    var onDiagnosticEvent: ((event: String, outcome: String, details: Map<String, String>) -> Unit)? = null

    val isCallActive: Boolean
        get() = activeCallFlag.get()

    internal fun isCurrentGeneration(generation: Long): Boolean =
        nextGeneration.get() == generation

    suspend fun startCaptureCall(): TelecomCallStartResult {
        val myStartSeq = startRequestSeq.incrementAndGet()
        requestStopForSupersede()

        return startMutex.withLock {
            if (startRequestSeq.get() != myStartSeq) {
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = "IGNORED",
                    details = mapOf("reason" to "superseded_before_start"),
                )
                return@withLock TelecomCallStartResult.CancelledBeforeReady(0L)
            }

            val prevJob = synchronized(stateLock) { activeCallJob }
            if (prevJob != null) {
                requestStopForSupersede()
                withTimeoutOrNull(PREV_CALL_DISCONNECT_WAIT_MS) {
                    prevJob.join()
                }
                if (startRequestSeq.get() != myStartSeq) {
                    emitDiagnostic(
                        event = "startCaptureCall",
                        outcome = "IGNORED",
                        details = mapOf("reason" to "superseded_after_prev_join"),
                    )
                    return@withLock TelecomCallStartResult.CancelledBeforeReady(0L)
                }
            }

            if (!platform.isTelecomSupported()) {
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = "ERROR",
                    details = mapOf("reason" to "telecom_unsupported"),
                )
                return@withLock TelecomCallStartResult.FailedUnavailable("Telecom unavailable on device")
            }

            if (!platform.hasRequiredPermissions()) {
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = "ERROR",
                    details = mapOf("reason" to "missing_manage_own_calls"),
                )
                return@withLock TelecomCallStartResult.FailedUnavailable("MANAGE_OWN_CALLS permission required")
            }

            if (platform.isCellularCallActive()) {
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = "ERROR",
                    details = mapOf("reason" to "cellular_call_active"),
                )
                return@withLock TelecomCallStartResult.FailedCellularOrBusy(reason = "cellular_call_active")
            }

            val registerResult = platform.registerAppIfNeeded()
            if (registerResult.isFailure) {
                val errMsg = registerResult.exceptionOrNull()?.message ?: "registerAppWithTelecom failed"
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = "ERROR",
                    details = mapOf("reason" to "register_app_failed", "error" to errMsg),
                )
                return@withLock TelecomCallStartResult.FailedUnavailable(errMsg)
            }

            val startingBt = platform.findStartingBluetoothEndpoint(startingEndpointLookupTimeoutMs)
            if (startRequestSeq.get() != myStartSeq) {
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = "IGNORED",
                    details = mapOf("reason" to "superseded_after_endpoint_lookup"),
                )
                return@withLock TelecomCallStartResult.CancelledBeforeReady(0L)
            }

            val generation = nextGeneration.incrementAndGet()
            val ticket = CallTicket(generation = generation)
            synchronized(stateLock) {
                currentTicket = ticket
            }

            emitDiagnostic(
                event = "startCaptureCall",
                outcome = "STATE_CHANGE",
                details = mapOf(
                    "generation" to generation.toString(),
                    "hasStartingBt" to (startingBt != null).toString(),
                ),
            )

            var jobRef: Job? = null
            val callJob = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
                try {
                    runOutgoingCall(ticket, startingBt)
                } finally {
                    val collectorsToCancel: List<Job>
                    synchronized(stateLock) {
                        if (currentTicket === ticket) {
                            activeCallFlag.set(false)
                            currentTicket = null
                        }
                        if (activeCallJob === jobRef) {
                            activeCallJob = null
                        }
                        collectorsToCancel = ticket.collectorJobs.toList()
                        ticket.collectorJobs.clear()
                    }
                    collectorsToCancel.forEach { it.cancel() }
                }
            }
            jobRef = callJob
            synchronized(stateLock) {
                activeCallJob = callJob
            }
            callJob.start()

            try {
                val result = ticket.startupDeferred.await()
                emitDiagnostic(
                    event = "startCaptureCall",
                    outcome = when (result) {
                        is TelecomCallStartResult.Ready -> "ACCEPTED"
                        is TelecomCallStartResult.CancelledBeforeReady -> "IGNORED"
                        is TelecomCallStartResult.FailedCellularOrBusy,
                        is TelecomCallStartResult.FailedUnavailable,
                        -> "ERROR"
                    },
                    details = when (result) {
                        is TelecomCallStartResult.Ready -> mapOf(
                            "generation" to result.generation.toString(),
                            "bluetoothEndpointActive" to result.bluetoothEndpointActive.toString(),
                            "endpointType" to result.endpointType.name,
                        )
                        is TelecomCallStartResult.CancelledBeforeReady -> mapOf(
                            "generation" to result.generation.toString(),
                            "byRemoteHangup" to result.byRemoteHangup.toString(),
                            "reason" to if (result.byRemoteHangup) "remote_hangup_before_ready" else "cancelled_before_ready",
                        )
                        is TelecomCallStartResult.FailedCellularOrBusy -> mapOf(
                            "generation" to generation.toString(),
                            "errorCode" to (result.errorCode?.toString() ?: "none"),
                            "reason" to (result.reason ?: "cellular_or_busy"),
                        )
                        is TelecomCallStartResult.FailedUnavailable -> mapOf(
                            "generation" to generation.toString(),
                            "reason" to result.reason,
                        )
                    },
                )
                result
            } catch (ce: CancellationException) {
                endCaptureCall(ticket.generation)
                throw ce
            }
        }
    }

    fun endCaptureCall(generation: Long? = null) {
        val handleToDisconnect: TelecomCallSessionHandle?
        val ticketToStop: CallTicket?
        val collectorsToCancel: List<Job>
        val ignoreReason: String?
        val hadHandle: Boolean
        val stopRequestedAlready: Boolean

        synchronized(stateLock) {
            val ticket = currentTicket
            if (ticket == null) {
                ignoreReason = "no_active_call"
                ticketToStop = null
                handleToDisconnect = null
                collectorsToCancel = emptyList()
                hadHandle = false
                stopRequestedAlready = false
            } else if (generation != null && ticket.generation != generation) {
                ignoreReason = "stale_generation"
                ticketToStop = null
                handleToDisconnect = null
                collectorsToCancel = emptyList()
                hadHandle = false
                stopRequestedAlready = false
            } else {
                ignoreReason = null
                ticketToStop = ticket
                stopRequestedAlready = ticket.stopRequested
                ticket.stopRequested = true
                ticket.appInitiatedDisconnect = true
                activeCallFlag.set(false)
                handleToDisconnect = ticket.sessionHandle
                hadHandle = handleToDisconnect != null
                collectorsToCancel = ticket.collectorJobs.toList()
                ticket.collectorJobs.clear()
            }
        }

        if (ignoreReason != null) {
            emitDiagnostic(
                event = "endCaptureCall",
                outcome = "IGNORED",
                details = buildMap {
                    put("reason", ignoreReason)
                    if (generation != null) {
                        put("requestedGeneration", generation.toString())
                    }
                },
            )
            return
        }

        collectorsToCancel.forEach { it.cancel() }
        ticketToStop?.endpointReadyDeferred?.complete(null)
        ticketToStop?.startupDeferred?.complete(
            TelecomCallStartResult.CancelledBeforeReady(ticketToStop.generation),
        )

        emitDiagnostic(
            event = "endCaptureCall",
            outcome = "STATE_CHANGE",
            details = mapOf(
                "generation" to ticketToStop!!.generation.toString(),
                "hadHandle" to hadHandle.toString(),
                "stopRequestedAlready" to stopRequestedAlready.toString(),
            ),
        )

        if (handleToDisconnect != null) {
            scope.launch(ioDispatcher) {
                withContext(NonCancellable) {
                    runCatching { handleToDisconnect.disconnect(TelecomDisconnectCode.LOCAL) }
                }
            }
        }
    }

    fun release() {
        endCaptureCall(generation = null)
    }

    private fun requestStopForSupersede() {
        val ticketToStop: CallTicket?
        val handleToDisconnect: TelecomCallSessionHandle?
        val collectorsToCancel: List<Job>
        synchronized(stateLock) {
            val ticket = currentTicket
            if (ticket == null) {
                ticketToStop = null
                handleToDisconnect = null
                collectorsToCancel = emptyList()
            } else {
                ticketToStop = ticket
                ticket.stopRequested = true
                ticket.appInitiatedDisconnect = true
                activeCallFlag.set(false)
                handleToDisconnect = ticket.sessionHandle
                collectorsToCancel = ticket.collectorJobs.toList()
                ticket.collectorJobs.clear()
            }
        }
        if (ticketToStop != null) {
            collectorsToCancel.forEach { it.cancel() }
            ticketToStop.endpointReadyDeferred.complete(null)
            ticketToStop.startupDeferred.complete(
                TelecomCallStartResult.CancelledBeforeReady(ticketToStop.generation),
            )
            if (handleToDisconnect != null) {
                scope.launch(ioDispatcher) {
                    withContext(NonCancellable) {
                        runCatching { handleToDisconnect.disconnect(TelecomDisconnectCode.LOCAL) }
                    }
                }
            }
        }
    }

    private suspend fun runOutgoingCall(
        ticket: CallTicket,
        startingBt: TelecomEndpoint?,
    ) {
        try {
            platform.addOutgoingAudioCall(
                preferredBluetoothEndpoint = startingBt,
                onDisconnect = { cause -> handlePlatformDisconnect(ticket, cause) },
                onSetInactive = { handlePlatformSetInactive(ticket) },
                onCallScopeReady = { handle -> handleCallScopeReady(ticket, handle) },
            )
            val alreadyCompleted = synchronized(stateLock) {
                if (currentTicket === ticket) {
                    activeCallFlag.set(false)
                }
                ticket.startupDeferred.isCompleted
            }
            if (!alreadyCompleted) {
                ticket.startupDeferred.complete(
                    TelecomCallStartResult.CancelledBeforeReady(ticket.generation),
                )
            }
        } catch (ce: CancellationException) {
            synchronized(stateLock) {
                if (currentTicket === ticket) {
                    activeCallFlag.set(false)
                }
            }
            ticket.startupDeferred.complete(
                TelecomCallStartResult.CancelledBeforeReady(ticket.generation),
            )
            throw ce
        } catch (t: Throwable) {
            val stopWasRequested = synchronized(stateLock) {
                if (currentTicket === ticket) {
                    activeCallFlag.set(false)
                }
                ticket.stopRequested
            }
            val result = when {
                stopWasRequested -> TelecomCallStartResult.CancelledBeforeReady(ticket.generation)
                t is CallException -> TelecomCallStartResult.FailedCellularOrBusy(
                    errorCode = t.code,
                    reason = "call_exception_${t.code}",
                )
                t is SecurityException -> TelecomCallStartResult.FailedUnavailable(
                    reason = t.message ?: "SecurityException",
                )
                else -> TelecomCallStartResult.FailedUnavailable(
                    reason = t.message ?: t::class.java.simpleName,
                )
            }
            ticket.startupDeferred.complete(result)
            emitDiagnostic(
                event = "addCall",
                outcome = if (stopWasRequested) "IGNORED" else "ERROR",
                details = mapOf(
                    "generation" to ticket.generation.toString(),
                    "error" to (t.message ?: t::class.java.simpleName),
                ),
            )
        }
    }

    private fun handleCallScopeReady(
        ticket: CallTicket,
        handle: TelecomCallSessionHandle,
    ) {
        val abortImmediately: Boolean
        synchronized(stateLock) {
            ticket.sessionHandle = handle
            abortImmediately = ticket.stopRequested || currentTicket !== ticket
            if (!abortImmediately) {
                activeCallFlag.set(true)
            }
        }

        if (abortImmediately) {
            ticket.endpointReadyDeferred.complete(null)
            ticket.startupDeferred.complete(
                TelecomCallStartResult.CancelledBeforeReady(ticket.generation),
            )
            emitDiagnostic(
                event = "callScopeReady",
                outcome = "IGNORED",
                details = mapOf(
                    "generation" to ticket.generation.toString(),
                    "reason" to "stop_requested_before_scope",
                ),
            )
            handle.scope.launch(ioDispatcher) {
                withContext(NonCancellable) {
                    runCatching { handle.disconnect(TelecomDisconnectCode.LOCAL) }
                }
            }
            return
        }

        emitDiagnostic(
            event = "callScopeReady",
            outcome = "ACCEPTED",
            details = mapOf("generation" to ticket.generation.toString()),
        )

        val currentEpJob = handle.scope.launch(ioDispatcher) {
            handle.currentEndpoint.collect { endpoint ->
                val btToComplete: TelecomEndpoint?
                val diagDetails: Map<String, String>
                synchronized(stateLock) {
                    ticket.latestEndpoint = endpoint
                    btToComplete = if (endpoint.type == TelecomEndpointType.BLUETOOTH &&
                        ticket.setActiveSucceeded &&
                        !ticket.stopRequested
                    ) {
                        endpoint
                    } else {
                        null
                    }
                    diagDetails = mapOf(
                        "generation" to ticket.generation.toString(),
                        "endpointType" to endpoint.type.name,
                        "endpointName" to endpoint.name,
                    )
                }
                if (btToComplete != null) {
                    ticket.endpointReadyDeferred.complete(btToComplete)
                }
                emitDiagnostic("currentCallEndpoint", "STATE_CHANGE", diagDetails)
            }
        }

        val availableEpJob = handle.scope.launch(ioDispatcher) {
            handle.availableEndpoints.collect { endpoints ->
                val bt = endpoints.firstOrNull { it.type == TelecomEndpointType.BLUETOOTH }
                var btToRequest: TelecomEndpoint? = null
                synchronized(stateLock) {
                    ticket.latestBtEndpoint = bt
                    if (bt != null &&
                        ticket.setActiveSucceeded &&
                        !ticket.stopRequested &&
                        ticket.latestEndpoint?.type != TelecomEndpointType.BLUETOOTH &&
                        ticket.lastRequestedBtEndpointId != bt.id
                    ) {
                        ticket.lastRequestedBtEndpointId = bt.id
                        btToRequest = bt
                    }
                }
                if (btToRequest != null) {
                    requestBtEndpointSwitch(ticket, handle, btToRequest)
                }
            }
        }

        synchronized(stateLock) {
            ticket.collectorJobs += currentEpJob
            ticket.collectorJobs += availableEpJob
        }

        handle.scope.launch(ioDispatcher) {
            activateAndAwaitEndpoint(ticket, handle)
        }
    }

    private suspend fun activateAndAwaitEndpoint(
        ticket: CallTicket,
        handle: TelecomCallSessionHandle,
    ) {
        val activeRes = handle.setActive()
        if (activeRes is TelecomControlOutcome.Error) {
            val collectorsToCancel: List<Job>
            synchronized(stateLock) {
                if (currentTicket === ticket) {
                    activeCallFlag.set(false)
                }
                ticket.stopRequested = true
                ticket.appInitiatedDisconnect = true
                collectorsToCancel = ticket.collectorJobs.toList()
                ticket.collectorJobs.clear()
            }
            collectorsToCancel.forEach { it.cancel() }
            ticket.endpointReadyDeferred.complete(null)
            ticket.startupDeferred.complete(
                TelecomCallStartResult.FailedCellularOrBusy(
                    errorCode = activeRes.errorCode,
                    reason = "setActive_error_${activeRes.errorCode}",
                ),
            )
            emitDiagnostic(
                event = "setActive",
                outcome = "ERROR",
                details = mapOf(
                    "generation" to ticket.generation.toString(),
                    "errorCode" to activeRes.errorCode.toString(),
                ),
            )
            withContext(NonCancellable) {
                runCatching { handle.disconnect(TelecomDisconnectCode.LOCAL) }
            }
            return
        }

        emitDiagnostic(
            event = "setActive",
            outcome = "ACCEPTED",
            details = mapOf("generation" to ticket.generation.toString()),
        )

        var btToRequest: TelecomEndpoint? = null
        var alreadyBt: TelecomEndpoint? = null
        val abortedAfterSetActive: Boolean
        synchronized(stateLock) {
            abortedAfterSetActive = ticket.stopRequested || currentTicket !== ticket
            if (!abortedAfterSetActive) {
                ticket.setActiveSucceeded = true
                val currentEp = ticket.latestEndpoint
                if (currentEp?.type == TelecomEndpointType.BLUETOOTH) {
                    alreadyBt = currentEp
                } else {
                    val availBt = ticket.latestBtEndpoint
                    if (availBt != null && ticket.lastRequestedBtEndpointId != availBt.id) {
                        ticket.lastRequestedBtEndpointId = availBt.id
                        btToRequest = availBt
                    }
                }
            }
        }

        if (abortedAfterSetActive) {
            synchronized(stateLock) {
                if (currentTicket === ticket) {
                    activeCallFlag.set(false)
                }
            }
            ticket.endpointReadyDeferred.complete(null)
            ticket.startupDeferred.complete(
                TelecomCallStartResult.CancelledBeforeReady(ticket.generation),
            )
            withContext(NonCancellable) {
                runCatching { handle.disconnect(TelecomDisconnectCode.LOCAL) }
            }
            return
        }

        if (alreadyBt != null) {
            ticket.endpointReadyDeferred.complete(alreadyBt)
        } else if (btToRequest != null) {
            requestBtEndpointSwitch(ticket, handle, btToRequest)
        }

        val btEndpoint = withTimeoutOrNull(btEndpointTimeoutMs) {
            ticket.endpointReadyDeferred.await()
        }

        val finalResult: TelecomCallStartResult
        var shouldDisconnect = false
        synchronized(stateLock) {
            if (ticket.stopRequested || currentTicket !== ticket) {
                if (currentTicket === ticket) {
                    activeCallFlag.set(false)
                }
                finalResult = TelecomCallStartResult.CancelledBeforeReady(ticket.generation)
                shouldDisconnect = !ticket.appInitiatedDisconnect && !ticket.disconnectNotified
                ticket.appInitiatedDisconnect = true
            } else {
                val effectiveType = btEndpoint?.type
                    ?: ticket.latestEndpoint?.type
                    ?: TelecomEndpointType.UNKNOWN
                val btActive = effectiveType == TelecomEndpointType.BLUETOOTH
                ticket.readyReturned = true
                finalResult = TelecomCallStartResult.Ready(
                    generation = ticket.generation,
                    bluetoothEndpointActive = btActive,
                    endpointType = effectiveType,
                )
            }
        }

        if (shouldDisconnect) {
            withContext(NonCancellable) {
                runCatching { handle.disconnect(TelecomDisconnectCode.LOCAL) }
            }
        }

        if (finalResult is TelecomCallStartResult.Ready) {
            emitDiagnostic(
                event = "btEndpointWait",
                outcome = if (finalResult.bluetoothEndpointActive) "ACCEPTED" else "STATE_CHANGE",
                details = buildMap {
                    put("generation", ticket.generation.toString())
                    put("endpointType", finalResult.endpointType.name)
                    if (!finalResult.bluetoothEndpointActive) {
                        put("reason", "timeout_fallback")
                        put("timeoutMs", btEndpointTimeoutMs.toString())
                    }
                },
            )
        }

        ticket.startupDeferred.complete(finalResult)
    }

    private suspend fun requestBtEndpointSwitch(
        ticket: CallTicket,
        handle: TelecomCallSessionHandle,
        btEndpoint: TelecomEndpoint,
    ) {
        val outcome = runCatching {
            handle.requestEndpointChange(btEndpoint)
        }.getOrElse {
            TelecomControlOutcome.Error(CallException.ERROR_UNKNOWN)
        }
        emitDiagnostic(
            event = "requestEndpointChange",
            outcome = if (outcome is TelecomControlOutcome.Success) "ACCEPTED" else "ERROR",
            details = buildMap {
                put("generation", ticket.generation.toString())
                put("endpointId", btEndpoint.id)
                put("endpointName", btEndpoint.name)
                if (outcome is TelecomControlOutcome.Error) {
                    put("errorCode", outcome.errorCode.toString())
                }
            },
        )
    }

    private fun handlePlatformSetInactive(ticket: CallTicket) {
        val handleToDisconnect: TelecomCallSessionHandle?
        val ignoreReason: String?
        val collectorsToCancel: List<Job>
        synchronized(stateLock) {
            if (currentTicket === ticket) {
                activeCallFlag.set(false)
            }
            if (ticket.appInitiatedDisconnect) {
                ignoreReason = "app_initiated"
                handleToDisconnect = null
                collectorsToCancel = emptyList()
            } else if (ticket.disconnectNotified) {
                ignoreReason = "already_notified"
                handleToDisconnect = null
                collectorsToCancel = emptyList()
            } else {
                ignoreReason = null
                ticket.disconnectNotified = true
                ticket.stopRequested = true
                ticket.appInitiatedDisconnect = true
                handleToDisconnect = ticket.sessionHandle
                collectorsToCancel = ticket.collectorJobs.toList()
                ticket.collectorJobs.clear()
            }
        }
        if (ignoreReason != null) {
            emitDiagnostic(
                event = "onSetInactive",
                outcome = "IGNORED",
                details = mapOf(
                    "generation" to ticket.generation.toString(),
                    "reason" to ignoreReason,
                ),
            )
            return
        }

        collectorsToCancel.forEach { it.cancel() }
        ticket.endpointReadyDeferred.complete(null)
        ticket.startupDeferred.complete(
            TelecomCallStartResult.FailedCellularOrBusy(reason = "telecom_on_set_inactive"),
        )
        emitDiagnostic(
            event = "onSetInactive",
            outcome = "ACCEPTED",
            details = mapOf("generation" to ticket.generation.toString()),
        )
        if (handleToDisconnect != null) {
            scope.launch(ioDispatcher) {
                withContext(NonCancellable) {
                    runCatching { handleToDisconnect.disconnect(TelecomDisconnectCode.LOCAL) }
                }
            }
        }
        onCellularOrSystemInterruption(ticket.generation)
    }

    private fun handlePlatformDisconnect(
        ticket: CallTicket,
        cause: TelecomDisconnectCode,
    ) {
        val cellularActive = platform.isCellularCallActive()
        val ignoreReason: String?
        val wasReady: Boolean
        val isInterruption: Boolean
        val collectorsToCancel: List<Job>
        synchronized(stateLock) {
            if (currentTicket === ticket) {
                activeCallFlag.set(false)
            }
            if (ticket.appInitiatedDisconnect) {
                ignoreReason = "app_initiated"
                wasReady = false
                isInterruption = false
                collectorsToCancel = emptyList()
            } else if (ticket.disconnectNotified) {
                ignoreReason = "already_notified"
                wasReady = false
                isInterruption = false
                collectorsToCancel = emptyList()
            } else {
                ignoreReason = null
                ticket.disconnectNotified = true
                ticket.stopRequested = true
                wasReady = ticket.readyReturned
                isInterruption = cellularActive ||
                    cause == TelecomDisconnectCode.BUSY ||
                    cause == TelecomDisconnectCode.CANCELED
                collectorsToCancel = ticket.collectorJobs.toList()
                ticket.collectorJobs.clear()
            }
        }
        if (ignoreReason != null) {
            emitDiagnostic(
                event = "onDisconnect",
                outcome = "IGNORED",
                details = mapOf(
                    "generation" to ticket.generation.toString(),
                    "cause" to cause.name,
                    "reason" to ignoreReason,
                ),
            )
            return
        }

        val failedUnavailableBeforeReady = !isInterruption &&
            !wasReady &&
            (cause == TelecomDisconnectCode.REJECTED || cause == TelecomDisconnectCode.ERROR)
        val remotePreReadyHangup = !isInterruption &&
            !wasReady &&
            (cause == TelecomDisconnectCode.LOCAL || cause == TelecomDisconnectCode.REMOTE)

        collectorsToCancel.forEach { it.cancel() }
        ticket.endpointReadyDeferred.complete(null)
        if (!ticket.startupDeferred.isCompleted) {
            val startupResult = when {
                isInterruption -> TelecomCallStartResult.FailedCellularOrBusy(
                    reason = "disconnected_${cause.name.lowercase()}",
                )
                failedUnavailableBeforeReady -> TelecomCallStartResult.FailedUnavailable(
                    reason = "disconnected_${cause.name.lowercase()}",
                )
                else -> TelecomCallStartResult.CancelledBeforeReady(
                    generation = ticket.generation,
                    byRemoteHangup = remotePreReadyHangup,
                )
            }
            ticket.startupDeferred.complete(startupResult)
        }

        val remoteHangup = !isInterruption &&
            wasReady &&
            (cause == TelecomDisconnectCode.LOCAL || cause == TelecomDisconnectCode.REMOTE)
        val interruptionSignal = isInterruption || (wasReady && !remoteHangup)
        emitDiagnostic(
            event = "onDisconnect",
            outcome = "ACCEPTED",
            details = mapOf(
                "generation" to ticket.generation.toString(),
                "cause" to cause.name,
                "wasReady" to wasReady.toString(),
                "cellularActive" to cellularActive.toString(),
                "isInterruption" to interruptionSignal.toString(),
                "remoteHangup" to remoteHangup.toString(),
                "byRemoteHangup" to remotePreReadyHangup.toString(),
            ),
        )
        if (interruptionSignal) {
            onCellularOrSystemInterruption(ticket.generation)
        } else if (remoteHangup) {
            onRemoteHangupStop(ticket.generation)
        }
    }

    private fun emitDiagnostic(
        event: String,
        outcome: String,
        details: Map<String, String>,
    ) {
        onDiagnosticEvent?.invoke(event, outcome, details)
    }

    companion object {
        internal const val DEFAULT_BT_ENDPOINT_TIMEOUT_MS = 1_200L
        internal const val DEFAULT_STARTING_ENDPOINT_LOOKUP_TIMEOUT_MS = 150L
        private const val PREV_CALL_DISCONNECT_WAIT_MS = 5_500L
    }
}
