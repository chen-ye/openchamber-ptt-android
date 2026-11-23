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

data class PreparedSpeechText(
    val text: String,
    val cacheHit: Boolean,
)

data class SynthesizedSpeechAudio(
    val audio: TtsAudio,
    val cacheHit: Boolean,
)

class TtsClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
    private val summarizeTimeoutMs: Long = DEFAULT_SUMMARIZE_TIMEOUT_MS,
    private val ttsCache: TtsCache? = null,
) {
    internal val ttsHttpClient: OkHttpClient = if (httpClient.readTimeoutMillis < MIN_TTS_READ_TIMEOUT_MS) {
        httpClient.newBuilder()
            .readTimeout(DEFAULT_TTS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    } else {
        httpClient
    }

    internal val summarizeHttpClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(summarizeTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(summarizeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun prepareTextForTts(
        serverUrl: String,
        text: String,
        inputMode: TtsInputMode,
        sessionLabel: String? = null,
        detailLevel: SpokenDetailLevel = SpokenDetailLevel.BALANCED,
    ): String = prepareTextForTtsDetailed(
        serverUrl = serverUrl,
        text = text,
        inputMode = inputMode,
        sessionLabel = sessionLabel,
        detailLevel = detailLevel,
    ).text

    suspend fun prepareTextForTtsDetailed(
        serverUrl: String,
        text: String,
        inputMode: TtsInputMode,
        sessionLabel: String? = null,
        detailLevel: SpokenDetailLevel = SpokenDetailLevel.BALANCED,
    ): PreparedSpeechText {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PreparedSpeechText(text = "", cacheHit = false)
        return when (inputMode) {
            TtsInputMode.RAW -> PreparedSpeechText(text = trimmed, cacheHit = false)
            TtsInputMode.SANITIZED -> {
                val cache = ttsCache
                if (cache != null) {
                    cache.getPreparedText(trimmed, TtsInputMode.SANITIZED, detailLevel)?.let { cached ->
                        return PreparedSpeechText(text = cached, cacheHit = true)
                    }
                    val flightKey = "prep:${cache.textCacheKey(trimmed, TtsInputMode.SANITIZED, detailLevel)}"
                    cache.singleFlight(flightKey) {
                        cache.getPreparedText(trimmed, TtsInputMode.SANITIZED, detailLevel)?.let { cached ->
                            return@singleFlight PreparedSpeechText(text = cached, cacheHit = true)
                        }
                        val (sanitized, isGenuine) = sanitizeForTtsInternal(serverUrl, trimmed)
                        if (isGenuine) {
                            cache.putPreparedText(trimmed, TtsInputMode.SANITIZED, detailLevel, sanitized)
                        }
                        PreparedSpeechText(text = sanitized, cacheHit = false)
                    }
                } else {
                    PreparedSpeechText(
                        text = sanitizeForTtsInternal(serverUrl, trimmed).first,
                        cacheHit = false,
                    )
                }
            }
            TtsInputMode.SUMMARIZED -> {
                if (!shouldTranslateForSpeech(trimmed)) {
                    val normalized = normalizeSpokenOutput(trimmed).ifEmpty { trimmed }
                    return PreparedSpeechText(text = normalized, cacheHit = false)
                }
                val cache = ttsCache
                if (cache != null) {
                    cache.getPreparedText(trimmed, TtsInputMode.SUMMARIZED, detailLevel)?.let { cached ->
                        return PreparedSpeechText(text = cached, cacheHit = true)
                    }
                    val flightKey = "prep:${cache.textCacheKey(trimmed, TtsInputMode.SUMMARIZED, detailLevel)}"
                    cache.singleFlight(flightKey) {
                        cache.getPreparedText(trimmed, TtsInputMode.SUMMARIZED, detailLevel)?.let { cached ->
                            return@singleFlight PreparedSpeechText(text = cached, cacheHit = true)
                        }
                        val translated = summarizeForSpeech(
                            serverUrl = serverUrl,
                            text = trimmed,
                            sessionLabel = sessionLabel,
                            detailLevel = detailLevel,
                        )
                        if (translated != null) {
                            val normalized = normalizeSpokenOutput(translated).ifEmpty { trimmed }
                            cache.putPreparedText(trimmed, TtsInputMode.SUMMARIZED, detailLevel, normalized)
                            PreparedSpeechText(text = normalized, cacheHit = false)
                        } else {
                            // Do not poison SUMMARIZED cache with sanitized fallback on transient error.
                            PreparedSpeechText(
                                text = sanitizeForTtsInternal(serverUrl, trimmed).first,
                                cacheHit = false,
                            )
                        }
                    }
                } else {
                    val translated = summarizeForSpeech(
                        serverUrl = serverUrl,
                        text = trimmed,
                        sessionLabel = sessionLabel,
                        detailLevel = detailLevel,
                    )
                    if (translated != null) {
                        PreparedSpeechText(
                            text = normalizeSpokenOutput(translated).ifEmpty { trimmed },
                            cacheHit = false,
                        )
                    } else {
                        PreparedSpeechText(
                            text = sanitizeForTtsInternal(serverUrl, trimmed).first,
                            cacheHit = false,
                        )
                    }
                }
            }
        }
    }

    suspend fun summarizeForSpeech(
        serverUrl: String,
        text: String,
        sessionLabel: String? = null,
        detailLevel: SpokenDetailLevel = SpokenDetailLevel.BALANCED,
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val cleanLabel = sessionLabel?.trim()?.takeIf { it.isNotEmpty() }
        val prompt = if (cleanLabel != null) {
            "Session: $cleanLabel\n\nAssistant reply:\n$trimmed"
        } else {
            trimmed
        }

        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val payload = json.encodeToString(
            SmallModelGenerateRequest(
                prompt = prompt,
                system = buildSpokenSystemPrompt(detailLevel),
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl$SMALL_MODEL_GENERATE_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        return try {
            val response = executeBytes(summarizeHttpClient, request, "summarizing text for TTS")
            if (response.statusCode == 401) {
                throw TtsException(statusCode = 401, message = "Unauthorized summarizing text for TTS")
            }
            if (response.statusCode !in 200..299 || response.bytes.isEmpty()) {
                return null
            }
            val bodyText = response.bytes.toString(Charsets.UTF_8).trim()
            if (bodyText.isEmpty()) return null
            val obj = runCatching { json.parseToJsonElement(bodyText) as? JsonObject }.getOrNull()
                ?: return null
            obj.stringOrNull("text")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (te: TtsException) {
            if (te.statusCode == 401) throw te
            null
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun sanitizeForTts(
        serverUrl: String,
        text: String,
    ): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        return sanitizeForTtsInternal(serverUrl, trimmed).first
    }

    private suspend fun sanitizeForTtsInternal(
        serverUrl: String,
        trimmed: String,
    ): Pair<String, Boolean> {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val payload = json.encodeToString(SummarizeRequest(text = trimmed, mode = SUMMARIZE_MODE_TTS))
        val request = Request.Builder()
            .url("$baseUrl$SUMMARIZE_PATH")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        return try {
            val response = executeBytes(httpClient, request, "sanitizing text for TTS")
            if (response.statusCode == 401) {
                throw TtsException(statusCode = 401, message = "Unauthorized sanitizing text for TTS")
            }
            if (response.statusCode !in 200..299 || response.bytes.isEmpty()) {
                return trimmed to false
            }
            val bodyText = response.bytes.toString(Charsets.UTF_8).trim()
            if (bodyText.isEmpty()) {
                return trimmed to false
            }
            val extracted = extractSummaryText(bodyText)
            if (extracted != null) {
                extracted to true
            } else {
                trimmed to false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (te: TtsException) {
            if (te.statusCode == 401) throw te
            trimmed to false
        } catch (_: Throwable) {
            trimmed to false
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
        kokoroSpeakerId: Int = DEFAULT_KOKORO_SPEAKER_ID,
    ): TtsAudio = synthesizeWithEngineDetailed(
        serverUrl = serverUrl,
        text = text,
        voice = voice,
        engine = engine,
        kokoroSpeakerId = kokoroSpeakerId,
    ).audio

    suspend fun synthesizeWithEngineDetailed(
        serverUrl: String,
        text: String,
        voice: String = DEFAULT_TTS_VOICE,
        engine: TtsEngine,
        kokoroSpeakerId: Int = DEFAULT_KOKORO_SPEAKER_ID,
    ): SynthesizedSpeechAudio {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            throw TtsException(message = "Cannot synthesize empty text")
        }
        if (engine == TtsEngine.MLKIT_GENAI) {
            throw TtsException(message = "MLKIT_GENAI is handled locally by TtsPlayer")
        }
        if (engine == TtsEngine.ANDROID_SYSTEM) {
            throw TtsException(message = "ANDROID_SYSTEM is handled locally by TtsPlayer")
        }
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val chosenVoice = voice.trim().ifEmpty { DEFAULT_TTS_VOICE }
        val cache = ttsCache
        if (cache != null) {
            val cacheKey = cache.audioCacheKey(
                serverUrl = baseUrl,
                engine = engine,
                voice = chosenVoice,
                spokenText = trimmed,
                speed = DEFAULT_TTS_SPEED,
                kokoroSpeakerId = kokoroSpeakerId,
            )
            cache.getAudio(cacheKey)?.let { hit ->
                return SynthesizedSpeechAudio(audio = hit, cacheHit = true)
            }
            return cache.singleFlight("audio:$cacheKey") {
                cache.getAudio(cacheKey)?.let { hit ->
                    return@singleFlight SynthesizedSpeechAudio(audio = hit, cacheHit = true)
                }
                val audio = synthesizeUncached(
                    baseUrl = baseUrl,
                    text = trimmed,
                    voice = chosenVoice,
                    engine = engine,
                    kokoroSpeakerId = kokoroSpeakerId,
                )
                cache.putAudio(cacheKey, audio)
                SynthesizedSpeechAudio(audio = audio, cacheHit = false)
            }
        }
        val audio = synthesizeUncached(
            baseUrl = baseUrl,
            text = trimmed,
            voice = chosenVoice,
            engine = engine,
            kokoroSpeakerId = kokoroSpeakerId,
        )
        return SynthesizedSpeechAudio(audio = audio, cacheHit = false)
    }

    private suspend fun synthesizeUncached(
        baseUrl: String,
        text: String,
        voice: String,
        engine: TtsEngine,
        kokoroSpeakerId: Int,
    ): TtsAudio = when (engine) {
        TtsEngine.GEMINI_BRIDGE -> requestPrimaryTts(baseUrl, text, voice).getOrThrow()
        TtsEngine.LOCAL_KOKORO -> requestKokoroFallbackTts(baseUrl, text, kokoroSpeakerId)
        TtsEngine.OPENAI_CLOUD -> requestOpenAiCloudTts(baseUrl, text, voice)
        TtsEngine.MLKIT_GENAI -> throw TtsException(message = "MLKIT_GENAI is handled locally by TtsPlayer")
        TtsEngine.ANDROID_SYSTEM -> throw TtsException(message = "ANDROID_SYSTEM is handled locally by TtsPlayer")
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
        val openAiVoice = normalizeOpenAiVoice(voice)
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
        speakerId: Int = DEFAULT_KOKORO_SPEAKER_ID,
    ): TtsAudio {
        val payload = json.encodeToString(
            KokoroSpeakRequest(
                text = text,
                speakerId = speakerId,
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
    private data class SmallModelGenerateRequest(
        val prompt: String,
        val system: String,
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
        const val SMALL_MODEL_GENERATE_PATH = OpenChamberHttp.SMALL_MODEL_GENERATE_PATH
        const val TTS_SPEAK_PATH = "/api/tts/speak"
        const val KOKORO_SPEAK_PATH = "/api/dictation/tts/speak"
        const val GUEST_TTS_SERVICE_REQUEST_PATH = "/api/guests/gemini-tts-bridge/service/request"
        const val VOICES_PATH = "/v1/audio/voices"

        const val SUMMARIZE_MODE_TTS = "tts"
        const val TTS_SUMMARIZE_MIN_CHARS = 180

        fun buildSpokenSystemPrompt(detailLevel: SpokenDetailLevel): String {
            val abstractionGuidance = when (detailLevel) {
                SpokenDetailLevel.ESSENTIALS ->
                    "Abstraction level — Essentials: Target 2 to 5 concise sentences unless listing actionable user choices: preserve EVERY actionable decision, choice/option, blocker, caveat, and closing question, " +
                        "while summarizing informational benchmark/reference tables into a one-sentence takeaway without per-row metrics, citation domains, filenames, class names, hex codes, API routes, or protocol internals. " +
                        "Only ask a closing question if the original reply asks one."
                SpokenDetailLevel.BALANCED ->
                    "Abstraction level — Balanced: Cover every outcome, option, tradeoff, caveat, and closing question, " +
                        "naming key files or components with spoken extensions (for example, 'docker-compose dot yml', never full directory prefixes) and omitting opaque commit SHAs, lockfile digests, UUIDs, and session IDs."
                SpokenDetailLevel.DETAILED ->
                    "Abstraction level — Detailed: Deliver a thorough technical briefing for the ear—walk through all options, behaviors, state/method/endpoint roles, " +
                        "metrics, error details, and edge cases in logical order, translating tables and code into clear spoken explanations (refer to hex codes, SHAs, digests, and IDs by meaning rather than spelling them character-by-character, and drop boilerplate home-directory prefixes)."
            }
            return "You are a written-to-spoken translator for a software engineer working eyes-free with no screen access. " +
                "NEVER omit actionable user options, choices, questions, warnings, caveats, or key outcomes (distinguish user choices from informational reference tables). " +
                "Translate markdown, tables, bullet lists, URLs, and code into natural spoken prose in the same language as the reply.\n" +
                "UI blocks & tables:\n" +
                "- Treat fenced `openui-lang` blocks as visual cards, steps, charts, and tables: speak ONLY the domain titles, steps, and data rows inside them (count data rows, never header columns as options); NEVER narrate DSL container primitives (`Stack`, `Card`, `Steps`, `Table`, `BarChart`, `Series`, `Callout`, `row`, or tone arguments like `success`/`warning`/`info`/`error`).\n" +
                "- When a table column header lists alternate triggers or aliases (for example `2x Tap or NEXT` and `3x Tap, PREV, or X`), state that mapping once up front before reading the rows, and keep state-level metadata columns (such as earcons) bound to the state itself rather than inventing per-action side effects.\n" +
                "Spoken phrasing rules:\n" +
                "- Never speak literal separator names like 'underscore', 'dash', or 'slash' chains: " +
                "turn `SEND_GRACE` into 'Send Grace', `gemini-3.8-flash-lite-tts` into 'Gemini 3.8 Flash Lite TTS', `openchamber_api35` into 'openchamber API 35', " +
                "and `src/auth/AuthService.kt` into 'AuthService dot kt under src auth'.\n" +
                "- Expand shorthands cleanly: say 'single tap', 'double tap', 'triple tap' (never 'one times tap'), 'or' for `/` between triggers (never 'for'), 'Previous' (never 'Prev'), 'undoes' (never 'undos'), '2025 to 2026' for ranges, and 'reduced by' vs 'down to' accurately.\n" +
                "- Never read hex codes, commit SHAs, SHA-256 digests, UUIDs, or opaque session IDs aloud—refer to them by meaning ('the PLAY opcode', 'updated digest', 'the explore task') unless comparing two exact hashes is the main subject; keep 4-digit port numbers as normal numbers ('port 4006') and turn HTTP codes into speech ('four oh one unauthorized').\n" +
                "- Cite URLs by source or bare domain only ('on granda dot org', 'on arXiv'), and paraphrase CLI commands/loopback URLs ('with the server flag on localhost port 4096') without raw `http://` or `--`.\n" +
                "- Use commas, periods, double dashes (--), ellipses (...), and sparing ALL CAPS on critical status words for pacing. Never output markdown syntax, bullet characters, backticks, bracketed/angle-bracket audio tags, SSML, or IPA.\n" +
                abstractionGuidance
        }

        val SUMMARIZE_SYSTEM_PROMPT: String = buildSpokenSystemPrompt(SpokenDetailLevel.BALANCED)

        internal fun shouldTranslateForSpeech(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed.length >= TTS_SUMMARIZE_MIN_CHARS) return true
            return trimmed.contains("```") ||
                trimmed.contains('`') ||
                trimmed.contains("http://") ||
                trimmed.contains("https://") ||
                trimmed.contains("](") ||
                MARKDOWN_LINE_REGEX.containsMatchIn(trimmed) ||
                PATH_LIKE_REGEX.containsMatchIn(trimmed)
        }

        internal fun normalizeSpokenOutput(text: String): String {
            return text
                .replace(Regex("""\[([^\]]+)]\(([^)]+)\)"""), "$1")
                .replace(Regex("""\[\s*(?:short|medium|long)?\s*pause\s*]""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""<\s*/?\s*(?:(?:short|medium|long)?\s*pause|sigh|laugh|gasp|uhm|break\b[^>]*)\s*>""", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("""https?://"""), "")
                .replace("```", " ")
                .replace("`", "")
                .replace("**", "")
                .replace("__", "")
                .replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
                .replace(Regex("""(?m)^\s*[-*+]\s+"""), "")
                .replace(Regex("""(?<!\*)\*([^*\n]+?)\*(?!\*)"""), "$1")
                .replace(Regex("""(?<=[\p{L}\p{N}])_(?=[\p{L}\p{N}])"""), " ")
                .replace(Regex("""(?<=[\p{L}\p{N})])\h*\n+\s*"""), ". ")
                .replace(Regex("""\h+([.!?,;:])"""), "$1")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private val MARKDOWN_LINE_REGEX = Regex("""(?m)^\s*(?:#{1,6}\s+|[-*+]\s+|\d+\.\s+|\|.+\|)""")
        private val PATH_LIKE_REGEX = Regex("""(?:^|\s)(?:~?/|[\w.-]+/[\w./-]+)""")
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

        internal fun normalizeOpenAiVoice(voice: String): String {
            val normalized = voice.trim().lowercase()
            return if (normalized in OPENAI_VOICES) normalized else DEFAULT_OPENAI_VOICE
        }
        const val DEFAULT_KOKORO_SPEAKER_ID = 0
        const val MIN_TTS_READ_TIMEOUT_MS = 60_000L
        const val DEFAULT_TTS_READ_TIMEOUT_MS = 90_000L
        const val DEFAULT_SUMMARIZE_TIMEOUT_MS = 10_000L

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
