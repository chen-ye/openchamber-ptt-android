package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min

sealed interface TurnEvent {
    data object Completed : TurnEvent
    data class Failed(val message: String = "") : TurnEvent
    data class Interrupted(val reason: String = "user") : TurnEvent
    data class PermissionAsked(val tool: String) : TurnEvent
    data object PermissionResolved : TurnEvent
}

class EventStreamClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
    private val initialReconnectDelayMs: Long = DEFAULT_INITIAL_RECONNECT_DELAY_MS,
    private val maxReconnectDelayMs: Long = DEFAULT_MAX_RECONNECT_DELAY_MS,
) {
    fun observeTurnEvents(
        serverUrl: String,
        targetSessionId: String,
        onReady: () -> Unit = {},
    ): Flow<TurnEvent> = callbackFlow {
        val baseWsUrl = toWebSocketBaseUrl(serverUrl)
        var lastEventId: String? = null
        var attempt = 0
        var activeWebSocket: WebSocket? = null
        var readyNotified = false

        while (currentCoroutineContext().isActive) {
            val closedSignal = CompletableDeferred<Unit>()
            val url = buildStreamUrl(baseWsUrl, lastEventId)
            val request = OpenChamberHttp.buildWebSocketRequest(url)

            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val parsed = parseFrame(text, targetSessionId)
                    if (parsed.isReady) {
                        attempt = 0
                        if (parsed.replayReset) {
                            lastEventId = null
                        }
                        if (!readyNotified) {
                            readyNotified = true
                            onReady()
                        }
                        return
                    }
                    if (parsed.eventId != null) {
                        attempt = 0
                        lastEventId = parsed.eventId
                    }
                    parsed.turnEvent?.let { event ->
                        trySend(event)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closedSignal.complete(Unit)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closedSignal.complete(Unit)
                }
            }

            val socket = httpClient.newWebSocket(request, listener)
            activeWebSocket = socket
            try {
                closedSignal.await()
            } finally {
                socket.cancel()
                activeWebSocket = null
            }

            if (!currentCoroutineContext().isActive) break

            val backoffMs = computeBackoffDelayMs(attempt)
            attempt += 1
            if (backoffMs > 0L) {
                delay(backoffMs)
            }
        }

        awaitClose {
            activeWebSocket?.cancel()
            activeWebSocket = null
        }
    }.buffer(Channel.UNLIMITED)

    internal fun buildStreamUrl(baseWsUrl: String, lastEventId: String?): String {
        val cleanedId = lastEventId?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanedId == null) {
            "$baseWsUrl$GLOBAL_EVENT_WS_PATH"
        } else {
            "$baseWsUrl$GLOBAL_EVENT_WS_PATH?lastEventId=${OpenChamberHttp.encodeUriComponent(cleanedId)}"
        }
    }

    internal fun parseFrame(rawText: String, targetSessionId: String): ParsedFrame {
        val root = runCatching { json.parseToJsonElement(rawText) as? JsonObject }.getOrNull()
            ?: return ParsedFrame()

        val frameType = root.str("type")
        if (frameType == "ready") {
            val replayReset = root.bool("replayReset") == true
            return ParsedFrame(isReady = true, replayReset = replayReset)
        }
        if (frameType == "backpressure" || frameType == "error" || frameType == HEARTBEAT_TYPE) {
            return ParsedFrame()
        }

        val payload: JsonObject = if (frameType == "event") {
            root["payload"] as? JsonObject ?: return ParsedFrame()
        } else {
            root
        }

        val eventType = payload.str("type") ?: return ParsedFrame()
        if (eventType == HEARTBEAT_TYPE) {
            return ParsedFrame()
        }

        val eventId = root.str("eventId")?.takeIf { it.isNotBlank() }
            ?: payload.str("id")?.takeIf { it.isNotBlank() }

        val data = (payload["data"] as? JsonObject)
            ?: (payload["properties"] as? JsonObject)

        val sessionId = data?.str("sessionID")
            ?: data?.str("sessionId")
            ?: payload.str("sessionID")
            ?: payload.str("sessionId")

        if (sessionId != targetSessionId) {
            return ParsedFrame(eventId = eventId)
        }

        val turnEvent = when (eventType) {
            "session.execution.succeeded" -> TurnEvent.Completed

            "session.idle" -> {
                val aborted = data?.bool("aborted") == true
                val outcome = data?.str("outcome")
                if (aborted || outcome == "interrupted") {
                    val reason = data.str("reason")?.takeIf { it.isNotBlank() } ?: "user"
                    TurnEvent.Interrupted(reason)
                } else {
                    TurnEvent.Completed
                }
            }

            "session.execution.failed", "session.error" -> {
                val message = extractErrorMessage(data)
                TurnEvent.Failed(message)
            }

            "session.execution.interrupted" -> {
                val reason = data?.str("reason")?.trim().orEmpty()
                if (reason == "shutdown") {
                    null
                } else {
                    TurnEvent.Interrupted(reason.ifEmpty { "user" })
                }
            }

            "permission.asked" -> {
                val tool = data?.str("action")?.takeIf { it.isNotBlank() }
                    ?: data?.str("tool")?.takeIf { it.isNotBlank() }
                    ?: data?.str("permission")?.takeIf { it.isNotBlank() }
                    ?: "unknown"
                TurnEvent.PermissionAsked(tool)
            }

            "permission.replied", "permission.resolved" -> TurnEvent.PermissionResolved

            "openchamber:session-status" -> {
                val status = data?.str("status")
                    ?: (data?.get("status") as? JsonObject)?.str("type")
                if (status == "idle") TurnEvent.Completed else null
            }

            else -> null
        }

        return ParsedFrame(
            eventId = eventId,
            turnEvent = turnEvent,
        )
    }

    private fun extractErrorMessage(data: JsonObject?): String {
        val errorElement = data?.get("error") ?: return "Execution failed"
        return when (errorElement) {
            is JsonPrimitive -> errorElement.contentOrNull?.takeIf { it.isNotBlank() } ?: "Execution failed"
            is JsonObject -> {
                errorElement.str("message")?.takeIf { it.isNotBlank() }
                    ?: errorElement.str("type")?.takeIf { it.isNotBlank() }
                    ?: errorElement.str("name")?.takeIf { it.isNotBlank() }
                    ?: "Execution failed"
            }
            else -> "Execution failed"
        }
    }

    private fun computeBackoffDelayMs(attempt: Int): Long {
        if (initialReconnectDelayMs <= 0L) return 0L
        val shift = attempt.coerceAtMost(10)
        val scaled = initialReconnectDelayMs * (1L shl shift)
        return min(scaled, maxReconnectDelayMs)
    }

    internal data class ParsedFrame(
        val isReady: Boolean = false,
        val replayReset: Boolean = false,
        val eventId: String? = null,
        val turnEvent: TurnEvent? = null,
    )

    companion object {
        const val GLOBAL_EVENT_WS_PATH = "/api/global/event/ws"
        const val HEARTBEAT_TYPE = "openchamber:heartbeat"
        private const val DEFAULT_INITIAL_RECONNECT_DELAY_MS = 250L
        private const val DEFAULT_MAX_RECONNECT_DELAY_MS = 5_000L

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull
