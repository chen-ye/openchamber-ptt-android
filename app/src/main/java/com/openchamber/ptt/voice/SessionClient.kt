package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.session.SessionApiException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class LatestTurn(
    val userText: String? = null,
    val assistantText: String? = null,
    val assistantMessageId: String? = null,
)

class SessionClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
) {
    suspend fun getLatestAssistantMessageId(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
    ): String? {
        val messages = fetchRecentMessages(serverUrl, sessionId, directory)
        return messages.firstOrNull { it.isAssistant }?.id
    }

    suspend fun sendPrompt(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
        text: String,
    ): String? {
        if (text.isBlank()) {
            throw SessionApiException(null, "Prompt text must not be blank")
        }

        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val encodedSessionId = OpenChamberHttp.encodeUriComponent(sessionId)
        val payload = json.encodeToString(PromptRequest(text = text))

        val requestBuilder = Request.Builder()
            .url("$baseUrl/api/session/$encodedSessionId/prompt")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
        OpenChamberHttp.applyDirectoryHeaders(requestBuilder, directory)

        val (statusCode, bodyText) = executeRequest(requestBuilder.build(), "sending prompt")
        if (statusCode !in 200..299) {
            throw SessionApiException(
                statusCode,
                "Failed to send prompt ($statusCode): ${bodyText.ifBlank { "HTTP $statusCode" }}",
            )
        }

        if (bodyText.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull() ?: return null
        return root.stringOrNull("id")
            ?: (root["data"] as? JsonObject)?.stringOrNull("id")
    }

    suspend fun fetchNewAssistantText(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
        afterMessageId: String? = null,
    ): String? {
        val messages = fetchRecentMessages(serverUrl, sessionId, directory)

        if (afterMessageId.isNullOrBlank()) {
            return messages
                .asSequence()
                .filter { it.isAssistant }
                .mapNotNull { it.joinedText() }
                .firstOrNull()
        }

        val newMessages = messages.takeWhile { it.id != afterMessageId }
        val newAssistantTexts = newMessages
            .asReversed()
            .asSequence()
            .filter { it.isAssistant }
            .mapNotNull { it.joinedText() }
            .toList()

        if (newAssistantTexts.isEmpty()) return null
        return newAssistantTexts.joinToString("\n\n")
    }

    suspend fun fetchLatestTurn(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
    ): LatestTurn {
        val messages = fetchRecentMessages(serverUrl, sessionId, directory)
        val latestAssistant = messages.firstOrNull { it.isAssistant && !it.joinedText().isNullOrBlank() }
        val latestUser = messages.firstOrNull { it.isUser && !it.joinedText().isNullOrBlank() }
        val cleanUserText = latestUser?.joinedText()?.let(::stripVoiceTagPrefix)
        return LatestTurn(
            userText = cleanUserText,
            assistantText = latestAssistant?.joinedText(),
            assistantMessageId = latestAssistant?.id,
        )
    }

    private suspend fun fetchRecentMessages(
        serverUrl: String,
        sessionId: String,
        directory: String?,
    ): List<ParsedMessage> {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val encodedSessionId = OpenChamberHttp.encodeUriComponent(sessionId)

        val requestBuilder = Request.Builder()
            .url("$baseUrl/api/session/$encodedSessionId/message?limit=$MESSAGE_LIMIT")
            .get()
            .header("Accept", "application/json")
        OpenChamberHttp.applyDirectoryHeaders(requestBuilder, directory)

        val (statusCode, bodyText) = executeRequest(requestBuilder.build(), "fetching session messages")
        if (statusCode !in 200..299) {
            throw SessionApiException(
                statusCode,
                "Failed to fetch session messages ($statusCode): ${bodyText.ifBlank { "HTTP $statusCode" }}",
            )
        }

        return parseMessagesPayload(bodyText, statusCode)
    }

    private fun parseMessagesPayload(bodyText: String, statusCode: Int): List<ParsedMessage> {
        val root: JsonElement = try {
            json.parseToJsonElement(bodyText)
        } catch (e: Exception) {
            throw SessionApiException(statusCode, "Invalid session messages JSON payload: ${e.message}", e)
        }

        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray
                ?: throw SessionApiException(statusCode, "Session messages payload missing 'data' array")
            else -> throw SessionApiException(statusCode, "Unexpected session messages JSON shape")
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val info = obj["info"] as? JsonObject

            val id = obj.stringOrNull("id") ?: info?.stringOrNull("id") ?: return@mapNotNull null
            val roleOrType = obj.stringOrNull("type")
                ?: obj.stringOrNull("role")
                ?: info?.stringOrNull("role")
                ?: info?.stringOrNull("type")

            val partsArray = (obj["parts"] as? JsonArray)
                ?: (obj["content"] as? JsonArray)

            val textParts = partsArray.orEmpty().mapNotNull { partElement ->
                val partObj = partElement as? JsonObject ?: return@mapNotNull null
                if (partObj.stringOrNull("type") != "text") return@mapNotNull null
                partObj.stringOrNull("text")?.takeIf { it.isNotBlank() }
            }

            ParsedMessage(
                id = id,
                isAssistant = roleOrType == "assistant",
                isUser = roleOrType == "user",
                textParts = textParts,
            )
        }
    }

    private suspend fun executeRequest(
        request: Request,
        action: String,
    ): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(
                        SessionApiException(null, "Network error $action: ${e.message}", e),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val text = resp.body?.string().orEmpty()
                        continuation.resume(resp.code to text)
                    }
                }
            },
        )
    }

    private data class ParsedMessage(
        val id: String,
        val isAssistant: Boolean,
        val isUser: Boolean,
        val textParts: List<String>,
    ) {
        fun joinedText(): String? {
            if (textParts.isEmpty()) return null
            val joined = textParts.joinToString("\n\n").trim()
            return joined.takeIf { it.isNotEmpty() }
        }
    }

    internal fun stripVoiceTagPrefix(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[voice]")) {
            trimmed.removePrefix("[voice]").trimStart('\r', '\n', ' ').trim()
        } else {
            trimmed
        }
    }

    @Serializable
    private data class PromptRequest(
        val text: String,
    )

    companion object {
        const val MESSAGE_LIMIT = 5
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
