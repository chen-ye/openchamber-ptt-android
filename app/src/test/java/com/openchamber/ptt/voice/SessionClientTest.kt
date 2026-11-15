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
import org.junit.Assert.assertNull
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
        assertEquals("/api/session/ses_1/message?limit=5", req.path)
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
    fun `fetchLatestTurn extracts latest assistant reply and strips voice tag from user prompt`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "msg_asst_2",
                          "type": "assistant",
                          "content": [{ "type": "text", "text": "Here is the summary." }]
                        },
                        {
                          "id": "msg_user_2",
                          "type": "user",
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
        assertEquals("Here is the summary.", latest.assistantText)
        assertEquals("msg_asst_2", latest.assistantMessageId)
    }
}
