package com.openchamber.ptt.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OpenChamberHttp {
    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_ORIGIN = "Origin"
    const val HEADER_DIRECTORY = "x-opencode-directory"
    const val HEADER_DIRECTORY_ENCODING = "x-opencode-directory-encoding"
    const val DIRECTORY_ENCODING_URI = "uri"
    const val SMALL_MODEL_GENERATE_PATH = "/api/small-model/generate"

    suspend fun executeText(client: OkHttpClient, request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            val text = resp.body?.string().orEmpty()
                            if (cont.isActive) cont.resume(resp.code to text)
                        }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }

    fun createClient(
        tokenProvider: () -> String? = { null },
        directoryProvider: (() -> String?)? = null,
        baseClient: OkHttpClient? = null,
        tokenForUrlProvider: ((HttpUrl) -> String?)? = null,
        directoryForUrlProvider: ((HttpUrl) -> String?)? = null,
    ): OkHttpClient {
        val builder = (baseClient ?: sharedBaseClient).newBuilder()

        builder.addInterceptor(
            OpenChamberAuthAndDirectoryInterceptor(
                tokenProvider = tokenProvider,
                tokenForUrlProvider = tokenForUrlProvider,
                directoryProvider = directoryProvider,
                directoryForUrlProvider = directoryForUrlProvider,
            ),
        )
        builder.addNetworkInterceptor(WebSocketOriginInterceptor())

        return builder.build()
    }

    fun applyDirectoryHeaders(
        builder: Request.Builder,
        directory: String?,
    ): Request.Builder {
        val cleaned = directory?.trim()?.takeIf { it.isNotEmpty() } ?: return builder
        return builder
            .header(HEADER_DIRECTORY, encodeUriComponent(cleaned))
            .header(HEADER_DIRECTORY_ENCODING, DIRECTORY_ENCODING_URI)
    }

    internal fun omitDirectoryHeaders(builder: Request.Builder): Request.Builder =
        builder.tag(OmitDirectoryHeadersTag::class.java, OmitDirectoryHeadersTag)

    fun buildWebSocketRequest(
        url: String,
        directory: String? = null,
    ): Request {
        val builder = Request.Builder().url(url)
        applyDirectoryHeaders(builder, directory)
        val built = builder.build()
        return built.newBuilder()
            .header(HEADER_ORIGIN, canonicalOrigin(built.url))
            .build()
    }

    fun canonicalOrigin(url: HttpUrl): String {
        val defaultPort = if (url.isHttps) 443 else 80
        val hostPart = if (url.host.contains(':')) "[${url.host}]" else url.host
        return if (url.port == defaultPort) {
            "${url.scheme}://$hostPart"
        } else {
            "${url.scheme}://$hostPart:${url.port}"
        }
    }

    fun normalizeBaseUrl(serverUrl: String): String = serverUrl.trim().trimEnd('/')

    fun encodeUriComponent(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            if (isUnreservedUriComponentChar(code)) {
                out.append(code.toChar())
            } else {
                out.append('%')
                out.append(HEX_DIGITS[code ushr 4])
                out.append(HEX_DIGITS[code and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreservedUriComponentChar(code: Int): Boolean {
        return (code in 'A'.code..'Z'.code) ||
            (code in 'a'.code..'z'.code) ||
            (code in '0'.code..'9'.code) ||
            code == '-'.code ||
            code == '_'.code ||
            code == '.'.code ||
            code == '!'.code ||
            code == '~'.code ||
            code == '*'.code ||
            code == '\''.code ||
            code == '('.code ||
            code == ')'.code
    }

    private val sharedBaseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private object OmitDirectoryHeadersTag

    private class OpenChamberAuthAndDirectoryInterceptor(
        private val tokenProvider: () -> String?,
        private val tokenForUrlProvider: ((HttpUrl) -> String?)? = null,
        private val directoryProvider: (() -> String?)?,
        private val directoryForUrlProvider: ((HttpUrl) -> String?)? = null,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val builder = original.newBuilder()

            val token = if (tokenForUrlProvider != null) {
                tokenForUrlProvider.invoke(original.url)
            } else {
                tokenProvider()
            }?.trim()?.takeIf { it.isNotEmpty() }
            if (token != null && original.header(HEADER_AUTHORIZATION) == null) {
                builder.header(HEADER_AUTHORIZATION, "Bearer $token")
            }

            if (original.tag(OmitDirectoryHeadersTag::class.java) == null) {
                if (original.header(HEADER_DIRECTORY) == null) {
                    val directory = if (directoryForUrlProvider != null) {
                        directoryForUrlProvider.invoke(original.url)
                    } else {
                        directoryProvider?.invoke()
                    }
                    applyDirectoryHeaders(builder, directory)
                } else if (original.header(HEADER_DIRECTORY_ENCODING) == null) {
                    builder.header(HEADER_DIRECTORY_ENCODING, DIRECTORY_ENCODING_URI)
                }
            }

            if (original.header("Upgrade").equals("websocket", ignoreCase = true)) {
                builder.header(HEADER_ORIGIN, canonicalOrigin(original.url))
            }

            return chain.proceed(builder.build())
        }
    }

    private class WebSocketOriginInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header("Upgrade").equals("websocket", ignoreCase = true)) {
                val expectedOrigin = canonicalOrigin(request.url)
                if (request.header(HEADER_ORIGIN) != expectedOrigin) {
                    val normalized = request.newBuilder()
                        .header(HEADER_ORIGIN, expectedOrigin)
                        .build()
                    return chain.proceed(normalized)
                }
            }
            return chain.proceed(request)
        }
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
