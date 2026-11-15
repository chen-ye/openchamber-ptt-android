package com.openchamber.ptt.auth

import android.os.Build
import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

class AuthException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
data class AuthSessionRequest(
    val password: String,
    val trustDevice: Boolean = true,
    val issueClientToken: Boolean = true,
    val clientLabel: String = "OpenChamber PTT",
    val clientKind: String = "mobile",
    val devicePlatform: String = "android",
    val deviceName: String,
    val deviceModel: String = Build.MODEL?.trim().orEmpty(),
    val dedupeKey: String? = null,
)

@Serializable
data class AuthSessionDataDto(
    val token: String? = null,
    val clientToken: String? = null,
)

@Serializable
data class AuthSessionResponse(
    val authenticated: Boolean? = null,
    val token: String? = null,
    val clientToken: String? = null,
    val data: AuthSessionDataDto? = null,
    val error: String? = null,
    val message: String? = null,
)

class AuthClient(
    private val httpClient: OkHttpClient = OpenChamberHttp.createClient(),
    private val json: Json = DefaultJson,
) {
    suspend fun authenticate(
        serverUrl: String,
        password: String,
        deviceName: String = defaultDeviceName(),
        dedupeKey: String? = null,
    ): String {
        val baseUrl = OpenChamberHttp.normalizeBaseUrl(serverUrl)
        val payload = AuthSessionRequest(
            password = password,
            trustDevice = true,
            issueClientToken = true,
            clientLabel = "OpenChamber PTT",
            clientKind = "mobile",
            devicePlatform = "android",
            deviceName = deviceName.trim().ifEmpty { DEFAULT_DEVICE_NAME },
            dedupeKey = dedupeKey?.trim()?.takeIf { it.isNotEmpty() },
        )

        val requestBody = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/auth/session")
            .post(requestBody)
            .header("Accept", "application/json")
            .build()

        val (statusCode, bodyText) = executeRequest(request)

        val parsed = runCatching {
            if (bodyText.isBlank()) null else json.decodeFromString<AuthSessionResponse>(bodyText)
        }.getOrNull()

        if (statusCode !in 200..299) {
            val detail = parsed?.error ?: parsed?.message ?: bodyText.ifBlank { "HTTP $statusCode" }
            throw AuthException(statusCode, "Authentication failed ($statusCode): $detail")
        }

        val token = parsed?.clientToken
            ?: parsed?.data?.clientToken
            ?: parsed?.token
            ?: parsed?.data?.token

        if (token.isNullOrBlank()) {
            throw AuthException(
                statusCode,
                "Authentication succeeded ($statusCode) but response did not include a client token",
            )
        }

        return token.trim()
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
                            AuthException(null, "Network error during authentication: ${e.message}", e),
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
        private const val DEFAULT_DEVICE_NAME = "Android Device"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

        fun defaultDeviceName(): String {
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            val model = Build.MODEL?.trim().orEmpty()
            val combined = when {
                manufacturer.isEmpty() && model.isEmpty() -> DEFAULT_DEVICE_NAME
                model.startsWith(manufacturer, ignoreCase = true) -> model
                manufacturer.isEmpty() -> model
                model.isEmpty() -> manufacturer
                else -> "$manufacturer $model"
            }
            return combined.ifBlank { DEFAULT_DEVICE_NAME }
        }
    }
}
