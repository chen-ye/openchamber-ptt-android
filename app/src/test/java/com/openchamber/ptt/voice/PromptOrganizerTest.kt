package com.openchamber.ptt.voice

import com.openchamber.ptt.network.OpenChamberHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PromptOrganizerTest {
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
    fun `cleanQuickTranscript calls small-model generate and returns cleaned text`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"Check PttForegroundService and run unit tests."}"""),
        )

        val organizer = PromptOrganizer(OpenChamberHttp.createClient(tokenProvider = { "oc_tok" }))
        val res = organizer.cleanQuickTranscript(
            serverUrl = server.url("/").toString().trimEnd('/'),
            rawTranscript = "um check ptt foreground service and run unit tests",
            sessionLabel = "Voice UX",
        )

        assertEquals("Check PttForegroundService and run unit tests.", res.text)
        assertTrue(res.usedSmallModel)
        assertFalse(res.fallbackUsed)

        val req = server.takeRequest()
        assertEquals("/api/small-model/generate", req.path)
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(PromptOrganizer.QUICK_CLEANUP_SYSTEM_PROMPT, body["system"]?.jsonPrimitive?.content)
        assertTrue(body["prompt"]?.jsonPrimitive?.content?.contains("Session: Voice UX") == true)
    }

    @Test
    fun `organizeDraft smart-merges existing draft with new burst and falls back on error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"Use SQLite for caching and add a 2-second timeout."}"""),
        )
        server.enqueue(MockResponse().setResponseCode(500).setBody("down"))

        val organizer = PromptOrganizer(OpenChamberHttp.createClient())
        val baseUrl = server.url("/").toString().trimEnd('/')

        val merged = organizer.organizeDraft(
            serverUrl = baseUrl,
            existingDraft = "Use Redis for caching.",
            newBurst = "Actually scratch Redis, use SQLite instead and add a 2-second timeout.",
            sessionLabel = "Cache",
        )
        assertEquals("Use SQLite for caching and add a 2-second timeout.", merged.text)
        assertTrue(merged.usedSmallModel)

        val req = server.takeRequest()
        val body = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(PromptOrganizer.SMART_MERGE_SYSTEM_PROMPT, body["system"]?.jsonPrimitive?.content)
        assertTrue(body["prompt"]?.jsonPrimitive?.content?.contains("Existing draft:") == true)
        assertTrue(body["prompt"]?.jsonPrimitive?.content?.contains("New spoken follow-up:") == true)

        val fallback = organizer.organizeDraft(
            serverUrl = baseUrl,
            existingDraft = "First paragraph.",
            newBurst = "Second paragraph.",
        )
        assertEquals("First paragraph.\n\nSecond paragraph.", fallback.text)
        assertTrue(fallback.fallbackUsed)
        assertFalse(fallback.usedSmallModel)
    }

    @Test
    fun `organizeDraft propagates HTTP 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val organizer = PromptOrganizer(OpenChamberHttp.createClient())
        try {
            organizer.cleanQuickTranscript(server.url("/").toString().trimEnd('/'), "hello world")
            fail("Expected PromptOrganizerException(401)")
        } catch (ex: PromptOrganizerException) {
            assertEquals(401, ex.statusCode)
        }
    }
}
