package com.openchamber.ptt.session

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal class SessionApiException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class SessionSummary(
    val id: String,
    val title: String,
    val directory: String,
    val parentId: String? = null,
    val updatedAtMs: Long = 0L,
    val isUnread: Boolean = false,
    val isUnreadWithError: Boolean = false,
)

internal data class ServerSessionsResult(
    val server: ServerProfile,
    val sessions: List<SessionSummary> = emptyList(),
    val roots: List<SessionRootNode> = buildSessionTree(sessions),
    val error: String? = null,
    val isUnauthorized: Boolean = false,
)

@Serializable
internal data class SessionLocationDto(
    val directory: String? = null,
)

@Serializable
internal data class SessionTimeDto(
    val created: JsonElement? = null,
    val updated: JsonElement? = null,
    val busy: JsonElement? = null,
    val idle: JsonElement? = null,
    val viewed: JsonElement? = null,
    val archived: JsonElement? = null,
)

@Serializable
internal data class SessionDto(
    val id: String,
    val title: String? = null,
    val directory: String? = null,
    val location: SessionLocationDto? = null,
    val time: SessionTimeDto? = null,
    @SerialName("parentID") val parentID: String? = null,
    val outcome: String? = null,
    val metadata: JsonElement? = null,
)

@Serializable
internal data class SessionCursorDto(
    val next: String? = null,
    val previous: String? = null,
)

@Serializable
internal data class SessionsEnvelopeDto(
    val data: List<SessionDto> = emptyList(),
    val cursor: SessionCursorDto? = null,
)

