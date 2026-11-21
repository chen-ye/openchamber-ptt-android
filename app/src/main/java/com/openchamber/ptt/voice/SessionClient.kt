package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.session.SessionApiException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class LatestTurn(
    val userText: String? = null,
    val assistantText: String? = null,
    val assistantMessageId: String? = null,
)

data class TurnsWindow(
    val records: List<ParsedSessionRecord>,
    val turns: List<SessionTurn>,
    val reachedHistoryStart: Boolean,
    val latestSettledMessageId: String?,
)

class SessionClient(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
) {
    suspend fun fetchTurnsWindow(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
        stopAtMessageIds: Set<String> = emptySet(),
        stopOnAnyTarget: Boolean = false,
        maxPages: Int = DEFAULT_MAX_PAGES,
        pageLimit: Int = DEFAULT_PAGE_LIMIT,
    ): TurnsWindow {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val encodedSessionId = OpenChamberHttp.encodeUriComponent(sessionId)
        val effectiveLimit = pageLimit.coerceAtLeast(1)
        val effectiveMaxPages = maxPages.coerceAtLeast(1)
        val stopTargets = stopAtMessageIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val collectedDesc = ArrayList<ParsedSessionRecord>()
        val seenIds = HashSet<String>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null
        var page = 0
        var reachedHistoryStart = false
        var seenNonOpener = false
        var seenOpenerAfterTurnContent = false

        while (page < effectiveMaxPages) {
            page++
            val url = buildString {
                append(baseUrl)
                append("/api/session/")
                append(encodedSessionId)
                append("/message?limit=")
                append(effectiveLimit)
                val cur = cursor
                if (!cur.isNullOrBlank()) {
                    append("&cursor=")
                    append(OpenChamberHttp.encodeUriComponent(cur))
                }
            }

            val requestBuilder = Request.Builder()
                .url(url)
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

            val parsedPage = parseMessagesPage(bodyText, statusCode)
            if (parsedPage.records.isEmpty()) {
                reachedHistoryStart = true
                break
            }

            var addedInPage = 0
            for (record in parsedPage.records) {
                if (!seenIds.add(record.id)) continue
                collectedDesc.add(record)
                addedInPage++
                when (record) {
                    is ParsedSessionRecord.AssistantRecord,
                    is ParsedSessionRecord.IdleRecord,
                    -> seenNonOpener = true
                    is ParsedSessionRecord.UserRecord -> {
                        if (seenNonOpener) {
                            seenOpenerAfterTurnContent = true
                        }
                    }
                    is ParsedSessionRecord.SyntheticRecord -> {
                        if (record.triggerKind != null && seenNonOpener) {
                            seenOpenerAfterTurnContent = true
                        }
                    }
                    is ParsedSessionRecord.OtherRecord -> Unit
                }
            }

            val nextCursor = parsedPage.nextCursor
            if (nextCursor == null) {
                reachedHistoryStart = true
                break
            }
            if (addedInPage == 0 || !seenCursors.add(nextCursor)) {
                break
            }

            val stopTargetsSatisfied = if (stopTargets.isEmpty()) {
                true
            } else if (stopOnAnyTarget) {
                stopTargets.any { it in seenIds }
            } else {
                stopTargets.all { it in seenIds }
            }
            val hasCompleteTurnBoundary = seenOpenerAfterTurnContent ||
                (stopTargets.isNotEmpty() && stopTargetsSatisfied && !seenNonOpener)
            if (hasCompleteTurnBoundary && stopTargetsSatisfied) {
                break
            }

            cursor = nextCursor
        }

        val chronologicalRecords = collectedDesc.asReversed()
        val turns = SessionTurnProjection.buildSessionTurns(chronologicalRecords)
        val latestSettledMessageId = SessionTurnProjection.computeLatestSettledMessageId(
            chronologicalRecords = chronologicalRecords,
            turns = turns,
        )
        return TurnsWindow(
            records = chronologicalRecords,
            turns = turns,
            reachedHistoryStart = reachedHistoryStart,
            latestSettledMessageId = latestSettledMessageId,
        )
    }

    suspend fun getLatestAssistantMessageId(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
    ): String? {
        val window = fetchTurnsWindow(
            serverUrl = serverUrl,
            sessionId = sessionId,
            directory = directory,
            maxPages = 1,
            pageLimit = DEFAULT_PAGE_LIMIT,
        )
        return window.records.lastOrNull { it is ParsedSessionRecord.AssistantRecord }?.id
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
        val cleanAfterId = afterMessageId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanAfterId == null) {
            val window = fetchTurnsWindow(
                serverUrl = serverUrl,
                sessionId = sessionId,
                directory = directory,
                maxPages = 2,
                pageLimit = DEFAULT_PAGE_LIMIT,
            )
            return window.records
                .asReversed()
                .asSequence()
                .filterIsInstance<ParsedSessionRecord.AssistantRecord>()
                .mapNotNull { it.text?.trim()?.takeIf { text -> text.isNotEmpty() } }
                .firstOrNull()
        }

        val window = fetchTurnsWindow(
            serverUrl = serverUrl,
            sessionId = sessionId,
            directory = directory,
            stopAtMessageIds = setOf(cleanAfterId),
            maxPages = DEFAULT_MAX_PAGES,
            pageLimit = DEFAULT_PAGE_LIMIT,
        )
        val afterIdx = window.records.indexOfFirst { it.id == cleanAfterId }
        val newerRecords = if (afterIdx >= 0) {
            window.records.subList(afterIdx + 1, window.records.size)
        } else {
            window.records
        }
        val newAssistantTexts = newerRecords
            .asSequence()
            .filterIsInstance<ParsedSessionRecord.AssistantRecord>()
            .mapNotNull { it.text?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .toList()

        if (newAssistantTexts.isEmpty()) return null
        return newAssistantTexts.joinToString("\n\n")
    }

    suspend fun fetchLatestTurn(
        serverUrl: String,
        sessionId: String,
        directory: String? = null,
    ): LatestTurn {
        val window = fetchTurnsWindow(
            serverUrl = serverUrl,
            sessionId = sessionId,
            directory = directory,
            maxPages = 2,
            pageLimit = DEFAULT_PAGE_LIMIT,
        )
        val latestTurnWithResponses = window.turns.lastOrNull { it.responses.isNotEmpty() }
        if (latestTurnWithResponses != null) {
            val joinedAssistant = latestTurnWithResponses.responses
                .asSequence()
                .map { it.assistantText.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
                .takeIf { it.isNotEmpty() }
            return LatestTurn(
                userText = latestTurnWithResponses.triggerText,
                assistantText = joinedAssistant,
                assistantMessageId = latestTurnWithResponses.responses.lastOrNull()?.messageId,
            )
        }

        return LatestTurn(
            userText = window.turns.lastOrNull()?.triggerText,
            assistantText = null,
            assistantMessageId = null,
        )
    }

    private fun parseMessagesPage(bodyText: String, statusCode: Int): ParsedMessagesPage {
        val root: JsonElement = try {
            json.parseToJsonElement(bodyText)
        } catch (e: Exception) {
            throw SessionApiException(statusCode, "Invalid session messages JSON payload: ${e.message}", e)
        }

        return when (root) {
            is JsonArray -> ParsedMessagesPage(
                records = parseRecordsArray(root),
                nextCursor = null,
            )
            is JsonObject -> {
                val array = root["data"] as? JsonArray
                    ?: throw SessionApiException(statusCode, "Session messages payload missing 'data' array")
                val cursorObj = root["cursor"] as? JsonObject
                val nextCursor = cursorObj?.stringOrNull("next")?.trim()?.takeIf { it.isNotEmpty() }
                ParsedMessagesPage(
                    records = parseRecordsArray(array),
                    nextCursor = nextCursor,
                )
            }
            else -> throw SessionApiException(statusCode, "Unexpected session messages JSON shape")
        }
    }

    private fun parseRecordsArray(array: JsonArray): List<ParsedSessionRecord> =
        array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseRecord(obj)
        }

    private fun parseRecord(obj: JsonObject): ParsedSessionRecord? {
        val info = obj["info"] as? JsonObject
        val id = obj.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.stringOrNull("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null

        val timeObj = (obj["time"] as? JsonObject) ?: (info?.get("time") as? JsonObject)
        val hasTimeObject = timeObj != null
        val createdAtMs = timeObj?.get("created").asEpochMillisOrNull()
            ?: obj["createdAt"].asEpochMillisOrNull()
            ?: info?.get("createdAt").asEpochMillisOrNull()
            ?: 0L

        val roleOrType = obj.stringOrNull("type")?.trim()?.takeIf { it.isNotEmpty() && it != "message" }
            ?: obj.stringOrNull("role")?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.stringOrNull("type")?.trim()?.takeIf { it.isNotEmpty() && it != "message" }
            ?: info?.stringOrNull("role")?.trim()?.takeIf { it.isNotEmpty() }

        val syntheticFlag = (obj["synthetic"] as? JsonPrimitive)?.booleanOrNull == true ||
            (info?.get("synthetic") as? JsonPrimitive)?.booleanOrNull == true

        return when {
            roleOrType == "synthetic" || (roleOrType == "user" && syntheticFlag) -> {
                parseSyntheticRecord(id = id, createdAtMs = createdAtMs, obj = obj, info = info)
            }
            roleOrType == "user" -> {
                val rawText = extractMessageText(obj, info).orEmpty()
                ParsedSessionRecord.UserRecord(
                    id = id,
                    createdAtMs = createdAtMs,
                    cleanText = stripVoiceTagPrefix(rawText),
                )
            }
            roleOrType == "assistant" -> {
                val completedAtMs = timeObj?.get("completed").asEpochMillisOrNull()
                val finish = obj.stringOrNull("finish")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: info?.stringOrNull("finish")?.trim()?.takeIf { it.isNotEmpty() }
                ParsedSessionRecord.AssistantRecord(
                    id = id,
                    createdAtMs = createdAtMs,
                    completedAtMs = completedAtMs,
                    finish = finish,
                    hasTimeObject = hasTimeObject,
                    text = extractMessageText(obj, info),
                )
            }
            roleOrType == "idle" -> {
                val outcome = obj.stringOrNull("outcome")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: info?.stringOrNull("outcome")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "succeeded"
                ParsedSessionRecord.IdleRecord(
                    id = id,
                    createdAtMs = createdAtMs,
                    outcome = outcome,
                )
            }
            else -> ParsedSessionRecord.OtherRecord(
                id = id,
                createdAtMs = createdAtMs,
            )
        }
    }

    private fun parseSyntheticRecord(
        id: String,
        createdAtMs: Long,
        obj: JsonObject,
        info: JsonObject?,
    ): ParsedSessionRecord.SyntheticRecord {
        val metadata = (obj["metadata"] as? JsonObject) ?: (info?.get("metadata") as? JsonObject)
        val source = metadata?.stringOrNull("source")?.trim()
        val triggerKind = when (source) {
            "openchamber-session" -> TurnTriggerKind.ASYNC_SESSION_RESULT
            "subagent" -> TurnTriggerKind.SUBAGENT_REPORT
            else -> null
        }

        val rawText = extractMessageText(obj, info)
        val description = obj.stringOrNull("description")?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.stringOrNull("description")?.trim()?.takeIf { it.isNotEmpty() }
        val tagAttrs = rawText?.let(::extractSyntheticTagAttributes).orEmpty()
        val unwrappedText = rawText?.let(::unwrapSyntheticText)?.takeIf { it.isNotEmpty() }
        val cleanText = unwrappedText ?: description.orEmpty()

        val triggerLabel = description
            ?: metadata?.firstNonBlankString("label", "title", "sessionTitle", "agent", "subagent", "name")
            ?: tagAttrs.firstNonBlank("title", "label", "agent", "subagent", "name")
            ?: metadata?.firstNonBlankString("sessionId", "sessionID")
            ?: tagAttrs.firstNonBlank("sessionId", "sessionID")

        return ParsedSessionRecord.SyntheticRecord(
            id = id,
            createdAtMs = createdAtMs,
            triggerKind = triggerKind,
            triggerLabel = triggerLabel,
            cleanText = cleanText,
        )
    }

    private fun extractMessageText(obj: JsonObject, info: JsonObject?): String? {
        val partsArray = (obj["parts"] as? JsonArray)
            ?: (obj["content"] as? JsonArray)
            ?: (info?.get("parts") as? JsonArray)
            ?: (info?.get("content") as? JsonArray)

        val textParts = partsArray.orEmpty().mapNotNull { partElement ->
            val partObj = partElement as? JsonObject ?: return@mapNotNull null
            if (partObj.stringOrNull("type") != "text") return@mapNotNull null
            partObj.stringOrNull("text")?.takeIf { it.isNotBlank() }
        }
        if (textParts.isNotEmpty()) {
            return textParts.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }
        }

        return obj.stringOrNull("text")?.trim()?.takeIf { it.isNotEmpty() }
            ?: obj.stringOrNull("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.stringOrNull("text")?.trim()?.takeIf { it.isNotEmpty() }
            ?: info?.stringOrNull("content")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun executeRequest(
        request: Request,
        action: String,
    ): Pair<Int, String> = try {
        OpenChamberHttp.executeText(httpClient, request)
    } catch (e: IOException) {
        throw SessionApiException(null, "Network error $action: ${e.message}", e)
    }

    internal fun stripVoiceTagPrefix(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[voice]")) {
            trimmed.removePrefix("[voice]").trimStart('\r', '\n', ' ').trim()
        } else {
            trimmed
        }
    }

    internal fun unwrapSyntheticText(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        if (SYNTHETIC_SELF_CLOSING_REGEX.matches(trimmed)) return ""
        val paired = SYNTHETIC_PAIRED_REGEX.matchEntire(trimmed)
        if (paired != null) {
            return paired.groupValues[2].trim()
        }
        return trimmed
    }

    private fun extractSyntheticTagAttributes(rawText: String): Map<String, String> {
        val match = SYNTHETIC_OPEN_TAG_REGEX.find(rawText) ?: return emptyMap()
        val attrsChunk = match.groupValues.getOrNull(2).orEmpty()
        if (attrsChunk.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (attr in XML_ATTR_REGEX.findAll(attrsChunk)) {
            val key = attr.groupValues[1].trim()
            val value = (attr.groupValues[2].ifEmpty { attr.groupValues[3] }).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                map[key] = value
            }
        }
        return map
    }

    private data class ParsedMessagesPage(
        val records: List<ParsedSessionRecord>,
        val nextCursor: String?,
    )

    @Serializable
    private data class PromptRequest(
        val text: String,
    )

    companion object {
        const val DEFAULT_PAGE_LIMIT = 50
        const val DEFAULT_MAX_PAGES = 4
        const val MESSAGE_LIMIT = DEFAULT_PAGE_LIMIT
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SYNTHETIC_PAIRED_REGEX = Regex(
            pattern = """^\s*<(openchamber-session|subagent)(?:\s+(?:[^>"']|"[^"]*"|'[^']*')*)?>([\s\S]*?)</\1>\s*$""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val SYNTHETIC_SELF_CLOSING_REGEX = Regex(
            pattern = """^\s*<(openchamber-session|subagent)(?:\s+(?:[^>"']|"[^"]*"|'[^']*')*)?/>\s*$""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val SYNTHETIC_OPEN_TAG_REGEX = Regex(
            pattern = """^\s*<(openchamber-session|subagent)((?:\s+(?:[^>"']|"[^"]*"|'[^']*')*)?)/?>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        private val XML_ATTR_REGEX = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.firstNonBlankString(vararg keys: String): String? {
    for (key in keys) {
        val value = stringOrNull(key)?.trim()?.takeIf { it.isNotEmpty() }
        if (value != null) return value
    }
    return null
}

private fun Map<String, String>.firstNonBlank(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key]?.trim()?.takeIf { it.isNotEmpty() }
        if (value != null) return value
    }
    return null
}

private fun JsonElement?.asEpochMillisOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull
        ?: primitive.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
}
