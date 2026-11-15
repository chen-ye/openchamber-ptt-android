package com.openchamber.ptt.session

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.storage.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SessionApiException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class SessionSummary(
    val id: String,
    val title: String,
    val directory: String,
)

data class ServerSessionsResult(
    val server: ServerProfile,
    val sessions: List<SessionSummary> = emptyList(),
    val error: String? = null,
    val isUnauthorized: Boolean = false,
)

@Serializable
data class SessionLocationDto(
    val directory: String? = null,
)

@Serializable
data class SessionTimeDto(
    val created: Long? = null,
    val updated: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    val directory: String? = null,
    val location: SessionLocationDto? = null,
    val time: SessionTimeDto? = null,
)

@Serializable
data class SessionsEnvelopeDto(
    val data: List<SessionDto> = emptyList(),
)

class SessionRepository(
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
                        val sessions = listSessions(
                            serverUrl = server.serverUrl,
                            directory = server.lastPinnedSession?.directory,
                        )
                        ServerSessionsResult(server = server, sessions = sessions)
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
    ): List<SessionSummary> {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val requestBuilder = Request.Builder()
            .url("$baseUrl/api/session")
            .get()
            .header("Accept", "application/json")

        OpenChamberHttp.applyDirectoryHeaders(requestBuilder, directory)

        val (statusCode, bodyText) = executeRequest(requestBuilder.build())
        if (statusCode !in 200..299) {
            throw SessionApiException(
                statusCode,
                "Failed to fetch sessions ($statusCode): ${bodyText.ifBlank { "HTTP $statusCode" }}",
            )
        }

        val envelope = try {
            json.decodeFromString<SessionsEnvelopeDto>(bodyText)
        } catch (e: Exception) {
            throw SessionApiException(statusCode, "Invalid sessions JSON payload: ${e.message}", e)
        }

        return envelope.data
            .filter { dto -> (dto.time?.archived ?: 0L) <= 0L }
            .map { dto ->
                val resolvedDir = dto.location?.directory ?: dto.directory ?: ""
                val resolvedTitle = dto.title?.takeIf { it.isNotBlank() } ?: dto.id
                SessionSummary(
                    id = dto.id,
                    title = resolvedTitle,
                    directory = resolvedDir,
                )
            }
    }

    private suspend fun executeRequest(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(
                            SessionApiException(null, "Network error fetching sessions: ${e.message}", e),
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

    companion object {
        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
