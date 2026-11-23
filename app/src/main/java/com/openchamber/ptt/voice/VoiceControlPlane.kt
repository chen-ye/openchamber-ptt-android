package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.tts.AudioCueMode
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

data class UnreadReplyPreview(
    val messageId: String,
    val sourceLabel: String,
    val previewText: String,
)

data class VoiceReplyContext(
    val focusedReply: String? = null,
    val focusedTriggerLabel: String? = null,
    val sameTurnSiblings: List<String> = emptyList(),
    val pendingUnreadPreviews: List<UnreadReplyPreview> = emptyList(),
)

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
        val cueMode: AudioCueMode? = null,
        val confirmationSpeech: String,
    ) : VoiceControlDecision
}

/**
 * Lightweight turn-based Voice Control Plane backed by `gemini-decision-1`
 * (via OpenChamber's `gemini-decisions-bridge` guest proxy) and `/api/small-model/generate`.
 *
 * Evaluates `{ utterance, replyContext, currentSettings }` and fails open to [VoiceRoute.PASS_THROUGH]
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
        replyContext: VoiceReplyContext,
        currentDetailLevel: SpokenDetailLevel,
        currentInputMode: TtsInputMode,
        allowDirectAnswer: Boolean = true,
        currentCueMode: AudioCueMode = AudioCueMode.HYBRID,
    ): VoiceControlDecision {
        val cleanUtterance = utterance.trim()
        if (cleanUtterance.isEmpty()) return VoiceControlDecision.PassThrough

        val formattedContext = if (allowDirectAnswer) formatReplyContext(replyContext) else null
        val routeChoice = classifyRoute(
            serverUrl = serverUrl,
            utterance = cleanUtterance,
            formattedContext = formattedContext,
            currentDetailLevel = currentDetailLevel,
            currentInputMode = currentInputMode,
            allowDirectAnswer = allowDirectAnswer,
            currentCueMode = currentCueMode,
        )

        return when (routeChoice.route) {
            VoiceRoute.PASS_THROUGH -> VoiceControlDecision.PassThrough

            VoiceRoute.LIVE_TUNING -> {
                val targetDetail = routeChoice.detailLevel
                val targetMode = routeChoice.inputMode
                val targetCueMode = routeChoice.cueMode
                if (targetDetail == null && targetMode == null && targetCueMode == null) {
                    VoiceControlDecision.PassThrough
                } else {
                    val parts = listOfNotNull(
                        targetMode?.let { "${it.title} mode" },
                        targetDetail?.let { "${it.title} detail" },
                        targetCueMode?.let {
                            when (it) {
                                AudioCueMode.HYBRID -> "Hybrid cues"
                                AudioCueMode.VOCAL -> "Vocal cues"
                                AudioCueMode.TONES -> "Studio tones"
                            }
                        },
                    )
                    VoiceControlDecision.LiveTuning(
                        detailLevel = targetDetail,
                        inputMode = targetMode,
                        cueMode = targetCueMode,
                        confirmationSpeech = "Switched to ${parts.joinToString(" and ")}.",
                    )
                }
            }

            VoiceRoute.ANSWER_DIRECTLY -> {
                if (!allowDirectAnswer || formattedContext == null || !formattedContext.hasAnyContext) {
                    return VoiceControlDecision.PassThrough
                }
                val answer = generateDirectAnswer(
                    serverUrl = serverUrl,
                    utterance = cleanUtterance,
                    formattedContext = formattedContext,
                ) ?: return VoiceControlDecision.PassThrough
                VoiceControlDecision.AnswerDirectly(spokenAnswer = answer)
            }
        }
    }

    internal data class RouteClassification(
        val route: VoiceRoute,
        val detailLevel: SpokenDetailLevel? = null,
        val inputMode: TtsInputMode? = null,
        val cueMode: AudioCueMode? = null,
    )

    internal data class FormattedReplyContext(
        val focusedReply: String?,
        val focusedTriggerLabel: String?,
        val sameTurnSiblings: String?,
        val pendingUnreadReplies: String?,
        val lastAssistantReply: String?,
    ) {
        val hasLastReply: Boolean get() = lastAssistantReply != null
        val hasPendingUnread: Boolean get() = pendingUnreadReplies != null
        val hasAnyContext: Boolean get() = hasLastReply || hasPendingUnread
    }

    internal fun formatReplyContext(replyContext: VoiceReplyContext): FormattedReplyContext {
        val rawFocused = replyContext.focusedReply?.trim()?.takeIf { it.isNotEmpty() }
        val triggerLabel = replyContext.focusedTriggerLabel?.trim()?.takeIf { it.isNotEmpty() }
        val siblings = replyContext.sameTurnSiblings.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        val unread = replyContext.pendingUnreadPreviews.mapNotNull { preview ->
            val cleanedText = preview.previewText
                .trim()
                .replace(WHITESPACE_REGEX, " ")
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val label = preview.sourceLabel.trim().ifEmpty { "background" }
            label to cleanedText
        }

        val hasExtras = siblings.isNotEmpty() || unread.isNotEmpty()
        val focusedBudget = if (hasExtras) FOCUSED_BUDGET_WITH_EXTRAS else MAX_REPLY_CONTEXT_CHARS
        val focusedForPrompt = rawFocused?.take(focusedBudget)
        val focusedForState = rawFocused?.let { reply ->
            if (triggerLabel != null) {
                "[Trigger: $triggerLabel]\n$reply".take(focusedBudget)
            } else {
                reply.take(focusedBudget)
            }
        }

        val siblingsSeparator = if (focusedForState != null) "\n\n" else ""
        val siblingsHeader = "Other steps in same turn:\n"
        val siblingsContentBudget = (SIBLINGS_BUDGET - siblingsSeparator.length - siblingsHeader.length)
            .coerceAtLeast(0)
        val formattedSiblings = formatSiblings(siblings, siblingsContentBudget)

        val formattedUnread = formatUnreadPreviews(unread, UNREAD_BUDGET)

        val lastAssistantReply = buildString {
            if (focusedForState != null) {
                append(focusedForState)
            }
            if (formattedSiblings != null) {
                append(siblingsSeparator)
                append(siblingsHeader)
                append(formattedSiblings)
            }
        }.takeIf { it.isNotEmpty() }

        return FormattedReplyContext(
            focusedReply = focusedForPrompt,
            focusedTriggerLabel = triggerLabel,
            sameTurnSiblings = formattedSiblings,
            pendingUnreadReplies = formattedUnread,
            lastAssistantReply = lastAssistantReply,
        )
    }

    private fun formatSiblings(
        siblings: List<String>,
        budget: Int,
    ): String? {
        if (siblings.isEmpty() || budget <= 0) return null
        val sb = StringBuilder()
        for (sibling in siblings) {
            val line = "- $sibling"
            val sep = if (sb.isEmpty()) "" else "\n"
            val remaining = budget - sb.length - sep.length
            if (remaining <= 0) break
            if (line.length <= remaining) {
                sb.append(sep).append(line)
            } else if (sb.isEmpty() || remaining >= MIN_TRUNCATED_SIBLING_CHARS) {
                val truncated = if (remaining > 1) line.take(remaining - 1) + "…" else line.take(remaining)
                sb.append(sep).append(truncated)
                break
            } else {
                break
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun formatUnreadPreviews(
        previews: List<Pair<String, String>>,
        budget: Int,
    ): String? {
        if (previews.isEmpty() || budget <= 0) return null
        val sb = StringBuilder()
        for ((label, text) in previews) {
            val snippet = if (text.length > UNREAD_PREVIEW_MAX_CHARS) {
                text.take(UNREAD_PREVIEW_MAX_CHARS - 1) + "…"
            } else {
                text
            }
            val line = "[UNREAD · $label]: $snippet"
            val sep = if (sb.isEmpty()) "" else "\n"
            if (sb.length + sep.length + line.length <= budget) {
                sb.append(sep).append(line)
            } else if (sb.isEmpty()) {
                sb.append(line.take(budget))
                break
            } else {
                break
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    internal suspend fun classifyRoute(
        serverUrl: String,
        utterance: String,
        formattedContext: FormattedReplyContext?,
        currentDetailLevel: SpokenDetailLevel,
        currentInputMode: TtsInputMode,
        allowDirectAnswer: Boolean = true,
        currentCueMode: AudioCueMode = AudioCueMode.HYBRID,
    ): RouteClassification {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val hasLastReply = allowDirectAnswer && formattedContext?.hasLastReply == true
        val hasPendingUnread = allowDirectAnswer && formattedContext?.hasPendingUnread == true
        val stateJson = buildMap<String, JsonElement> {
            put("user_utterance", JsonPrimitive(utterance))
            put("current_detail_level", JsonPrimitive(currentDetailLevel.name))
            put("current_input_mode", JsonPrimitive(currentInputMode.name))
            put("current_cue_mode", JsonPrimitive(currentCueMode.name))
            if (allowDirectAnswer && formattedContext != null) {
                put("has_pending_unread", JsonPrimitive(hasPendingUnread))
                formattedContext.lastAssistantReply?.let { put("last_assistant_reply", JsonPrimitive(it)) }
                formattedContext.pendingUnreadReplies?.let { put("pending_unread_replies", JsonPrimitive(it)) }
            }
        }

        val questionsMap = buildMap<String, SystemOneQuestion> {
            val routeInstructions = if (allowDirectAnswer) {
                "Route the user's spoken utterance. " +
                    "Choose 'live_tuning' ONLY if the user is explicitly asking to change spoken detail level (essentials/balanced/detailed), speech input mode (spoken/sanitized/raw), or audio cue mode (hybrid/vocal/tones). " +
                    "Choose 'answer_directly' ONLY if (a) last_assistant_reply is present and completely answers a clarifying or follow-up question about the focused reply without needing new code edits, file reads, or tool execution, OR " +
                    "(b) pending_unread_replies is present and the user is explicitly asking about the unread queue or what else finished in the background, and pending_unread_replies answers it. " +
                    "Choose 'pass_through' for ambiguous status queries when has_pending_unread is true, or whenever codebase/tool access or action is needed."
            } else {
                "Route the user's spoken utterance. " +
                    "Choose 'live_tuning' ONLY if the user is explicitly asking to change spoken detail level (essentials/balanced/detailed), speech input mode (spoken/sanitized/raw), or audio cue mode (hybrid/vocal/tones). " +
                    "For everything else, choose 'pass_through'."
            }
            val routeCriteria = buildMap {
                put(
                    "pass_through",
                    if (allowDirectAnswer) {
                        "A coding task, command, question requiring codebase/tool access, an ambiguous status query when has_pending_unread is true, or anything not fully answered by the provided reply context."
                    } else {
                        "Any prompt, question, follow-up, command, or task to send straight to the session."
                    },
                )
                if (allowDirectAnswer) {
                    put(
                        "answer_directly",
                        "A clarifying/follow-up question answered completely by last_assistant_reply, or an explicit queue/background-status question answered completely by pending_unread_replies.",
                    )
                }
                put(
                    "live_tuning",
                    "An explicit request to change spoken detail level (essentials, balanced, detailed), speech input mode (spoken, sanitized, raw), or audio cue mode (hybrid, vocal cues, studio tones).",
                )
            }
            put(
                "route",
                SystemOneQuestion(
                    type = "choice",
                    instructions = routeInstructions,
                    criteria = routeCriteria,
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
            put(
                "tuning_cues",
                SystemOneQuestion(
                    type = "choice",
                    instructions = "If route is live_tuning and the user requested an audio cue mode, pick it; otherwise pick none.",
                    criteria = mapOf(
                        "hybrid" to "User wants hybrid cues (crisp tones for button taps, spoken cues for latency and errors).",
                        "vocal" to "User wants vocal cues or spoken status updates.",
                        "tones" to "User wants studio tones or non-verbal earcons only.",
                        "none" to "Not changing audio cue mode.",
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
            val cuesChoice = answers["tuning_cues"]?.jsonObject?.get("choice")?.jsonPrimitive?.contentOrNull

            when (routeChoice) {
                "answer_directly" -> if (allowDirectAnswer && (hasLastReply || hasPendingUnread)) {
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
                    val cueMode = when (cuesChoice) {
                        "hybrid" -> AudioCueMode.HYBRID
                        "vocal" -> AudioCueMode.VOCAL
                        "tones" -> AudioCueMode.TONES
                        else -> null
                    }
                    RouteClassification(
                        route = VoiceRoute.LIVE_TUNING,
                        detailLevel = level,
                        inputMode = mode,
                        cueMode = cueMode,
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
        formattedContext: FormattedReplyContext,
    ): String? {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val prompt = buildString {
            formattedContext.focusedReply?.let { focused ->
                val labelSuffix = formattedContext.focusedTriggerLabel?.let { " ($it)" }.orEmpty()
                append("Focused assistant reply$labelSuffix:\n")
                append(focused)
            }
            formattedContext.sameTurnSiblings?.let { siblings ->
                if (isNotEmpty()) append("\n\n")
                append("Other steps in same turn:\n")
                append(siblings)
            }
            formattedContext.pendingUnreadReplies?.let { unread ->
                if (isNotEmpty()) append("\n\n")
                append("Queued unread replies (only if user asks what else finished):\n")
                append(unread)
            }
            append("\n\nUser follow-up question:\n")
            append(utterance)
        }
        val payload = json.encodeToString(
            SmallModelRequest(
                prompt = prompt,
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
        internal const val MAX_REPLY_CONTEXT_CHARS = 8_000
        internal const val FOCUSED_BUDGET_WITH_EXTRAS = 4_800
        internal const val SIBLINGS_BUDGET = 2_000
        internal const val UNREAD_BUDGET = 1_200
        internal const val UNREAD_PREVIEW_MAX_CHARS = 350
        private const val MIN_TRUNCATED_SIBLING_CHARS = 60
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val ANSWER_DIRECTLY_SYSTEM_PROMPT =
            "Answer the user's follow-up question directly using ONLY the provided reply context. " +
                "Prefer the focused assistant reply (and other steps in the same turn if relevant) for follow-up questions; " +
                "use queued unread replies ONLY if the user explicitly asks what else finished or about queued/background messages. " +
                "Speak in clear, natural plain prose for text-to-speech listening (no markdown, no backticks, no raw URLs, no audio tags). " +
                "If the provided context does not actually contain the answer, say so briefly."

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
