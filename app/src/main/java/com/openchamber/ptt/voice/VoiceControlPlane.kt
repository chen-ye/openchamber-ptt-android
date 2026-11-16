package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.SpokenDetailLevel
import com.openchamber.ptt.tts.TtsClient
import com.openchamber.ptt.tts.TtsInputMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class VoiceRoute {
    PASS_THROUGH,
    ANSWER_DIRECTLY,
    LIVE_TUNING,
}

sealed interface VoiceControlDecision {
    data object PassThrough : VoiceControlDecision
    data class AnswerDirectly(val spokenAnswer: String) : VoiceControlDecision
    data class LiveTuning(
        val detailLevel: SpokenDetailLevel? = null,
        val inputMode: TtsInputMode? = null,
        val confirmationSpeech: String,
    ) : VoiceControlDecision
}

/**
 * Lightweight turn-based Voice Control Plane backed by `gemini-decision-1`
 * (via OpenChamber's `gemini-decisions-bridge` guest proxy) and `/api/small-model/generate`.
 *
 * Evaluates `{ utterance, lastReply, currentSettings }` and fails open to [VoiceRoute.PASS_THROUGH]
 * on any timeout or unavailability so coding prompts are never dropped.
 */
class VoiceControlPlane(
    httpClient: OkHttpClient,
    private val decisionTimeoutMs: Long = DEFAULT_DECISION_TIMEOUT_MS,
    private val answerTimeoutMs: Long = DEFAULT_ANSWER_TIMEOUT_MS,
    private val json: Json = DefaultJson,
) {
    private val decisionClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(decisionTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(decisionTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val answerClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(answerTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(answerTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun evaluateTurn(
        serverUrl: String,
        utterance: String,
        lastReply: String?,
        currentDetailLevel: SpokenDetailLevel,
        currentInputMode: TtsInputMode,
    ): VoiceControlDecision {
        val cleanUtterance = utterance.trim()
        if (cleanUtterance.isEmpty()) return VoiceControlDecision.PassThrough

        val routeChoice = classifyRoute(
            serverUrl = serverUrl,
            utterance = cleanUtterance,
            lastReply = lastReply,
            currentDetailLevel = currentDetailLevel,
            currentInputMode = currentInputMode,
        )

        return when (routeChoice.route) {
            VoiceRoute.PASS_THROUGH -> VoiceControlDecision.PassThrough

            VoiceRoute.LIVE_TUNING -> {
                val targetDetail = routeChoice.detailLevel
                val targetMode = routeChoice.inputMode
                if (targetDetail == null && targetMode == null) {
                    VoiceControlDecision.PassThrough
                } else {
                    val parts = listOfNotNull(
                        targetMode?.let { "${it.title} mode" },
                        targetDetail?.let { "${it.title} detail" },
                    )
                    VoiceControlDecision.LiveTuning(
                        detailLevel = targetDetail,
                        inputMode = targetMode,
                        confirmationSpeech = "Switched to ${parts.joinToString(" and ")}.",
                    )
                }
            }

            VoiceRoute.ANSWER_DIRECTLY -> {
                val reply = lastReply?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return VoiceControlDecision.PassThrough
                val answer = generateDirectAnswer(
                    serverUrl = serverUrl,
                    utterance = cleanUtterance,
                    lastReply = reply,
                ) ?: return VoiceControlDecision.PassThrough
                VoiceControlDecision.AnswerDirectly(spokenAnswer = answer)
            }
        }
    }

    internal data class RouteClassification(
        val route: VoiceRoute,
        val detailLevel: SpokenDetailLevel? = null,
        val inputMode: TtsInputMode? = null,
    )

    internal suspend fun classifyRoute(
        serverUrl: String,
        utterance: String,
        lastReply: String?,
        currentDetailLevel: SpokenDetailLevel,
        currentInputMode: TtsInputMode,
    ): RouteClassification {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val hasLastReply = !lastReply.isNullOrBlank()
        val stateJson = buildMap<String, JsonElement> {
            put("user_utterance", JsonPrimitive(utterance))
            put("current_detail_level", JsonPrimitive(currentDetailLevel.name))
            put("current_input_mode", JsonPrimitive(currentInputMode.name))
            if (hasLastReply) {
                put("last_assistant_reply", JsonPrimitive(lastReply.orEmpty().take(MAX_REPLY_CONTEXT_CHARS)))
            }
        }

        val questionsMap = buildMap<String, SystemOneQuestion> {
            put(
                "route",
                SystemOneQuestion(
                    type = "choice",
                    instructions = "Route the user's spoken utterance. Choose 'live_tuning' ONLY if the user is explicitly asking to change spoken detail level (essentials/balanced/detailed) or speech input mode (spoken/sanitized/raw). Choose 'answer_directly' ONLY if last_assistant_reply is present and completely answers the user's follow-up question (e.g. asking to clarify or expand on an option/detail from that last reply) without needing new code edits, file reads, or tool execution. Otherwise choose 'pass_through'.",
                    criteria = mapOf(
                        "pass_through" to "A coding task, command, question requiring codebase/tool access, or anything not fully answered by last_assistant_reply.",
                        "answer_directly" to "A clarifying or follow-up question that can be answered completely from last_assistant_reply alone.",
                        "live_tuning" to "An explicit request to change spoken detail level (essentials, balanced, detailed) or speech input mode (spoken, sanitized, raw).",
                    ),
                ),
            )
            put(
                "tuning_detail",
                SystemOneQuestion(
                    type = "choice",
                    instructions = "If route is live_tuning and the user requested a detail level, pick it; otherwise pick none.",
                    criteria = mapOf(
                        "essentials" to "User wants brief/essentials/triage level.",
                        "balanced" to "User wants balanced/normal level.",
                        "detailed" to "User wants detailed/full/technical level.",
                        "none" to "Not changing detail level.",
                    ),
                ),
            )
            put(
                "tuning_mode",
                SystemOneQuestion(
                    type = "choice",
                    instructions = "If route is live_tuning and the user requested a speech input mode, pick it; otherwise pick none.",
                    criteria = mapOf(
                        "spoken" to "User wants spoken/translated/summarized speech input mode.",
                        "sanitized" to "User wants sanitized speech input mode.",
                        "raw" to "User wants raw/verbatim speech input mode.",
                        "none" to "Not changing speech input mode.",
                    ),
                ),
            )
        }

        val systemOneBody = SystemOneRequest(
            model = DECISION_MODEL,
            state = JsonObject(stateJson),
            questions = questionsMap,
        )
        val guestPayload = json.encodeToString(
            GuestServiceRequest(
                method = "POST",
                path = SYSTEM_ONE_PATH,
                body = json.encodeToJsonElement(SystemOneRequest.serializer(), systemOneBody),
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl$GUEST_DECISIONS_SERVICE_REQUEST_PATH")
            .post(guestPayload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        return try {
            val (status, respText) = OpenChamberHttp.executeText(decisionClient, request)
            if (status !in 200..299 || respText.isBlank()) {
                return RouteClassification(VoiceRoute.PASS_THROUGH)
            }
            val wrapper = json.parseToJsonElement(respText).jsonObject
            val innerStatus = wrapper["status"]?.jsonPrimitive?.intOrNull ?: status
            val innerBody = wrapper["body"]?.jsonPrimitive?.contentOrNull
                ?: return RouteClassification(VoiceRoute.PASS_THROUGH)
            if (innerStatus !in 200..299) {
                return RouteClassification(VoiceRoute.PASS_THROUGH)
            }
            val decisionObj = json.parseToJsonElement(innerBody).jsonObject
            val answers = decisionObj["answers"]?.jsonObject ?: return RouteClassification(VoiceRoute.PASS_THROUGH)
            val routeChoice = answers["route"]?.jsonObject?.get("choice")?.jsonPrimitive?.contentOrNull
            val detailChoice = answers["tuning_detail"]?.jsonObject?.get("choice")?.jsonPrimitive?.contentOrNull
            val modeChoice = answers["tuning_mode"]?.jsonObject?.get("choice")?.jsonPrimitive?.contentOrNull

            when (routeChoice) {
                "answer_directly" -> if (hasLastReply) {
                    RouteClassification(VoiceRoute.ANSWER_DIRECTLY)
                } else {
                    RouteClassification(VoiceRoute.PASS_THROUGH)
                }
                "live_tuning" -> {
                    val level = when (detailChoice) {
                        "essentials" -> SpokenDetailLevel.ESSENTIALS
                        "balanced" -> SpokenDetailLevel.BALANCED
                        "detailed" -> SpokenDetailLevel.DETAILED
                        else -> null
                    }
                    val mode = when (modeChoice) {
                        "spoken" -> TtsInputMode.SUMMARIZED
                        "sanitized" -> TtsInputMode.SANITIZED
                        "raw" -> TtsInputMode.RAW
                        else -> null
                    }
                    RouteClassification(
                        route = VoiceRoute.LIVE_TUNING,
                        detailLevel = level,
                        inputMode = mode,
                    )
                }
                else -> RouteClassification(VoiceRoute.PASS_THROUGH)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            RouteClassification(VoiceRoute.PASS_THROUGH)
        }
    }

    private suspend fun generateDirectAnswer(
        serverUrl: String,
        utterance: String,
        lastReply: String,
    ): String? {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val payload = json.encodeToString(
            SmallModelRequest(
                prompt = "Previous assistant reply:\n$lastReply\n\nUser follow-up question:\n$utterance",
                system = ANSWER_DIRECTLY_SYSTEM_PROMPT,
            ),
        )
        val request = Request.Builder()
            .url("$baseUrl${OpenChamberHttp.SMALL_MODEL_GENERATE_PATH}")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .build()

        return try {
            val (status, respText) = OpenChamberHttp.executeText(answerClient, request)
            if (status !in 200..299 || respText.isBlank()) return null
            val obj = json.parseToJsonElement(respText).jsonObject
            val raw = obj["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            TtsClient.normalizeSpokenOutput(raw).ifEmpty { null }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    @Serializable
    private data class GuestServiceRequest(
        val method: String,
        val path: String,
        val body: JsonElement,
    )

    @Serializable
    private data class SystemOneRequest(
        val model: String,
        val state: JsonObject,
        val questions: Map<String, SystemOneQuestion>,
    )

    @Serializable
    private data class SystemOneQuestion(
        val type: String,
        val instructions: String,
        val criteria: Map<String, String>,
    )

    @Serializable
    private data class SmallModelRequest(
        val prompt: String,
        val system: String,
    )

    companion object {
        const val GUEST_DECISIONS_SERVICE_REQUEST_PATH = "/api/guests/gemini-decisions-bridge/service/request"
        const val SYSTEM_ONE_PATH = "/v1/systemone"
        const val DECISION_MODEL = "gemini-decision-1"
        const val DEFAULT_DECISION_TIMEOUT_MS = 1_800L
        const val DEFAULT_ANSWER_TIMEOUT_MS = 5_000L
        private const val MAX_REPLY_CONTEXT_CHARS = 8_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val ANSWER_DIRECTLY_SYSTEM_PROMPT =
            "Answer the user's follow-up question directly using ONLY the provided previous assistant reply. " +
                "Speak in clear, natural plain prose for text-to-speech listening (no markdown, no backticks, no raw URLs, no audio tags). " +
                "If the previous reply does not actually contain the answer, say so briefly."

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
