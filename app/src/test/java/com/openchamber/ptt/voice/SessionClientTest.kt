package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import com.openchamber.ptt.session.SessionApiException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SessionClientTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

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
    fun `getLatestAssistantMessageId reads newest-first messages and returns latest assistant id`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "msg_user_2", "type": "user", "text": "hello" },
                        { "id": "msg_asst_1", "type": "assistant", "content": [{ "type": "text", "text": "prior" }] },
                        { "id": "msg_asst_0", "type": "assistant", "content": [{ "type": "text", "text": "older" }] }
                      ],
                      "cursor": {}
                    }
                    """.trimIndent(),
                ),
        )

        val client = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" })
        val sessionClient = SessionClient(httpClient = client)
        val baseUrl = server.url("/").toString().trimEnd('/')

        val latestId = sessionClient.getLatestAssistantMessageId(
            serverUrl = baseUrl,
            sessionId = "ses_1",
            directory = "/repo/app",
        )

        assertEquals("msg_asst_1", latestId)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/session/ses_1/message?limit=${SessionClient.DEFAULT_PAGE_LIMIT}", req.path)
        assertEquals("Bearer oc_tok", req.getHeader("Authorization"))
        assertEquals("%2Frepo%2Fapp", req.getHeader("x-opencode-directory"))
        assertEquals("uri", req.getHeader("x-opencode-directory-encoding"))
    }

    @Test
    fun `sendPrompt posts text payload with directory headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"msg_prompt_1"}"""),
        )

        val client = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" })
        val sessionClient = SessionClient(httpClient = client)
        val baseUrl = server.url("/").toString().trimEnd('/')

        val acceptedId = sessionClient.sendPrompt(
            serverUrl = baseUrl,
            sessionId = "ses_1",
            directory = "/repo/app",
            text = "Summarize the failing test",
        )

        assertEquals("msg_prompt_1", acceptedId)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/session/ses_1/prompt", req.path)
        assertEquals("Bearer oc_tok", req.getHeader("Authorization"))
        assertEquals("%2Frepo%2Fapp", req.getHeader("x-opencode-directory"))
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("Summarize the failing test", body["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fetchNewAssistantText extracts only text parts from messages newer than afterMessageId`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "msg_asst_new",
                          "type": "assistant",
                          "content": [
                            { "type": "reasoning", "text": "internal thought" },
                            { "type": "text", "text": "First paragraph." },
                            { "type": "tool", "id": "call_1", "name": "read" },
                            { "type": "text", "text": "Second paragraph." }
                          ]
                        },
                        {
                          "id": "msg_user_new",
                          "type": "user",
                          "text": "my prompt"
                        },
                        {
                          "id": "msg_asst_old",
                          "type": "assistant",
                          "content": [
                            { "type": "text", "text": "Old response" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val client = OpenChamberHttp.createClient(tokenProvider = { "oc_tok" })
        val sessionClient = SessionClient(httpClient = client)
        val baseUrl = server.url("/").toString().trimEnd('/')

        val text = sessionClient.fetchNewAssistantText(
            serverUrl = baseUrl,
            sessionId = "ses_1",
            directory = "/repo/app",
            afterMessageId = "msg_asst_old",
        )

        assertEquals("First paragraph.\n\nSecond paragraph.", text)
    }

    @Test
    fun `fetchNewAssistantText returns null when no newer assistant message exists`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "msg_asst_old",
                          "type": "assistant",
                          "content": [
                            { "type": "text", "text": "Old response" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val text = sessionClient.fetchNewAssistantText(
            serverUrl = baseUrl,
            sessionId = "ses_1",
            directory = null,
            afterMessageId = "msg_asst_old",
        )

        assertNull(text)
    }

    @Test
    fun `sendPrompt throws SessionApiException on HTTP failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        try {
            sessionClient.sendPrompt(
                serverUrl = baseUrl,
                sessionId = "ses_1",
                directory = null,
                text = "hello",
            )
            fail("Expected SessionApiException")
        } catch (ex: SessionApiException) {
            assertEquals(503, ex.statusCode)
        }
    }

    @Test
    fun `fetchLatestTurn extracts multi-step assistant reply and strips voice tag from user prompt`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "msg_idle_2",
                          "type": "idle",
                          "outcome": "succeeded",
                          "time": { "created": 3000 }
                        },
                        {
                          "id": "msg_asst_2b",
                          "type": "assistant",
                          "finish": "stop",
                          "time": { "created": 2500, "completed": 2900 },
                          "content": [{ "type": "text", "text": "Here is the summary." }]
                        },
                        {
                          "id": "msg_asst_2a",
                          "type": "assistant",
                          "finish": "tool-calls",
                          "time": { "created": 2100, "completed": 2300 },
                          "content": [{ "type": "text", "text": "Reading git diff." }]
                        },
                        {
                          "id": "msg_user_2",
                          "type": "user",
                          "time": { "created": 2000 },
                          "content": [{ "type": "text", "text": "[voice]\nsummarize the diff" }]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val latest = sessionClient.fetchLatestTurn(baseUrl, "ses_1", "/repo/app")

        assertEquals("summarize the diff", latest.userText)
        assertEquals("Reading git diff.\n\nHere is the summary.", latest.assistantText)
        assertEquals("msg_asst_2b", latest.assistantMessageId)
    }

    @Test
    fun `fetchTurnsWindow paginates via cursor without order param and stops once targets and boundary are reached`() = runTest {
        // Page 1 (desc): newest idle + 2 assistant steps, NO opener yet -> must continue to Page 2
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "idle_2", "type": "idle", "outcome": "succeeded", "time": { "created": 5000 } },
                        { "id": "asst_2b", "type": "assistant", "finish": "stop", "time": { "created": 4600, "completed": 4900 }, "text": "Final step." },
                        { "id": "asst_2a", "type": "assistant", "finish": "tool-calls", "time": { "created": 4200, "completed": 4400 }, "content": [{ "type": "text", "text": "Running checks." }] }
                      ],
                      "cursor": { "next": "cur/page2" }
                    }
                    """.trimIndent(),
                ),
        )
        // Page 2 (desc): opener u2 + older settled turn's stop target asst_1 -> must stop here and NOT fetch Page 3
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "user_2", "type": "user", "time": { "created": 4000 }, "text": "[voice] Run the checks" },
                        { "id": "idle_1", "type": "idle", "outcome": "succeeded", "time": { "created": 3000 } },
                        { "id": "asst_1", "type": "assistant", "time": { "created": 2000, "completed": 2500 }, "text": "Old reply." }
                      ],
                      "cursor": { "next": "cur/page3" }
                    }
                    """.trimIndent(),
                ),
        )
        // Page 3 (should not be fetched)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"user_1","type":"user","text":"Should not fetch"}],"cursor":{}}"""),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val window = sessionClient.fetchTurnsWindow(
            serverUrl = baseUrl,
            sessionId = "ses_paginated",
            stopAtMessageIds = setOf("asst_1"),
            maxPages = 4,
            pageLimit = 3,
        )

        assertEquals(2, server.requestCount)
        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertEquals("/api/session/ses_paginated/message?limit=3", req1.path)
        assertEquals("/api/session/ses_paginated/message?limit=3&cursor=cur%2Fpage2", req2.path)
        assertFalse(req1.path.orEmpty().contains("order="))
        assertFalse(req2.path.orEmpty().contains("order="))

        assertFalse(window.reachedHistoryStart)
        assertEquals("idle_2", window.latestSettledMessageId)
        assertEquals(listOf("asst_1", "idle_1", "user_2", "asst_2a", "asst_2b", "idle_2"), window.records.map { it.id })
        assertEquals(2, window.turns.size)
        assertEquals("Run the checks", window.turns[1].triggerText)
        assertEquals(listOf("asst_2a", "asst_2b"), window.turns[1].responses.map { it.messageId })
    }

    @Test
    fun `fetchTurnsWindow parses OpenCode 2x synthetic wrappers, streaming assistants, other records, and legacy arrays`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "asst_stream",
                          "type": "assistant",
                          "time": { "created": 7000 },
                          "content": [{ "type": "text", "text": "Synthesizing..." }]
                        },
                        {
                          "id": "syn_sub",
                          "type": "synthetic",
                          "time": { "created": 6000 },
                          "description": "Subagent explore finished",
                          "metadata": { "source": "subagent", "agent": "explore" },
                          "text": "<subagent id=\"sub_1\" agent=\"explore\">\n  Found 3 files.\n</subagent>"
                        },
                        {
                          "id": "idle_async",
                          "type": "idle",
                          "outcome": "succeeded",
                          "time": { "created": 5000 }
                        },
                        {
                          "id": "asst_async",
                          "type": "assistant",
                          "finish": "stop",
                          "time": { "created": 4500, "completed": 4900 },
                          "parts": [{ "type": "text", "text": "Worker reported clean tests." }]
                        },
                        {
                          "id": "syn_async",
                          "type": "synthetic",
                          "time": { "created": 4000 },
                          "metadata": { "source": "openchamber-session" },
                          "text": "<openchamber-session sessionId=\"ses_w1\" title=\"a -> b\">\n42 tests passed.\n</openchamber-session>"
                        },
                        {
                          "id": "syn_ctx",
                          "type": "synthetic",
                          "time": { "created": 3500 },
                          "metadata": { "source": "context" },
                          "text": "Non-trigger context note"
                        },
                        {
                          "id": "other_switch",
                          "type": "agent-switched",
                          "time": { "created": 3200 }
                        },
                        {
                          "info": { "id": "legacy_asst", "role": "assistant" },
                          "parts": [{ "type": "text", "text": "Legacy response." }]
                        },
                        {
                          "info": { "id": "legacy_user", "role": "user" },
                          "parts": [{ "type": "text", "text": "[voice] Legacy question" }]
                        }
                      ],
                      "cursor": {}
                    }
                    """.trimIndent(),
                ),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val window = sessionClient.fetchTurnsWindow(baseUrl, "ses_2x")

        assertTrue(window.reachedHistoryStart)
        // Latest turn is streaming (asst_stream has time.created without completed/finish/idle) -> anchors to idle_async
        assertEquals("idle_async", window.latestSettledMessageId)
        assertEquals(3, window.turns.size)

        val legacyTurn = window.turns[0]
        assertEquals("Legacy question", legacyTurn.triggerText)
        assertTrue(legacyTurn.responses.single().isCompleted)
        assertTrue(legacyTurn.responses.single().isFinalStep)
        assertEquals("syn_ctx", legacyTurn.latestMessageId)

        val asyncTurn = window.turns[1]
        assertEquals(TurnTriggerKind.ASYNC_SESSION_RESULT, asyncTurn.triggerKind)
        assertEquals("a -> b", asyncTurn.triggerLabel)
        assertEquals("42 tests passed.", asyncTurn.triggerText)
        assertTrue(asyncTurn.isIdle)
        assertEquals("succeeded", asyncTurn.outcome)

        val subagentTurn = window.turns[2]
        assertEquals(TurnTriggerKind.SUBAGENT_REPORT, subagentTurn.triggerKind)
        assertEquals("Subagent explore finished", subagentTurn.triggerLabel)
        assertEquals("Found 3 files.", subagentTurn.triggerText)
        assertFalse(subagentTurn.isIdle)
        assertFalse(subagentTurn.responses.single().isCompleted)
        assertFalse(subagentTurn.responses.single().isFinalStep)
    }

    @Test
    fun `fetchTurnsWindow handles bare JsonArray payload and empty trailing page`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      { "info": { "id": "m_asst", "role": "assistant" }, "parts": [{ "type": "text", "text": "Array reply" }] },
                      { "info": { "id": "m_user", "role": "user" }, "parts": [{ "type": "text", "text": "Array prompt" }] }
                    ]
                    """.trimIndent(),
                ),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val arrayWindow = sessionClient.fetchTurnsWindow(baseUrl, "ses_arr")

        assertTrue(arrayWindow.reachedHistoryStart)
        assertEquals(1, arrayWindow.turns.size)
        assertEquals("Array prompt", arrayWindow.turns.single().triggerText)
        assertEquals("Array reply", arrayWindow.turns.single().responses.single().assistantText)

        // OpenCode pagination convention: non-empty page returns cursor.next, trailing empty page clears cursor
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "a_only", "type": "assistant", "text": "Single page reply" },
                        { "id": "u_only", "type": "user", "text": "Single page prompt" }
                      ],
                      "cursor": { "next": "p_empty" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[],"cursor":{}}"""),
        )

        val emptyTailWindow = sessionClient.fetchTurnsWindow(
            serverUrl = baseUrl,
            sessionId = "ses_tail",
            stopAtMessageIds = setOf("missing_older_id"),
            maxPages = 4,
        )

        assertTrue(emptyTailWindow.reachedHistoryStart)
        assertEquals(1, emptyTailWindow.turns.size)
        assertEquals("Single page prompt", emptyTailWindow.turns.single().triggerText)
    }

    @Test
    fun `fetchNewAssistantText paginates across pages to locate afterMessageId`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "a_new_2", "type": "assistant", "text": "Step 2" }
                      ],
                      "cursor": { "next": "p2" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "a_new_1", "type": "assistant", "text": "Step 1" },
                        { "id": "u_turn", "type": "user", "text": "Do work" },
                        { "id": "a_baseline", "type": "assistant", "text": "Older baseline" }
                      ],
                      "cursor": { "next": "p3" }
                    }
                    """.trimIndent(),
                ),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val text = sessionClient.fetchNewAssistantText(
            serverUrl = baseUrl,
            sessionId = "ses_multi",
            afterMessageId = "a_baseline",
        )

        assertEquals(2, server.requestCount)
        assertEquals("Step 1\n\nStep 2", text)
    }

    @Test
    fun `fetchTurnsWindow with stopOnAnyTarget stops on Page 1 when newest cursor bookmark is present even if older target is absent`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        { "id": "idle_3", "type": "idle", "outcome": "succeeded", "time": { "created": 500 } },
                        { "id": "a3", "type": "assistant", "finish": "stop", "time": { "created": 450, "completed": 480 }, "text": "Latest reply" },
                        { "id": "u3", "type": "user", "time": { "created": 400 }, "text": "Turn 3" },
                        { "id": "a_heard", "type": "assistant", "finish": "stop", "time": { "created": 300, "completed": 350 }, "text": "Heard reply" }
                      ],
                      "cursor": { "next": "page_2_should_not_fetch" }
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"a_old_baseline","type":"assistant","text":"Old baseline"}],"cursor":{}}"""),
        )

        val sessionClient = SessionClient(httpClient = OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')
        val window = sessionClient.fetchTurnsWindow(
            serverUrl = baseUrl,
            sessionId = "ses_fast_stop",
            stopAtMessageIds = setOf("a_heard", "a_old_baseline"),
            stopOnAnyTarget = true,
            maxPages = 4,
            pageLimit = 4,
        )

        assertEquals(1, server.requestCount)
        assertEquals(listOf("a_heard", "u3", "a3", "idle_3"), window.records.map { it.id })
    }
}
