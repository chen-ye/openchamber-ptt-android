package com.openchamber.ptt.network

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenChamberHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `websocket handshake includes Bearer, directory headers, and canonical Origin`() {
        val openLatch = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))

        val client = OpenChamberHttp.createClient(
            tokenProvider = { "oc_client_ws_token" },
            directoryProvider = { "/workspace/openchamber" },
        )

        val wsUrl = server.url("/ws/ptt").toString().replaceFirst("http://", "ws://")
        val requestWithSpoofedOrigin = Request.Builder()
            .url(wsUrl)
            .header("Origin", "https://evil.example")
            .build()

        val ws = client.newWebSocket(
            requestWithSpoofedOrigin,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openLatch.countDown()
                    webSocket.close(1000, "done")
                }
            },
        )

        assertTrue("WebSocket did not open in time", openLatch.await(5, TimeUnit.SECONDS))
        ws.cancel()

        val recorded = server.takeRequest()
        assertEquals("Bearer oc_client_ws_token", recorded.getHeader("Authorization"))
        assertEquals("%2Fworkspace%2Fopenchamber", recorded.getHeader("x-opencode-directory"))
        assertEquals("uri", recorded.getHeader("x-opencode-directory-encoding"))
        assertEquals("http://${server.hostName}:${server.port}", recorded.getHeader("Origin"))
    }

    @Test
    fun `tokenForUrlProvider routes distinct bearer tokens per server origin`() {
        val serverB = MockWebServer()
        serverB.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("okA"))
            serverB.enqueue(MockResponse().setResponseCode(200).setBody("okB"))

            val urlA = server.url("/api/session").toString()
            val urlB = serverB.url("/api/session").toString()
            val client = OpenChamberHttp.createClient(
                tokenForUrlProvider = { url ->
                    when (url.port) {
                        server.port -> "token_A"
                        serverB.port -> "token_B"
                        else -> null
                    }
                },
            )

            client.newCall(Request.Builder().url(urlA).get().build()).execute().close()
            client.newCall(Request.Builder().url(urlB).get().build()).execute().close()

            assertEquals("Bearer token_A", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer token_B", serverB.takeRequest().getHeader("Authorization"))
        } finally {
            serverB.shutdown()
        }
    }

    @Test
    fun `tokenForUrlProvider and directoryForUrlProvider do not fall back to global active providers on unknown URL`() {
        val serverB = MockWebServer()
        serverB.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("okA"))
            serverB.enqueue(MockResponse().setResponseCode(200).setBody("okB"))

            val urlA = server.url("/api/session").toString()
            val urlB = serverB.url("/auth/session").toString()
            val client = OpenChamberHttp.createClient(
                tokenProvider = { "active_token_should_not_leak" },
                directoryProvider = { "/active/dir/should_not_leak" },
                tokenForUrlProvider = { url -> if (url.port == server.port) "token_A" else null },
                directoryForUrlProvider = { url -> if (url.port == server.port) "/repo/a" else null },
            )

            client.newCall(Request.Builder().url(urlA).get().build()).execute().close()
            client.newCall(Request.Builder().url(urlB).get().build()).execute().close()

            val reqA = server.takeRequest()
            assertEquals("Bearer token_A", reqA.getHeader("Authorization"))
            assertEquals("%2Frepo%2Fa", reqA.getHeader("x-opencode-directory"))

            val reqB = serverB.takeRequest()
            assertNull(reqB.getHeader("Authorization"))
            assertNull(reqB.getHeader("x-opencode-directory"))
        } finally {
            serverB.shutdown()
        }
    }

    @Test
    fun `omitDirectoryHeaders prevents directoryForUrlProvider and directoryProvider injection`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = OpenChamberHttp.createClient(
            tokenProvider = { "tok_global" },
            directoryProvider = { "/repo/default" },
            directoryForUrlProvider = { "/repo/scoped" },
        )

        val request = OpenChamberHttp.omitDirectoryHeaders(
            Request.Builder().url(server.url("/api/session")).get(),
        ).build()
        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertEquals("Bearer tok_global", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("x-opencode-directory"))
        assertNull(recorded.getHeader("x-opencode-directory-encoding"))
    }
}
