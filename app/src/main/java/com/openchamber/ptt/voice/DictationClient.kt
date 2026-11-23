package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64
import java.util.UUID
import kotlin.math.max

class DictationException(
    message: String,
    val retryable: Boolean = false,
    val reasonCode: String? = null,
    val isEmptyTranscript: Boolean = false,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
data class DictationOptions(
    val provider: String = "local",
    val language: String = "en",
    val correct: Boolean = false,
)

interface DictationSession {
    val dictationId: String
    val lastAckSeq: Int
    suspend fun sendChunk(pcm16le: ByteArray)
    suspend fun finish(): String
    suspend fun cancel()
}

class DictationClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
    private val defaultFinalTimeoutMs: Long = DEFAULT_FINAL_TIMEOUT_MS,
) {
    suspend fun startSession(
        serverUrl: String,
        options: DictationOptions = DictationOptions(),
        dictationId: String = UUID.randomUUID().toString(),
        onPartial: (String) -> Unit = {},
    ): DictationSession {
        val wsUrl = "${toWebSocketBaseUrl(serverUrl)}$DICTATION_WS_PATH"
        val request = OpenChamberHttp.buildWebSocketRequest(wsUrl)

        val streamStartedDeferred = CompletableDeferred<Unit>()
        val finalDeferred = CompletableDeferred<String>()
        val finishAcceptedDeferred = CompletableDeferred<Long>()
        val session = ActiveDictationSession(
            dictationId = dictationId,
            json = json,
            finalDeferred = finalDeferred,
            finishAcceptedDeferred = finishAcceptedDeferred,
            defaultFinalTimeoutMs = defaultFinalTimeoutMs,
        )

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val type = frame.stringField("type") ?: return
                val msgDictationId = frame.stringField("dictationId")

                when (type) {
                    "ready" -> {
                        val startPayload = json.encodeToString(
                            DictationStartFrame(
                                dictationId = dictationId,
                                format = PCM_16K_16BIT_FORMAT,
                                options = options,
                            ),
                        )
                        val sent = session.sendRaw(startPayload)
                        if (!sent) {
                            val ex = DictationException("Failed to send dictation start frame", retryable = true)
                            streamStartedDeferred.completeExceptionally(ex)
                            finalDeferred.completeExceptionally(ex)
                        }
                    }

                    "ack" -> {
                        if (msgDictationId != null && msgDictationId != dictationId) return
                        val ackSeq = frame["ackSeq"]?.jsonPrimitive?.intOrNull ?: return
                        session.updateAckSeq(ackSeq)
                        streamStartedDeferred.complete(Unit)
                    }

                    "partial" -> {
                        if (msgDictationId != null && msgDictationId != dictationId) return
                        val partialText = frame.stringField("text") ?: return
                        onPartial(partialText)
                    }

                    "finish_accepted" -> {
                        if (msgDictationId != null && msgDictationId != dictationId) return
                        val timeoutMs = frame["timeoutMs"]?.jsonPrimitive?.longOrNull ?: defaultFinalTimeoutMs
                        finishAcceptedDeferred.complete(timeoutMs)
                    }

                    "final" -> {
                        if (msgDictationId != null && msgDictationId != dictationId) return
                        val transcript = frame.stringField("text").orEmpty().trim()
                        if (transcript.isEmpty()) {
                            finalDeferred.completeExceptionally(
                                DictationException(
                                    message = "Dictation produced an empty transcript",
                                    retryable = false,
                                    isEmptyTranscript = true,
                                ),
                            )
                        } else {
                            finalDeferred.complete(transcript)
                        }
                    }

                    "error" -> {
                        if (msgDictationId != null && msgDictationId != dictationId) return
                        val errorMessage = frame.stringField("error") ?: "Dictation failed"
                        val retryable = frame["retryable"]?.jsonPrimitive?.booleanOrNull ?: false
                        val reasonCode = frame.stringField("reasonCode")
                        val ex = DictationException(
                            message = errorMessage,
                            retryable = retryable,
                            reasonCode = reasonCode,
                        )
                        streamStartedDeferred.completeExceptionally(ex)
                        finalDeferred.completeExceptionally(ex)
                    }

                    "pong" -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val detail = if (reason.isBlank()) "$code" else "$code: $reason"
                if (!streamStartedDeferred.isCompleted) {
                    streamStartedDeferred.completeExceptionally(
                        DictationException("Dictation WebSocket closed before stream started ($detail)", retryable = true),
                    )
                }
                if (!finalDeferred.isCompleted) {
                    finalDeferred.completeExceptionally(
                        DictationException(
                            "Dictation WebSocket closed before final transcript ($detail)",
                            retryable = true,
                        ),
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code}: ${t.message}" } ?: (t.message ?: "WebSocket error")
                val ex = DictationException(
                    message = "Dictation connection failed: $detail",
                    retryable = true,
                    statusCode = response?.code,
                    cause = t,
                )
                streamStartedDeferred.completeExceptionally(ex)
                finalDeferred.completeExceptionally(ex)
            }
        }

        val webSocket = httpClient.newWebSocket(request, listener)
        session.attachWebSocket(webSocket)

        try {
            withContext(Dispatchers.IO) {
                withTimeout(readyTimeoutMs) {
                    streamStartedDeferred.await()
                }
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                session.cancel()
            }
            if (t is DictationException || t is CancellationException) {
                throw t
            }
            throw DictationException("Timed out waiting for dictation ready", retryable = true, cause = t)
        }

        return session
    }

    suspend fun transcribe(
        serverUrl: String,
        audioChunks: Flow<ByteArray>,
        options: DictationOptions = DictationOptions(),
        dictationId: String = UUID.randomUUID().toString(),
        onPartial: (String) -> Unit = {},
    ): String {
        val session = startSession(
            serverUrl = serverUrl,
            options = options,
            dictationId = dictationId,
            onPartial = onPartial,
        )
        try {
            audioChunks.collect { chunk ->
                session.sendChunk(chunk)
            }
            return session.finish()
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                session.cancel()
            }
            throw t
        }
    }

    private class ActiveDictationSession(
        override val dictationId: String,
        private val json: Json,
        private val finalDeferred: CompletableDeferred<String>,
        private val finishAcceptedDeferred: CompletableDeferred<Long>,
        private val defaultFinalTimeoutMs: Long,
    ) : DictationSession {
        private val lock = Any()

        @Volatile
        private var webSocket: WebSocket? = null

        @Volatile
        override var lastAckSeq: Int = -1
            private set

        private var nextSeq: Int = 0
        private var finished: Boolean = false
        private var cancelled: Boolean = false

        fun attachWebSocket(ws: WebSocket) {
            synchronized(lock) {
                webSocket = ws
                if (cancelled) {
                    ws.cancel()
                }
            }
        }

        fun updateAckSeq(ackSeq: Int) {
            if (ackSeq > lastAckSeq) {
                lastAckSeq = ackSeq
            }
        }

        fun sendRaw(payload: String): Boolean {
            val ws = synchronized(lock) {
                if (cancelled) return false
                webSocket
            } ?: return false
            return ws.send(payload)
        }

        override suspend fun sendChunk(pcm16le: ByteArray) {
            if (pcm16le.isEmpty()) return
            if (finalDeferred.isCompleted) {
                finalDeferred.await()
                return
            }

            var offset = 0
            while (offset < pcm16le.size) {
                val end = minOf(offset + MAX_RAW_CHUNK_BYTES, pcm16le.size)
                val slice = if (offset == 0 && end == pcm16le.size) {
                    pcm16le
                } else {
                    pcm16le.copyOfRange(offset, end)
                }
                val encoded = Base64.getEncoder().encodeToString(slice)

                val sent = synchronized(lock) {
                    if (cancelled) {
                        throw CancellationException("Dictation session was cancelled")
                    }
                    if (finished) {
                        throw DictationException("Cannot send audio chunk after finish", retryable = false)
                    }
                    val ws = webSocket ?: throw DictationException("Dictation WebSocket not attached", retryable = true)
                    val seq = nextSeq
                    nextSeq += 1
                    val frame = json.encodeToString(
                        DictationChunkFrame(
                            dictationId = dictationId,
                            seq = seq,
                            audio = encoded,
                        ),
                    )
                    ws.send(frame)
                }

                if (!sent) {
                    throw DictationException("Failed to enqueue dictation audio chunk", retryable = true)
                }
                offset = end
            }
        }

        override suspend fun finish(): String {
            val finalSeq = synchronized(lock) {
                if (cancelled) {
                    throw CancellationException("Dictation session was cancelled")
                }
                if (finished) {
                    throw DictationException("Dictation finish already requested", retryable = false)
                }
                finished = true
                val ws = webSocket ?: throw DictationException("Dictation WebSocket not attached", retryable = true)
                val lastSentSeq = nextSeq - 1
                val frame = json.encodeToString(
                    DictationFinishFrame(
                        dictationId = dictationId,
                        finalSeq = lastSentSeq,
                    ),
                )
                if (!ws.send(frame)) {
                    throw DictationException("Failed to send dictation finish frame", retryable = true)
                }
                lastSentSeq
            }
            @Suppress("UNUSED_VARIABLE")
            val unusedSeq = finalSeq

            return try {
                awaitFinalWithAdaptiveTimeout()
            } finally {
                withContext(NonCancellable) {
                    closeSocket(1000, "done")
                }
            }
        }

        private suspend fun awaitFinalWithAdaptiveTimeout(): String = withContext(Dispatchers.IO) {
            var deadlineMs = System.currentTimeMillis() + defaultFinalTimeoutMs
            var acceptedHandled = false

            while (true) {
                val remaining = deadlineMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    throw DictationException("Timed out waiting for final transcript", retryable = true)
                }

                val result = withTimeoutOrNull(remaining) {
                    if (!acceptedHandled) {
                        select<String?> {
                            finalDeferred.onAwait { it }
                            finishAcceptedDeferred.onAwait { serverTimeoutMs ->
                                acceptedHandled = true
                                val serverDeadline = System.currentTimeMillis() +
                                    max(defaultFinalTimeoutMs, serverTimeoutMs + SERVER_TIMEOUT_GRACE_MS)
                                deadlineMs = max(deadlineMs, serverDeadline)
                                null
                            }
                        }
                    } else {
                        finalDeferred.await()
                    }
                }

                if (result != null) {
                    return@withContext result
                }
                if (!acceptedHandled && !finishAcceptedDeferred.isCompleted) {
                    throw DictationException("Timed out waiting for final transcript", retryable = true)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        override suspend fun cancel() {
            val wsToClose = synchronized(lock) {
                if (cancelled) return
                cancelled = true
                webSocket
            }
            if (wsToClose != null) {
                runCatching {
                    wsToClose.send(json.encodeToString(DictationCancelFrame(dictationId = dictationId)))
                }
                runCatching {
                    wsToClose.close(1000, "cancelled")
                }
                runCatching {
                    wsToClose.cancel()
                }
            }
            finalDeferred.cancel()
        }

        private fun closeSocket(code: Int, reason: String) {
            val ws = synchronized(lock) { webSocket } ?: return
            runCatching { ws.close(code, reason) }
        }
    }

    @Serializable
    private data class DictationStartFrame(
        val type: String = "start",
        val dictationId: String,
        val format: String,
        val options: DictationOptions,
    )

    @Serializable
    private data class DictationChunkFrame(
        val type: String = "chunk",
        val dictationId: String,
        val seq: Int,
        val audio: String,
    )

    @Serializable
    private data class DictationFinishFrame(
        val type: String = "finish",
        val dictationId: String,
        val finalSeq: Int,
    )

    @Serializable
    private data class DictationCancelFrame(
        val type: String = "cancel",
        val dictationId: String,
    )

    companion object {
        const val DICTATION_WS_PATH = "/api/dictation/ws"
        const val PCM_16K_16BIT_FORMAT = "audio/pcm;rate=16000;bits=16"
        private const val DEFAULT_READY_TIMEOUT_MS = 10_000L
        private const val DEFAULT_FINAL_TIMEOUT_MS = 15_000L
        private const val SERVER_TIMEOUT_GRACE_MS = 5_000L
        private const val MAX_RAW_CHUNK_BYTES = 256 * 1024

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }
}

internal fun toWebSocketBaseUrl(serverUrl: String): String {
    val normalized = OpenChamberHttp.normalizeBaseUrl(serverUrl)
    return when {
        normalized.startsWith("https://", ignoreCase = true) ->
            "wss://" + normalized.substring("https://".length)
        normalized.startsWith("http://", ignoreCase = true) ->
            "ws://" + normalized.substring("http://".length)
        normalized.startsWith("wss://", ignoreCase = true) ||
            normalized.startsWith("ws://", ignoreCase = true) -> normalized
        else -> "ws://$normalized"
    }
}

private fun JsonObject.stringField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
