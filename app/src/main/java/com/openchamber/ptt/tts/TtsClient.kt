package com.openchamber.ptt.tts

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TtsException(
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
data class TtsVoice(
    val id: String,
    val name: String = "",
    @SerialName("display_name") val displayName: String = "",
    val category: String = "",
    val language: String = "",
    @SerialName("language_label") val languageLabel: String = "",
    val gender: String = "",
    val accent: String = "",
    val style: String = "",
    val pitch: String = "",
    val description: String = "",
) {
    val label: String
        get() = displayName.ifBlank { name.ifBlank { id } }
}

@Serializable
data class VoiceCatalog(
    @SerialName("default_voice") val defaultVoice: String = TtsClient.DEFAULT_TTS_VOICE,
    val voices: List<TtsVoice> = emptyList(),
)

data class TtsAudio(
    val bytes: ByteArray,
    val contentType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsAudio) return false
        return contentType == other.contentType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        return result
    }
}

class TtsClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
) {
    internal val ttsHttpClient: OkHttpClient = if (httpClient.readTimeoutMillis < MIN_TTS_READ_TIMEOUT_MS) {
        httpClient.newBuilder()
            .readTimeout(DEFAULT_TTS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    } else {
        httpClient
    }

    suspend fun sanitizeForTts(
        serverUrl: String,
        text: String,
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val payload = json.encodeToString(SummarizeRequest(text = trimmed, mode = SUMMARIZE_MODE_TTS))
        val request = Request.Builder()
            .url("$baseUrl$SUMMARIZE_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        return try {
            val response = executeBytes(httpClient, request, "sanitizing text for TTS")
            if (response.statusCode !in 200..299 || response.bytes.isEmpty()) {
                return trimmed
            }
            val bodyText = response.bytes.toString(Charsets.UTF_8).trim()
            if (bodyText.isEmpty()) {
                return trimmed
            }
            extractSummaryText(bodyText) ?: trimmed
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            trimmed
        }
    }

    suspend fun fetchVoices(
        serverUrl: String,
        category: String = "featured",
    ): VoiceCatalog {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val payload = json.encodeToString(
            GuestServiceRequest(
                method = "GET",
                path = VOICES_PATH,
                query = mapOf("category" to category),
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl$GUEST_TTS_SERVICE_REQUEST_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        val response = executeBytes(httpClient, request, "fetching TTS voices")
        if (response.statusCode !in 200..299 || response.bytes.isEmpty()) {
            throw TtsException(
                statusCode = response.statusCode,
                message = "Failed to fetch voices (${response.statusCode})",
            )
        }

        val wrapperText = response.bytes.toString(Charsets.UTF_8)
        val wrapperObj = runCatching { json.parseToJsonElement(wrapperText) as? JsonObject }.getOrNull()
            ?: throw TtsException(statusCode = response.statusCode, message = "Invalid voices response")
        val innerStatus = wrapperObj["status"]?.jsonPrimitive?.intOrNull ?: response.statusCode
        val innerBody = wrapperObj.stringOrNull("body")
            ?: throw TtsException(statusCode = innerStatus, message = "Missing voices payload")

        if (innerStatus !in 200..299) {
            throw TtsException(statusCode = innerStatus, message = "TTS bridge voices error ($innerStatus)")
        }

        return runCatching { json.decodeFromString<VoiceCatalog>(innerBody) }
            .getOrElse { err ->
                throw TtsException(statusCode = innerStatus, message = "Invalid voices JSON: ${err.message}", cause = err)
            }
    }

    suspend fun synthesizeWithEngine(
        serverUrl: String,
        text: String,
        voice: String = DEFAULT_TTS_VOICE,
        engine: TtsEngine,
    ): TtsAudio {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw TtsException(message = "Cannot synthesize empty text")
        }
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val chosenVoice = voice.trim().ifEmpty { DEFAULT_TTS_VOICE }
        return when (engine) {
            TtsEngine.GEMINI_BRIDGE -> requestPrimaryTts(baseUrl, trimmed, chosenVoice).getOrThrow()
            TtsEngine.LOCAL_KOKORO -> requestKokoroFallbackTts(baseUrl, trimmed)
            TtsEngine.OPENAI_CLOUD -> requestOpenAiCloudTts(baseUrl, trimmed, chosenVoice)
            TtsEngine.MLKIT_GENAI -> throw TtsException(message = "MLKIT_GENAI is handled locally by TtsPlayer")
            TtsEngine.ANDROID_SYSTEM -> throw TtsException(message = "ANDROID_SYSTEM is handled locally by TtsPlayer")
        }
    }

    private suspend fun requestPrimaryTts(
        baseUrl: String,
        text: String,
        voice: String,
    ): Result<TtsAudio> {
        val payload = json.encodeToString(
            TtsSpeakRequest(
                text = text,
                baseURL = DEFAULT_TTS_BASE_URL,
                voice = voice,
                model = DEFAULT_TTS_MODEL,
                speed = DEFAULT_TTS_SPEED,
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl$TTS_SPEAK_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", AUDIO_ACCEPT_HEADER)
            .build()

        val response = executeBytes(ttsHttpClient, request, "synthesizing primary TTS")
        if (response.statusCode !in 200..299) {
            return Result.failure(
                TtsException(
                    statusCode = response.statusCode,
                    message = "Primary TTS failed (${response.statusCode})",
                ),
            )
        }
        if (response.bytes.isEmpty()) {
            return Result.failure(
                TtsException(
                    statusCode = response.statusCode,
                    message = "Primary TTS returned an empty audio body",
                ),
            )
        }
        return Result.success(
            TtsAudio(
                bytes = response.bytes,
                contentType = response.contentType ?: DEFAULT_AUDIO_CONTENT_TYPE,
            ),
        )
    }

    private suspend fun requestOpenAiCloudTts(
        baseUrl: String,
        text: String,
        voice: String,
    ): TtsAudio {
        val openAiVoice = if (voice.lowercase() in OPENAI_VOICES) voice.lowercase() else DEFAULT_OPENAI_VOICE
        val payload = json.encodeToString(
            OpenAiSpeakRequest(
                text = text,
                voice = openAiVoice,
                model = DEFAULT_OPENAI_MODEL,
                speed = DEFAULT_TTS_SPEED,
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl$TTS_SPEAK_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", AUDIO_ACCEPT_HEADER)
            .build()

        val response = executeBytes(ttsHttpClient, request, "synthesizing OpenAI Cloud TTS")
        if (response.statusCode !in 200..299) {
            throw TtsException(
                statusCode = response.statusCode,
                message = "OpenAI Cloud TTS failed (${response.statusCode})",
            )
        }
        if (response.bytes.isEmpty()) {
            throw TtsException(
                statusCode = response.statusCode,
                message = "OpenAI Cloud TTS returned an empty audio body",
            )
        }
        return TtsAudio(
            bytes = response.bytes,
            contentType = response.contentType ?: "audio/mpeg",
        )
    }

    private suspend fun requestKokoroFallbackTts(
        baseUrl: String,
        text: String,
    ): TtsAudio {
        val payload = json.encodeToString(
            KokoroSpeakRequest(
                text = text,
                speakerId = DEFAULT_KOKORO_SPEAKER_ID,
                speed = DEFAULT_TTS_SPEED,
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl$KOKORO_SPEAK_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", AUDIO_ACCEPT_HEADER)
            .build()

        val response = executeBytes(ttsHttpClient, request, "synthesizing Kokoro fallback TTS")
        if (response.statusCode !in 200..299) {
            throw TtsException(
                statusCode = response.statusCode,
                message = "Kokoro TTS failed (${response.statusCode})",
            )
        }
        if (response.bytes.isEmpty()) {
            throw TtsException(
                statusCode = response.statusCode,
                message = "Kokoro TTS returned an empty audio body",
            )
        }
        return TtsAudio(
            bytes = response.bytes,
            contentType = response.contentType ?: DEFAULT_AUDIO_CONTENT_TYPE,
        )
    }

    private fun extractSummaryText(bodyText: String): String? {
        val parsed = runCatching { json.parseToJsonElement(bodyText) }.getOrNull()
        return when (parsed) {
            is JsonObject -> {
                val direct = parsed.stringOrNull("summary")
                    ?: parsed.stringOrNull("text")
                    ?: parsed.stringOrNull("result")
                    ?: parsed.stringOrNull("output")
                val nested = (parsed["data"] as? JsonObject)?.let { data ->
                    data.stringOrNull("summary")
                        ?: data.stringOrNull("text")
                        ?: data.stringOrNull("result")
                        ?: data.stringOrNull("output")
                }
                (direct ?: nested)?.trim()?.takeIf { it.isNotEmpty() }
            }

            is JsonPrimitive -> parsed.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            else -> if (!bodyText.startsWith("{") && !bodyText.startsWith("[")) bodyText else null
        }
    }

    private suspend fun executeBytes(
        client: OkHttpClient,
        request: Request,
        action: String,
    ): HttpBytesResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(
                        TtsException(message = "Network error $action: ${e.message}", cause = e),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            val contentType = resp.header("Content-Type")
                                ?.substringBefore(';')
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                            if (!continuation.isCancelled) {
                                continuation.resume(
                                    HttpBytesResponse(
                                        statusCode = resp.code,
                                        bytes = bytes,
                                        contentType = contentType,
                                    ),
                                )
                            }
                        }
                    } catch (e: IOException) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(
                                TtsException(message = "Error reading response $action: ${e.message}", cause = e),
                            )
                        }
                    }
                }
            },
        )
    }

    private data class HttpBytesResponse(
        val statusCode: Int,
        val bytes: ByteArray,
        val contentType: String?,
    )

    @Serializable
    private data class GuestServiceRequest(
        val method: String,
        val path: String,
        val query: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class SummarizeRequest(
        val text: String,
        val mode: String = SUMMARIZE_MODE_TTS,
    )

    @Serializable
    private data class TtsSpeakRequest(
        val text: String,
        val baseURL: String = DEFAULT_TTS_BASE_URL,
        val voice: String = DEFAULT_TTS_VOICE,
        val model: String = DEFAULT_TTS_MODEL,
        val speed: Double = DEFAULT_TTS_SPEED,
    )

    @Serializable
    private data class OpenAiSpeakRequest(
        val text: String,
        val voice: String = DEFAULT_OPENAI_VOICE,
        val model: String = DEFAULT_OPENAI_MODEL,
        val speed: Double = DEFAULT_TTS_SPEED,
    )

    @Serializable
    private data class KokoroSpeakRequest(
        val text: String,
        val speakerId: Int = DEFAULT_KOKORO_SPEAKER_ID,
        val speed: Double = DEFAULT_TTS_SPEED,
    )

    companion object {
        const val SUMMARIZE_PATH = "/api/text/summarize"
        const val TTS_SPEAK_PATH = "/api/tts/speak"
        const val KOKORO_SPEAK_PATH = "/api/dictation/tts/speak"
        const val GUEST_TTS_SERVICE_REQUEST_PATH = "/api/guests/gemini-tts-bridge/service/request"
        const val VOICES_PATH = "/v1/audio/voices"

        const val SUMMARIZE_MODE_TTS = "tts"
        const val DEFAULT_TTS_BASE_URL = "http://127.0.0.1:4005/v1"
        const val DEFAULT_TTS_VOICE = "Kore"
        const val DEFAULT_TTS_MODEL = "google/gemini-3.8-flash-lite-tts"
        const val DEFAULT_OPENAI_VOICE = VoicePipelineDefaults.DEFAULT_OPENAI_VOICE
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-tts"
        const val DEFAULT_TTS_SPEED = 1.0
        private val OPENAI_VOICES = setOf(
            "alloy", "ash", "ballad", "coral", "echo", "fable",
            "nova", "onyx", "sage", "shimmer", "verse", "marin", "cedar",
        )
        const val DEFAULT_KOKORO_SPEAKER_ID = 0
        const val MIN_TTS_READ_TIMEOUT_MS = 60_000L
        const val DEFAULT_TTS_READ_TIMEOUT_MS = 90_000L

        private const val DEFAULT_AUDIO_CONTENT_TYPE = "audio/wav"
        private const val AUDIO_ACCEPT_HEADER = "audio/wav, audio/*;q=0.9, application/octet-stream;q=0.8"
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
