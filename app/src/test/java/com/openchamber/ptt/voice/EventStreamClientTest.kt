package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class EventStreamClientTest {
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
    fun `filters strictly by targetSessionId ignores heartbeats and shutdown interruptions and maps TurnEvents`() =
        runTest {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"ready","scope":"global"}""")
                            // 15s server heartbeat - must be ignored
                            webSocket.send(
                                """{"type":"event","directory":"global","payload":{"type":"openchamber:heartbeat","timestamp":1700000000000}}""",
                            )
                            // Subagent events - must be filtered out
                            webSocket.send(
                                """{"type":"event","eventId":"evt_sub_1","payload":{"id":"evt_sub_1","type":"permission.asked","data":{"id":"per_sub","sessionID":"ses_subagent","action":"bash"}}}""",
                            )
                            webSocket.send(
                                """{"type":"event","eventId":"evt_sub_2","payload":{"id":"evt_sub_2","type":"session.execution.succeeded","data":{"sessionID":"ses_subagent"}}}""",
                            )
                            webSocket.send(
                                """{"type":"event","eventId":"evt_sub_3","payload":{"id":"evt_sub_3","type":"session.execution.failed","data":{"sessionID":"ses_subagent","error":{"message":"sub fail"}}}}""",
                            )
                            // Target session shutdown interruption - must be ignored
                            webSocket.send(
                                """{"type":"event","eventId":"evt_shut","payload":{"id":"evt_shut","type":"session.execution.interrupted","data":{"sessionID":"ses_target","reason":"shutdown"}}}""",
                            )
                            // Target session permission.asked
                            webSocket.send(
                                """{"type":"event","eventId":"evt_perm","payload":{"id":"evt_perm","type":"permission.asked","data":{"id":"per_1","sessionID":"ses_target","action":"bash","resources":["ls"]}}}""",
                            )
                            // Target session user interruption
                            webSocket.send(
                                """{"type":"event","eventId":"evt_int","payload":{"id":"evt_int","type":"session.execution.interrupted","data":{"sessionID":"ses_target","reason":"user"}}}""",
                            )
                            // Target session failure
                            webSocket.send(
                                """{"type":"event","eventId":"evt_fail","payload":{"id":"evt_fail","type":"session.execution.failed","data":{"sessionID":"ses_target","error":{"type":"ProviderError","message":"rate limited"}}}}""",
                            )
                            // Target session completion
                            webSocket.send(
                                """{"type":"event","eventId":"evt_done","payload":{"id":"evt_done","type":"session.execution.succeeded","data":{"sessionID":"ses_target"}}}""",
                            )
                        }
                    },
                ),
            )

            val client = OpenChamberHttp.createClient(
                tokenProvider = { "oc_client_events_token" },
            )
            val streamClient = EventStreamClient(
                httpClient = client,
                initialReconnectDelayMs = 20L,
                maxReconnectDelayMs = 100L,
            )

            val baseUrl = server.url("/").toString().trimEnd('/')
            val events = streamClient
                .observeTurnEvents(serverUrl = baseUrl, targetSessionId = "ses_target")
                .take(4)
                .toList()

            assertEquals(
                listOf(
                    TurnEvent.PermissionAsked("bash"),
                    TurnEvent.Interrupted("user"),
                    TurnEvent.Failed("rate limited"),
                    TurnEvent.Completed,
                ),
                events,
            )

            val recorded = server.takeRequest()
            assertEquals("/api/global/event/ws", recorded.path)
            assertEquals("Bearer oc_client_events_token", recorded.getHeader("Authorization"))
            assertEquals("http://${server.hostName}:${server.port}", recorded.getHeader("Origin"))
        }

    @Test
    fun `reconnects with lastEventId query parameter after disconnect`() = runTest {
        // First connection sends an event from another session (advancing cursor) then closes
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ready","scope":"global"}""")
                        webSocket.send(
                            """{"type":"event","eventId":"evt-42","payload":{"id":"evt-42","type":"session.text.delta","data":{"sessionID":"ses_other","delta":"hi"}}}""",
                        )
                        webSocket.close(1011, "upstream restart")
                    }
                },
            ),
        )

        // Second connection expects ?lastEventId=evt-42 and delivers completion for target session
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ready","scope":"global"}""")
                        webSocket.send(
                            """{"type":"event","eventId":"evt-43","payload":{"id":"evt-43","type":"session.execution.succeeded","data":{"sessionID":"ses_target"}}}""",
                        )
                    }
                },
            ),
        )

        val streamClient = EventStreamClient(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_client_reconnect" }),
            initialReconnectDelayMs = 20L,
            maxReconnectDelayMs = 50L,
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val events = streamClient
            .observeTurnEvents(serverUrl = baseUrl, targetSessionId = "ses_target")
            .take(1)
            .toList()

        assertEquals(listOf(TurnEvent.Completed), events)

        val firstReq = server.takeRequest()
        assertEquals("/api/global/event/ws", firstReq.path)

        val secondReq = server.takeRequest()
        assertEquals("/api/global/event/ws?lastEventId=evt-42", secondReq.path)
    }

    @Test
    fun `maps openchamber session-status idle to Completed and ignores busy`() {
        val streamClient = EventStreamClient(
            httpClient = OpenChamberHttp.createClient(tokenProvider = { "oc_client_test" }),
        )

        val busyParsed = streamClient.parseFrame(
            """{"type":"event","eventId":"evt-50","payload":{"type":"openchamber:session-status","properties":{"sessionID":"ses_target","status":"busy","timestamp":1}}}""",
            targetSessionId = "ses_target",
        )
        assertEquals(null, busyParsed.turnEvent)

        val idleParsed = streamClient.parseFrame(
            """{"type":"event","eventId":"evt-51","payload":{"type":"openchamber:session-status","properties":{"sessionID":"ses_target","status":"idle","timestamp":2}}}""",
            targetSessionId = "ses_target",
        )
        assertEquals(TurnEvent.Completed, idleParsed.turnEvent)

        val permReplied = streamClient.parseFrame(
            """{"type":"event","eventId":"evt-52","payload":{"type":"permission.replied","properties":{"sessionID":"ses_target","requestID":"req-1"}}}""",
            targetSessionId = "ses_target",
        )
        assertEquals(TurnEvent.PermissionResolved, permReplied.turnEvent)
    }
}