internal class SessionRepository(
    private val httpClient: OkHttpClient,
    private val json: Json = DefaultJson,
) {
    suspend fun listAllServerSessions(
        servers: List<ServerProfile>,
    ): List<ServerSessionsResult> = coroutineScope {
        servers.map { server ->
            async {
                if (!server.isSignedIn) {
                    ServerSessionsResult(
                        server = server,
                        error = "Sign in required",
                        isUnauthorized = true,
                    )
                } else {
                    try {
                        val (sessions, roots) = listSessionsWithTree(
                            serverUrl = server.serverUrl,
                            directory = null,
                        )
                        ServerSessionsResult(server = server, sessions = sessions, roots = roots)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: SessionApiException) {
                        ServerSessionsResult(
                            server = server,
                            error = if (e.statusCode == 401) {
                                "Session expired — sign in again"
                            } else {
                                e.message ?: "Failed to fetch sessions"
                            },
                            isUnauthorized = e.statusCode == 401,
                        )
                    } catch (t: Throwable) {
                        ServerSessionsResult(
                            server = server,
                            error = t.message ?: "Failed to fetch sessions",
                        )
                    }
                }
            }
        }.awaitAll()
    }

    suspend fun listSessions(
        serverUrl: String,
        directory: String? = null,
    ): List<SessionSummary> = listSessionsWithTree(serverUrl, directory).first

    private suspend fun listSessionsWithTree(
        serverUrl: String,
        directory: String? = null,
    ): Pair<List<SessionSummary>, List<SessionRootNode>> {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val collected = mutableListOf<SessionSummary>()
        val archivedIds = HashSet<String>()
        val seenCursors = HashSet<String>()
        val seenRawIds = HashSet<String>()
        var cursor: String? = null
        var page = 0

        while (page < MAX_PAGES) {
            page++
            val url = buildString {
                append(baseUrl)
                append("/api/session?limit=")
                append(PAGE_LIMIT)
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

            if (directory.isNullOrBlank()) {
                OpenChamberHttp.omitDirectoryHeaders(requestBuilder)
            } else {
                OpenChamberHttp.applyDirectoryHeaders(requestBuilder, directory)
            }

            val (statusCode, bodyText) = try {
                OpenChamberHttp.executeText(httpClient, requestBuilder.build())
            } catch (e: IOException) {
                throw SessionApiException(null, "Network error fetching sessions: ${e.message}", e)
            }
            if (statusCode !in 200..299) {
                throw SessionApiException(
                    statusCode,
                    "Failed to fetch sessions ($statusCode): ${bodyText.ifBlank { "HTTP $statusCode" }}",
                )
            }

            val pageResult = withContext(Dispatchers.Default) {
                val envelope = try {
                    json.decodeFromString<SessionsEnvelopeDto>(bodyText)
                } catch (e: Exception) {
                    throw SessionApiException(statusCode, "Invalid sessions JSON payload: ${e.message}", e)
                }
                if (envelope.data.isEmpty()) {
                    null
                } else {
                    var newRawCount = 0
                    val pageActive = mutableListOf<SessionSummary>()
                    val pageArchived = mutableListOf<String>()
                    for (dto in envelope.data) {
                        if (!seenRawIds.add(dto.id)) {
                            continue
                        }
                        newRawCount++
                        val archivedMs = parseTimestampMs(dto.time?.archived)
                        val isArchived = (archivedMs ?: 0L) > 0L
                        if (isArchived) {
                            pageArchived.add(dto.id)
                            continue
                        }
                        pageActive.add(toSessionSummary(dto, isArchived = false))
                    }
                    Triple(newRawCount, pageActive to pageArchived, envelope.cursor?.next?.takeIf { it.isNotBlank() })
                }
            } ?: break

            val (newRawCount, lists, nextCursor) = pageResult
            archivedIds.addAll(lists.second)
            collected.addAll(lists.first)

            if (newRawCount == 0) {
                break
            }
            if (nextCursor == null || !seenCursors.add(nextCursor)) {
                break
            }
            cursor = nextCursor
        }

        return withContext(Dispatchers.Default) {
            val activeById = collected.associateBy { it.id }
            val filtered = if (archivedIds.isEmpty()) {
                collected
            } else {
                collected.filterNot { summary ->
                    hasArchivedAncestor(summary.parentId, archivedIds, activeById)
                }
            }
            val sorted = filtered.sortedByDescending { it.updatedAtMs }
            val roots = buildSessionTree(sorted)
            sorted to roots
        }
    }

    private fun hasArchivedAncestor(
        initialParentId: String?,
        archivedIds: Set<String>,
        activeById: Map<String, SessionSummary>,
    ): Boolean {
        val visited = HashSet<String>()
        var currentId = initialParentId
        while (currentId != null && visited.add(currentId)) {
            if (currentId in archivedIds) {
                return true
            }
            currentId = activeById[currentId]?.parentId
        }
        return false
    }

    private fun toSessionSummary(dto: SessionDto, isArchived: Boolean): SessionSummary {
        val resolvedDir = (dto.location?.directory ?: dto.directory)?.trim().orEmpty()
        val resolvedTitle = dto.title?.takeIf { it.isNotBlank() } ?: dto.id
        val parentId = dto.parentID?.trim()?.takeIf { it.isNotEmpty() }
        val idleMs = parseTimestampMs(dto.time?.idle)?.takeIf { it > 0L }
        val viewedMs = parseTimestampMs(dto.time?.viewed)?.takeIf { it > 0L }
        val markedUnreadAtMs = extractMarkedUnreadAtMs(dto.metadata)
        val updatedAtMs = parseTimestampMs(dto.time?.updated)?.takeIf { it > 0L }
            ?: parseTimestampMs(dto.time?.created)?.takeIf { it > 0L }
            ?: 0L

        val canBeUnread = (parentId == null) && !isArchived
        val hasUnseenTurn = idleMs != null && (viewedMs == null || viewedMs < idleMs)
        val hasUnreadMark = markedUnreadAtMs != null
        val isUnread = canBeUnread && (hasUnseenTurn || hasUnreadMark)
        val isUnreadWithError = canBeUnread && hasUnseenTurn && dto.outcome == "failed"

        return SessionSummary(
            id = dto.id,
            title = resolvedTitle,
            directory = resolvedDir,
            parentId = parentId,
            updatedAtMs = updatedAtMs,
            isUnread = isUnread,
            isUnreadWithError = isUnreadWithError,
        )
    }

    private fun extractMarkedUnreadAtMs(metadata: JsonElement?): Long? {
        val root = metadata as? JsonObject ?: return null
        val openchamber = root["openchamber"] as? JsonObject ?: return null
        val markedUnread = openchamber["markedUnread"] as? JsonObject ?: return null
        val atPrimitive = markedUnread["at"] as? JsonPrimitive ?: return null
        return parseTimestampMs(atPrimitive)?.takeIf { it > 0L }
    }

    private fun parseTimestampMs(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.takeIf { it.isFinite() }?.toLong()
    }

    companion object {
        private const val PAGE_LIMIT = 500
        private const val MAX_PAGES = 20

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
